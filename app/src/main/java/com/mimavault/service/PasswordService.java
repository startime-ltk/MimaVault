package com.mimavault.service;

import com.mimavault.util.AesUtil;
import com.mimavault.db.DatabaseManager;
import com.mimavault.model.Entry;
import com.mimavault.model.PasswordHistoryItem;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

/**
 * 密码业务服务，与 PC 端 PasswordService 对齐
 * 负责主密码校验（PBKDF2 慢哈希）、密钥派生、条目 CRUD 编排
 */
public class PasswordService {

    public enum VerifyResult {
        MATCH,
        MATCH_NEED_UPGRADE,
        MISMATCH
    }

    /** 单条目保留的历史密码条数上限 */
    public static final int MAX_PASSWORD_HISTORY = 20;

    private final DatabaseManager db;

    public PasswordService(DatabaseManager db) {
        this.db = db;
    }

    public boolean isInitialized() {
        return db.isMasterSet();
    }

    public void setMasterPassword(char[] masterPassword) {
        byte[] salt = AesUtil.generateSalt();
        String record = AesUtil.buildPbkdf2Record(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS);
        db.saveMasterHash(record);
    }

    public VerifyResult verifyMasterPassword(char[] masterPassword) {
        String stored = db.getMasterHash();
        if (stored == null || stored.isEmpty()) {
            return VerifyResult.MISMATCH;
        }
        if (AesUtil.isLegacyRecord(stored)) {
            boolean ok = stored.equalsIgnoreCase(AesUtil.sha256Hex(new String(masterPassword)));
            return ok ? VerifyResult.MATCH_NEED_UPGRADE : VerifyResult.MISMATCH;
        }
        if (!AesUtil.verifyPassword(masterPassword, stored)) {
            return VerifyResult.MISMATCH;
        }
        // 登录耗时优化：老库(600k 等更高档位)验证通过后触发一次性迁移至当前档位(210k)
        int storedIter = AesUtil.iterationsFromRecord(stored);
        return storedIter > AesUtil.PBKDF2_ITERATIONS ? VerifyResult.MATCH_NEED_UPGRADE : VerifyResult.MATCH;
    }

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

    public int getMasterIterations() {
        String stored = db.getMasterHash();
        if (stored == null || !stored.startsWith(AesUtil.PBKDF2_PREFIX)) {
            return AesUtil.PBKDF2_ITERATIONS;
        }
        return AesUtil.iterationsFromRecord(stored);
    }

    /** 一次性迁移至当前 PBKDF2 档位（先预检再写库）。
     *  兼容两种来源：旧 SHA-256 库、旧高迭代(600k)PBKDF2 库。
     *  条目加密密钥随主密钥档位变化，须用旧钥解密后用新钥重加密，故降档与升档同路径处理。 */
    public void upgradeToPbkdf2(char[] masterPassword) {
        String stored = db.getMasterHash();
        if (stored == null) {
            return;
        }
        if (!AesUtil.isLegacyRecord(stored) && AesUtil.iterationsFromRecord(stored) <= AesUtil.PBKDF2_ITERATIONS) {
            return;
        }
        SecretKey oldKey = deriveKey(masterPassword);
        byte[] salt = AesUtil.generateSalt();
        SecretKey newKey = AesUtil.deriveKeyPbkdf2(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS);

        List<Entry> entries = db.getAllEntries();
        List<Entry> affected = new ArrayList<>();
        for (Entry e : entries) {
            String enc = e.getPasswordEnc();
            if (enc == null || enc.isEmpty()) {
                continue;
            }
            try {
                AesUtil.decrypt(enc, oldKey);
                affected.add(e);
            } catch (Exception ex) {
                throw new IllegalStateException("迁移预检失败：条目无法用当前主密码解密，已中止迁移（数据未改动）", ex);
            }
        }
        for (Entry e : affected) {
            String plain = AesUtil.decrypt(e.getPasswordEnc(), oldKey);
            e.setPasswordEnc(AesUtil.encrypt(plain, newKey));
            db.updateEntry(e);
        }
        db.saveMasterHash(AesUtil.buildPbkdf2Record(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS));
    }

    public long addEntry(Entry entry, String plainPassword, SecretKey key) {
        entry.setPasswordEnc(encryptOrNull(plainPassword, key));
        return db.insertEntry(entry);
    }

    /** 改主密码的结果：新的派生密钥 + 新的盐 / 迭代次数，供会话与手势/指纹绑定刷新 */
    public static final class MasterChangeResult {
        public final SecretKey newKey;
        public final String newSaltHex;
        public final int iterations;

        MasterChangeResult(SecretKey newKey, String newSaltHex, int iterations) {
            this.newKey = newKey;
            this.newSaltHex = newSaltHex;
            this.iterations = iterations;
        }
    }

    /**
     * 修改主密码：全量重加密（条目密码 + TOTP 密钥 + 历史密码）。
     *
     * 采用「换盐」的方式重新派生密钥并重写 master_hash，
     * 与改密前的备份文件格式完全一致（pbkdf2$迭代$盐$哈希），因此旧备份仍可用旧主密码导入。
     * 任何一条密文无法用当前主密码解密时立即中止，保证不会出现半途损坏的中间状态。
     *
     * @throws IllegalStateException 当前密码错误 / 新密码非法 / 预检失败
     */
    public MasterChangeResult changeMasterPassword(char[] currentPassword, char[] newPassword) {
        String stored = db.getMasterHash();
        if (stored == null || stored.isEmpty()) {
            throw new IllegalStateException("尚未初始化主密码");
        }
        if (!AesUtil.verifyPassword(currentPassword, stored)) {
            throw new IllegalStateException("当前主密码不正确");
        }
        if (newPassword == null || newPassword.length < 6) {
            throw new IllegalStateException("新主密码长度至少 6 位");
        }
        if (java.util.Arrays.equals(currentPassword, newPassword)) {
            throw new IllegalStateException("新主密码不能与当前主密码相同");
        }

        SecretKey oldKey = deriveKey(currentPassword);
        byte[] salt = AesUtil.generateSalt();
        SecretKey newKey = AesUtil.deriveKeyPbkdf2(newPassword, salt, AesUtil.PBKDF2_ITERATIONS);

        // 第一步：预检（只解密不写入），确保全库密文都能用当前主密码解开
        List<Entry> affected = new ArrayList<>();
        for (Entry e : db.getAllEntriesIncludingTrashed()) {
            boolean need = false;
            if (e.getPasswordEnc() != null && !e.getPasswordEnc().isEmpty()) {
                decryptOrThrow(e.getPasswordEnc(), oldKey);
                need = true;
            }
            if (e.getTotpSecretEnc() != null && !e.getTotpSecretEnc().isEmpty()) {
                decryptOrThrow(e.getTotpSecretEnc(), oldKey);
                need = true;
            }
            if (need) {
                affected.add(e);
            }
        }
        List<PasswordHistoryItem> historyAffected = new ArrayList<>();
        for (PasswordHistoryItem it : db.listAllPasswordHistory()) {
            if (it.getPasswordEnc() == null || it.getPasswordEnc().isEmpty()) {
                continue;
            }
            decryptOrThrow(it.getPasswordEnc(), oldKey);
            historyAffected.add(it);
        }

        // 第二步：全量重加密写库
        for (Entry e : affected) {
            if (e.getPasswordEnc() != null && !e.getPasswordEnc().isEmpty()) {
                e.setPasswordEnc(AesUtil.encrypt(AesUtil.decrypt(e.getPasswordEnc(), oldKey), newKey));
            }
            if (e.getTotpSecretEnc() != null && !e.getTotpSecretEnc().isEmpty()) {
                e.setTotpSecretEnc(AesUtil.encrypt(AesUtil.decrypt(e.getTotpSecretEnc(), oldKey), newKey));
            }
            db.updateEntry(e);
        }
        for (PasswordHistoryItem it : historyAffected) {
            db.updatePasswordHistoryEnc(it.getId(),
                    AesUtil.encrypt(AesUtil.decrypt(it.getPasswordEnc(), oldKey), newKey));
        }

        // 第三步：最后落盘新的主密码记录
        db.saveMasterHash(AesUtil.buildPbkdf2Record(newPassword, salt, AesUtil.PBKDF2_ITERATIONS));
        return new MasterChangeResult(newKey, bytesToHex(salt), AesUtil.PBKDF2_ITERATIONS);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void decryptOrThrow(String cipher, SecretKey key) {
        try {
            AesUtil.decrypt(cipher, key);
        } catch (Exception e) {
            throw new IllegalStateException("数据无法用当前主密码解密，已中止修改（数据未改动）", e);
        }
    }

    /**
     * 更新条目。keepPassword 为 false 时写入新密码，并把改密前的旧密码留档到历史表，
     * 供后续回溯查看与一键恢复（历史同样为密文存储，明文不落盘）。
     */
    public void updateEntry(Entry entry, String plainPassword, SecretKey key, boolean keepPassword) {
        if (!keepPassword) {
            String oldEnc = entry.getPasswordEnc();
            String newEnc = encryptOrNull(plainPassword, key);
            if (oldEnc != null && !oldEnc.isEmpty() && !oldEnc.equals(newEnc)) {
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

    public List<Entry> listEntries() {
        return db.getAllEntries();
    }

    public List<Entry> search(String keyword, String category) {
        return db.searchEntries(keyword, category);
    }

    public void delete(long id) {
        // 移入回收站（软删除），与 PC 端行为一致
        db.trashEntry(id);
    }

    public List<Entry> listTrashed() {
        return db.listTrashedEntries();
    }

    public void restore(long id) {
        db.restoreEntry(id);
    }

    public void purge(long id) {
        db.purgeEntry(id);
    }

    public void purgeAllTrashed() {
        db.purgeAllTrashed();
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
}
