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
        /** 校验通过，但密库档位落后（旧 SHA-256 格式 / 非统一迭代次数），需要一次性迁移重加密 */
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
        if (!AesUtil.verifyPassword(masterPassword, stored)) {
            return VerifyResult.MISMATCH;
        }
        // 双端统一档位：历史库（600000 等非当前统一档）验证通过后触发一次性迁移重加密
        return AesUtil.iterationsFromRecord(stored) == AesUtil.PBKDF2_ITERATIONS
                ? VerifyResult.MATCH : VerifyResult.MATCH_NEED_UPGRADE;
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

    /** 当前库主密码哈希中的迭代次数；未初始化或旧格式返回当前统一档位（210000） */
    public int getMasterIterations() {
        String stored = db.getMasterHash();
        if (stored == null || !stored.startsWith(AesUtil.PBKDF2_PREFIX)) {
            return AesUtil.PBKDF2_ITERATIONS;
        }
        return AesUtil.iterationsFromRecord(stored);
    }

    /**
     * 一次性迁移到双端统一档位（PBKDF2 210000 档）。
     * 兼容两种来源：① 旧 SHA-256 库；② 旧迭代档位（如 600000）的 PBKDF2 库。
     * 档位变化会改变派生密钥，两种来源同路径处理：旧钥解密 → 新钥重加密 → 最后更新 master_hash。
     * 先预检全部密文可解密再写库，保证迁移中途失败时旧数据仍可用旧主密码打开。
     */
    public void upgradeToPbkdf2(char[] masterPassword) {
        String stored = db.getMasterHash();
        if (stored == null || stored.isEmpty()) {
            return;
        }
        if (!AesUtil.isLegacyRecord(stored)
                && AesUtil.iterationsFromRecord(stored) == AesUtil.PBKDF2_ITERATIONS) {
            return; // 已是统一档位，无需迁移
        }

        // 1. 派生旧密钥（旧 SHA-256 / 旧档位 PBKDF2）与新密钥（新随机盐 + 统一档位）
        SecretKey oldKey = deriveKey(masterPassword);
        byte[] salt = AesUtil.generateSalt();
        SecretKey newKey = AesUtil.deriveKeyPbkdf2(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS);

        // 2. 全量重加密（条目密码 + 动态验证码 + 历史密码，含回收站条目）
        reencryptAll(oldKey, newKey);

        // 3. 更新存储为统一档位（最后一步，保证失败时旧数据仍可用旧密码打开）
        db.saveMasterHash(AesUtil.buildPbkdf2Record(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS));
    }

    /**
     * 全量重加密：用新密钥重写条目密码、动态验证码与历史密码密文（含回收站条目，这些密文与主密钥同钥）。
     * 活跃条目严格「预检不通过即中止」，避免产生半途损坏的库；
     * 回收站条目与历史记录中确有无法解密的旧残留时跳过，不阻塞主流程。
     */
    private void reencryptAll(SecretKey oldKey, SecretKey newKey) {
        List<Entry> active = db.getAllEntries();
        java.util.Set<Long> activeIds = new java.util.HashSet<>();
        for (Entry e : active) {
            activeIds.add(e.getId());
        }

        // 全量集合（含回收站）
        List<Entry> all = db.getAllEntriesIncludingTrashed();

        // 1. 预检：活跃条目解不开立即中止；回收站条目解不开仅跳过
        for (Entry e : all) {
            boolean strict = activeIds.contains(e.getId());
            String enc = e.getPasswordEnc();
            if (enc != null && !enc.isEmpty() && !canDecrypt(enc, oldKey) && strict) {
                throw new IllegalStateException(
                        "迁移预检失败：条目【" + nullToEmpty(e.getPlatform()) + "】无法用当前主密码解密，已中止迁移（数据未改动）");
            }
            String totpEnc = e.getTotpSecretEnc();
            if (totpEnc != null && !totpEnc.isEmpty() && !canDecrypt(totpEnc, oldKey) && strict) {
                throw new IllegalStateException(
                        "迁移预检失败：条目【" + nullToEmpty(e.getPlatform()) + "】的动态验证码密钥无法用当前主密码解密，已中止迁移（数据未改动）");
            }
        }

        // 2. 写回：同一条目一次性写完密码与动态验证码
        for (Entry e : all) {
            boolean changed = false;
            String enc = e.getPasswordEnc();
            if (enc != null && !enc.isEmpty()) {
                String re = tryReencrypt(enc, oldKey, newKey);
                if (re != null) {
                    e.setPasswordEnc(re);
                    changed = true;
                }
            }
            String totpEnc = e.getTotpSecretEnc();
            if (totpEnc != null && !totpEnc.isEmpty()) {
                String re = tryReencrypt(totpEnc, oldKey, newKey);
                if (re != null) {
                    e.setTotpSecretEnc(re);
                    changed = true;
                }
            }
            if (changed) {
                db.updateEntry(e);
            }
        }

        // 3. 历史密码密文（与条目同源同钥；早期遗留无法解密的记录跳过）
        for (PasswordHistoryItem h : db.listAllPasswordHistory()) {
            String enc = h.getPasswordEnc();
            if (enc == null || enc.isEmpty()) {
                continue;
            }
            String re = tryReencrypt(enc, oldKey, newKey);
            if (re != null) {
                db.updatePasswordHistoryEnc(h.getId(), re);
            }
        }
    }

    /** 密文能否用指定密钥解开 */
    private static boolean canDecrypt(String cipher, SecretKey key) {
        try {
            AesUtil.decrypt(cipher, key);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 旧钥解密 → 新钥重加密；解不开返回 null（是否中止由调用方决定） */
    private static String tryReencrypt(String cipher, SecretKey oldKey, SecretKey newKey) {
        try {
            return AesUtil.encrypt(AesUtil.decrypt(cipher, oldKey), newKey);
        } catch (Exception e) {
            return null;
        }
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

        // 3~4. 全量重加密（条目密码 + 动态验证码 + 历史密码，含回收站条目；预检不过即中止）
        reencryptAll(oldKey, newKey);

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
