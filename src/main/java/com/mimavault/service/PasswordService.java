package com.mimavault.service;

import com.mimavault.db.DatabaseManager;
import com.mimavault.model.Entry;
import com.mimavault.model.PasswordHistoryItem;
import com.mimavault.util.AesUtil;

import javax.crypto.SecretKey;
import java.util.ArrayList;
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

    /** 单条目密码历史保留上限（与安卓端 A9.9.16 保持一致） */
    public static final int MAX_PASSWORD_HISTORY = 20;

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

        // 3.5 TOTP 密文：与密码同钥加密，不迁移则升级后动态验证码无法生成（先全量预检再写回）
        List<Entry> totpAffected = new ArrayList<>();
        for (Entry e : entries) {
            String totpEnc = e.getTotpSecretEnc();
            if (totpEnc == null || totpEnc.isEmpty()) {
                continue;
            }
            try {
                AesUtil.decrypt(totpEnc, oldKey);
                totpAffected.add(e);
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "迁移预检失败：条目【" + nullToEmpty(e.getPlatform()) + "】的动态验证码密钥无法用当前主密码解密，已中止迁移（数据未改动）", ex);
            }
        }
        for (Entry e : totpAffected) {
            String plain = AesUtil.decrypt(e.getTotpSecretEnc(), oldKey);
            e.setTotpSecretEnc(AesUtil.encrypt(plain, newKey));
            db.updateEntry(e);
        }

        // 4. 更新存储为新格式（最后一步，保证失败时旧数据仍可用旧密码打开）
        db.saveMasterHash(AesUtil.buildPbkdf2Record(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS));
    }

    /**
     * 修改主密码：校验旧密码后，用旧密钥解密全部条目，再用新主密码派生密钥重加密写回，
     * 最后更新主密码哈希（pbkdf2 新格式）。先预检全部条目可解密再执行写库，
     * 避免中途失败导致数据不可用；哈希更新放在最后一步，失败时旧数据仍可用旧密码打开。
     *
     * @param oldPassword 当前主密码（校验失败抛 IllegalArgumentException）
     * @param newPassword 新主密码
     * @return 新主密码派生的 AES 密钥，调用方需用它替换内存中持有的旧密钥
     */
    public SecretKey changeMasterPassword(char[] oldPassword, char[] newPassword) {
        // 1. 校验当前主密码
        VerifyResult result = verifyMasterPassword(oldPassword);
        if (result == VerifyResult.MISMATCH) {
            throw new IllegalArgumentException("当前主密码错误");
        }
        // 旧格式（SHA-256）防御性迁移：正常登录后已是新格式，此处仅兜底
        if (result == VerifyResult.MATCH_NEED_UPGRADE) {
            upgradeToPbkdf2(oldPassword);
        }

        // 2. 派生旧密钥与新密钥（新随机盐 + 标准迭代次数）
        SecretKey oldKey = deriveKey(oldPassword);
        byte[] salt = AesUtil.generateSalt();
        SecretKey newKey = AesUtil.deriveKeyPbkdf2(newPassword, salt, AesUtil.PBKDF2_ITERATIONS);

        // 3. 预检：先解密验证所有密文，收集需重加密条目
        List<Entry> entries = db.getAllEntries();
        List<Entry> affected = new java.util.ArrayList<>();
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
                        "修改主密码预检失败：条目【" + nullToEmpty(e.getPlatform()) + "】无法用当前主密码解密，已中止（数据未改动）", ex);
            }
        }

        // 4. 执行重加密写回
        for (Entry e : affected) {
            String plain = AesUtil.decrypt(e.getPasswordEnc(), oldKey);
            e.setPasswordEnc(AesUtil.encrypt(plain, newKey));
            db.updateEntry(e);
        }

        // 4.5 TOTP 密钥密文同步重加密（与密码同钥，不改则改主密码后动态验证码无法生成）
        List<Entry> totpAffected = new ArrayList<>();
        for (Entry e : entries) {
            String totpEnc = e.getTotpSecretEnc();
            if (totpEnc == null || totpEnc.isEmpty()) {
                continue;
            }
            try {
                AesUtil.decrypt(totpEnc, oldKey);
                totpAffected.add(e);
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "修改主密码预检失败：条目【" + nullToEmpty(e.getPlatform()) + "】的动态验证码密钥无法解密，已中止（数据未改动）", ex);
            }
        }
        for (Entry e : totpAffected) {
            String plain = AesUtil.decrypt(e.getTotpSecretEnc(), oldKey);
            e.setTotpSecretEnc(AesUtil.encrypt(plain, newKey));
            db.updateEntry(e);
        }

        // 4.6 历史密码密文同步重加密（历史与条目同源同钥，不改则改主密码后历史将无法解密）
        for (PasswordHistoryItem h : db.listAllPasswordHistory()) {
            String enc = h.getPasswordEnc();
            if (enc == null || enc.isEmpty()) {
                continue;
            }
            try {
                String plain = AesUtil.decrypt(enc, oldKey);
                db.updatePasswordHistoryEnc(h.getId(), AesUtil.encrypt(plain, newKey));
            } catch (Exception ignore) {
                // 更早版本遗留的、无法用当前密钥解密的历史记录：跳过，不阻塞主流程
            }
        }

        // 5. 更新主密码哈希（最后一步，失败时旧数据仍可用旧密码打开）
        db.saveMasterHash(AesUtil.buildPbkdf2Record(newPassword, salt, AesUtil.PBKDF2_ITERATIONS));
        return newKey;
    }

    // ---------- 条目 CRUD ----------

    /** 新增条目（内部自动加密密码字段） */
    public long addEntry(Entry entry, String plainPassword, SecretKey key) {
        entry.setPasswordEnc(encryptOrNull(plainPassword, key));
        return db.insertEntry(entry);
    }

    /**
     * 更新条目。keepPassword 为 false 时写入新密码，并把改密前的旧密码留档到历史表，
     * 供后续回溯查看与一键恢复（历史同样为密文存储，明文不落盘）。
     */
    public void updateEntry(Entry entry, String plainPassword, SecretKey key, boolean keepPassword) {
        if (!keepPassword) {
            String oldEnc = entry.getPasswordEnc();
            String newEnc = encryptOrNull(plainPassword, key);
            if (entry.getId() > 0 && oldEnc != null && !oldEnc.isEmpty() && !oldEnc.equals(newEnc)) {
                db.insertPasswordHistory(entry.getId(), oldEnc);
                db.trimPasswordHistory(entry.getId(), MAX_PASSWORD_HISTORY);
            }
            entry.setPasswordEnc(newEnc);
        }
        db.updateEntry(entry);
    }

    // ---------- 密码历史版本 ----------

    /** 某条目的历史密码列表（最新在前） */
    public List<PasswordHistoryItem> listPasswordHistory(long entryId) {
        return db.listPasswordHistory(entryId);
    }

    /** 某条目的历史密码条数 */
    public int countPasswordHistory(long entryId) {
        return db.countPasswordHistory(entryId);
    }

    /** 解密单条历史记录；解密失败返回空串（展示层按“无法解密”处理） */
    public String decryptHistoryPassword(PasswordHistoryItem item, SecretKey key) {
        if (item == null || item.getPasswordEnc() == null || item.getPasswordEnc().isEmpty()) {
            return "";
        }
        try {
            return AesUtil.decrypt(item.getPasswordEnc(), key);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把指定历史版本恢复为当前密码：旧的当前密码反向留档，历史记录与之一一互换，
     * 避免列表重复堆积。恢复后条目密码直接落库（密文写入，无明文落盘）。
     */
    public void restorePasswordFromHistory(Entry entry, PasswordHistoryItem item, SecretKey key) {
        if (entry == null || item == null) {
            return;
        }
        String target = item.getPasswordEnc();
        if (target == null || target.isEmpty()) {
            return;
        }
        String current = entry.getPasswordEnc();
        db.deletePasswordHistoryById(item.getId());
        if (current != null && !current.isEmpty() && !current.equals(target)) {
            db.insertPasswordHistory(entry.getId(), current);
        }
        entry.setPasswordEnc(target);
        db.updateEntry(entry);
        db.trimPasswordHistory(entry.getId(), MAX_PASSWORD_HISTORY);
    }

    /** 清空某条目的历史密码 */
    public void clearPasswordHistory(long entryId) {
        db.deletePasswordHistory(entryId);
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

    /** 解密条目动态验证码（otpauth 链接）；未绑定或解密失败返回 null */
    public String decryptTotp(Entry entry, SecretKey key) {
        if (entry == null || entry.getTotpSecretEnc() == null || entry.getTotpSecretEnc().isEmpty() || key == null) {
            return null;
        }
        try {
            return AesUtil.decrypt(entry.getTotpSecretEnc(), key);
        } catch (Exception e) {
            return null;
        }
    }

    /** 加密动态验证码链接；空值返回 null */
    public static String encryptTotpOrNull(String totpUri, SecretKey key) {
        if (totpUri == null || totpUri.isEmpty() || key == null) {
            return null;
        }
        return AesUtil.encrypt(totpUri, key);
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

    // ---------- 回收站 ----------

    /** 移入回收站（软删除，可恢复） */
    public void trash(long id) {
        db.trashEntry(id);
    }

    /** 从回收站恢复 */
    public void restore(long id) {
        db.restoreEntry(id);
    }

    /** 彻底删除回收站中的单条条目 */
    public void purge(long id) {
        db.purgeEntry(id);
    }

    /** 清空回收站 */
    public void purgeAllTrashed() {
        db.purgeAllTrashed();
    }

    /** 回收站全部条目 */
    public List<Entry> listTrashed() {
        return db.listTrashedEntries();
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
