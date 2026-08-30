package com.passwordmaster.service;

import com.passwordmaster.db.DatabaseManager;
import com.passwordmaster.model.Entry;
import com.passwordmaster.util.AesUtil;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.List;

/**
 * 密码业务服务
 * 负责主密码校验（PBKDF2 慢哈希）、条目加解密与 CRUD 编排
 * 主密码在关键路径上以 char[] 承载，用完立即清零
 */
public class PasswordService {

    /** 主密码校验结果 */
    public enum VerifyResult {
        /** 校验通过（新格式） */
        MATCH,
        /** 校验通过但为旧 SHA-256 格式，需要一次性迁移到 PBKDF2 */
        MATCH_NEED_UPGRADE,
        /** 密码错误 */
        MISMATCH
    }

    private final DatabaseManager db;

    public PasswordService(DatabaseManager db) {
        this.db = db;
    }

    /** 是否已初始化主密码 */
    public boolean isInitialized() {
        return db.isMasterSet();
    }

    /**
     * 设置（重置）主密码：生成随机盐，PBKDF2 派生校验哈希，
     * 存储格式 pbkdf2$迭代次数$盐hex$哈希hex
     */
    public void setMasterPassword(char[] masterPassword) {
        byte[] salt = AesUtil.generateSalt();
        String record = AesUtil.buildPbkdf2Record(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS);
        db.saveMasterHash(record);
    }

    /** 校验主密码是否正确（自动识别新旧格式） */
    public VerifyResult verifyMasterPassword(char[] masterPassword) {
        String stored = db.getMasterHash();
        if (stored == null || stored.isEmpty()) {
            return VerifyResult.MISMATCH;
        }
        if (AesUtil.isLegacyRecord(stored)) {
            // 旧格式：SHA-256 校验，通过后需升级迁移
            boolean ok = stored.equalsIgnoreCase(AesUtil.sha256Hex(new String(masterPassword)));
            return ok ? VerifyResult.MATCH_NEED_UPGRADE : VerifyResult.MISMATCH;
        }
        return AesUtil.verifyPassword(masterPassword, stored) ? VerifyResult.MATCH : VerifyResult.MISMATCH;
    }

    /**
     * 派生密钥（供加解密使用）
     * 新格式按库中盐/迭代次数派生；旧格式走 SHA-256（迁移完成前）
     */
    public SecretKey deriveKey(char[] masterPassword) {
        String stored = db.getMasterHash();
        if (stored != null && !AesUtil.isLegacyRecord(stored)) {
            byte[] salt = AesUtil.saltFromRecord(stored);
            int iterations = AesUtil.iterationsFromRecord(stored);
            if (salt != null) {
                return AesUtil.deriveKeyPbkdf2(masterPassword, salt, iterations);
            }
        }
        return AesUtil.deriveKey(new String(masterPassword));
    }

    /**
     * 当前库主密码哈希中的盐（hex 字符串，小写）。
     * 新格式（pbkdf2$...）返回盐 hex；未初始化或旧格式返回 null（无盐，备份导出时不写 header）
     */
    public String getMasterSaltHex() {
        String stored = db.getMasterHash();
        if (stored == null || !stored.startsWith(AesUtil.PBKDF2_PREFIX)) {
            return null;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return null;
        }
        return parts[2];
    }

    /** 当前库主密码哈希中的迭代次数；未初始化或旧格式返回默认值 600000 */
    public int getMasterIterations() {
        String stored = db.getMasterHash();
        if (stored == null || !stored.startsWith(AesUtil.PBKDF2_PREFIX)) {
            return AesUtil.PBKDF2_ITERATIONS;
        }
        return AesUtil.iterationsFromRecord(stored);
    }

    /**
     * 一次性迁移：旧 SHA-256 加密 -> PBKDF2 新密钥
     * 流程：用旧密钥逐条解密 -> 用新密钥重新加密写回 -> 更新存储为新格式
     * 先预检全部条目可解密，再执行写库，避免迁移中途失败导致数据不可用
     */
    public void upgradeToPbkdf2(char[] masterPassword) {
        String stored = db.getMasterHash();
        if (stored == null || !AesUtil.isLegacyRecord(stored)) {
            return; // 已是新格式，无需迁移
        }

        // 1. 派生旧密钥（SHA-256）与新密钥（PBKDF2 随机盐）
        SecretKey oldKey = AesUtil.deriveKey(new String(masterPassword));
        byte[] salt = AesUtil.generateSalt();
        SecretKey newKey = AesUtil.deriveKeyPbkdf2(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS);

        List<Entry> entries = db.getAllEntries();
        List<Entry> affected = new java.util.ArrayList<>();

        // 2. 预检：先解密验证所有密文，收集需重加密条目
        for (Entry e : entries) {
            String enc = e.getPasswordEnc();
            if (enc == null || enc.isEmpty()) {
                continue;
            }
            try {
                AesUtil.decrypt(enc, oldKey);
                affected.add(e);
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "迁移预检失败：条目【" + nullToEmpty(e.getPlatform()) + "】无法用当前主密码解密，已中止迁移（数据未改动）", ex);
            }
        }

        // 3. 执行重加密写回
        for (Entry e : affected) {
            String plain = AesUtil.decrypt(e.getPasswordEnc(), oldKey);
            try {
                e.setPasswordEnc(AesUtil.encrypt(plain, newKey));
                db.updateEntry(e);
            } finally {
                // plain 为 String 由 GC 回收，密文侧无明文残留
            }
        }

        // 4. 更新存储为新格式（最后一步，保证失败时旧数据仍可用旧密码打开）
        db.saveMasterHash(AesUtil.buildPbkdf2Record(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS));
    }

    // ---------- 条目 CRUD ----------

    /** 新增条目（内部自动加密密码字段） */
    public long addEntry(Entry entry, String plainPassword, SecretKey key) {
        entry.setPasswordEnc(encryptOrNull(plainPassword, key));
        return db.insertEntry(entry);
    }

    /** 更新条目（plainPassword 为空则保留原加密值） */
    public void updateEntry(Entry entry, String plainPassword, SecretKey key, boolean keepPassword) {
        if (!keepPassword) {
            entry.setPasswordEnc(encryptOrNull(plainPassword, key));
        }
        db.updateEntry(entry);
    }

    /** 解密条目密码 */
    public String decryptPassword(Entry entry, SecretKey key) {
        if (entry.getPasswordEnc() == null || entry.getPasswordEnc().isEmpty()) {
            return "";
        }
        try {
            return AesUtil.decrypt(entry.getPasswordEnc(), key);
        } catch (Exception e) {
            return "";
        }
    }

    /** 查询列表（解密密码字段，供导出/展示使用） */
    public List<Entry> listEntries() {
        return db.getAllEntries();
    }

    /** 搜索（加密字段不解密） */
    public List<Entry> search(String keyword, String category) {
        return db.searchEntries(keyword, category);
    }

    public void delete(long id) {
        db.deleteEntry(id);
    }

    public Entry getById(long id) {
        return db.getEntryById(id);
    }

    public void clearAll() {
        db.clearEntries();
    }

    public void insertAll(List<Entry> entries) {
        db.insertAll(entries);
    }

    private String encryptOrNull(String plain, SecretKey key) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        return AesUtil.encrypt(plain, key);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
