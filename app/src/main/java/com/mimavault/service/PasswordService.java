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
        // 双端统一档位（210k）：历史库（600k 等高/低档）验证通过后触发一次性迁移重加密，
        // 使 PC 端与安卓端密库档位完全一致、可互相打开。
        int storedIter = AesUtil.iterationsFromRecord(stored);
        return storedIter != AesUtil.PBKDF2_ITERATIONS ? VerifyResult.MATCH_NEED_UPGRADE : VerifyResult.MATCH;
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

    /** 一次性迁移至双端统一档位（先预检再写库）。
     *  兼容两种来源：旧 SHA-256 库、非统一档位（如 600k）的 PBKDF2 库。
     *  条目加密密钥随主密钥档位变化，须用旧钥解密后用新钥重加密，故升档与降档同路径处理。 */
    public void upgradeToPbkdf2(char[] masterPassword) {
        String stored = db.getMasterHash();
        if (stored == null || stored.isEmpty()) {
            return;
        }
        if (!AesUtil.isLegacyRecord(stored)
                && AesUtil.iterationsFromRecord(stored) == AesUtil.PBKDF2_ITERATIONS) {
            return; // 已是统一档位，无需迁移
        }
        SecretKey oldKey = deriveKey(masterPassword);
        byte[] salt = AesUtil.generateSalt();
        SecretKey newKey = AesUtil.deriveKeyPbkdf2(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS);

        reencryptAll(oldKey, newKey);

        db.saveMasterHash(AesUtil.buildPbkdf2Record(masterPassword, salt, AesUtil.PBKDF2_ITERATIONS));
    }

    /**
     * 全量重加密：条目密码 + TOTP 密钥 + 历史密码（含回收站条目，密文均与主密钥同钥）。
     * 活跃条目严格「预检不通过即中止」，避免出现半途损坏的库；
     * 回收站条目与历史记录中确有无法解密的旧残留时跳过，不阻塞主流程。
     */
    private void reencryptAll(SecretKey oldKey, SecretKey newKey) {
        List<Entry> all = db.getAllEntriesIncludingTrashed();

        // 第一步：预检（活跃条目必须全部可解密）
        for (Entry e : all) {
            if (e.getDeletedAt() != null) {
                continue; // 回收站条目宽松处理
            }
            if (e.getPasswordEnc() != null && !e.getPasswordEnc().isEmpty()) {
                decryptOrThrow(e.getPasswordEnc(), oldKey);
            }
            if (e.getTotpSecretEnc() != null && !e.getTotpSecretEnc().isEmpty()) {
                decryptOrThrow(e.getTotpSecretEnc(), oldKey);
            }
        }

        // 第二步：全量重加密写库（同一条目一次性写完密码与 TOTP）
        for (Entry e : all) {
            boolean changed = false;
            if (e.getPasswordEnc() != null && !e.getPasswordEnc().isEmpty()) {
                String re = tryReencrypt(e.getPasswordEnc(), oldKey, newKey);
                if (re != null) {
                    e.setPasswordEnc(re);
                    changed = true;
                }
            }
            if (e.getTotpSecretEnc() != null && !e.getTotpSecretEnc().isEmpty()) {
                String re = tryReencrypt(e.getTotpSecretEnc(), oldKey, newKey);
                if (re != null) {
                    e.setTotpSecretEnc(re);
                    changed = true;
                }
            }
            if (changed) {
                db.updateEntry(e);
            }
        }

        // 第三步：历史密码密文（早期残留无法解密时跳过）
        for (PasswordHistoryItem it : db.listAllPasswordHistory()) {
            String enc = it.getPasswordEnc();
            if (enc == null || enc.isEmpty()) {
                continue;
            }
            String re = tryReencrypt(enc, oldKey, newKey);
            if (re != null) {
                db.updatePasswordHistoryEnc(it.getId(), re);
            }
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
     * 活跃条目中任何一条密文无法用当前主密码解密时立即中止，保证不会出现半途损坏的中间状态。
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

        // 第一步 + 第二步：预检并全量重加密（条目密码 + TOTP 密钥 + 历史密码，含回收站条目）
        reencryptAll(oldKey, newKey);

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
