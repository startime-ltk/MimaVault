package com.mimavault.service;

import com.mimavault.util.AesUtil;
import com.mimavault.db.DatabaseManager;
import com.mimavault.model.Entry;

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
        return AesUtil.verifyPassword(masterPassword, stored) ? VerifyResult.MATCH : VerifyResult.MISMATCH;
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

    /** 一次性迁移：旧 SHA-256 -> PBKDF2（先预检再写库） */
    public void upgradeToPbkdf2(char[] masterPassword) {
        String stored = db.getMasterHash();
        if (stored == null || !AesUtil.isLegacyRecord(stored)) {
            return;
        }
        SecretKey oldKey = AesUtil.deriveKey(new String(masterPassword));
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

    public void updateEntry(Entry entry, String plainPassword, SecretKey key, boolean keepPassword) {
        if (!keepPassword) {
            entry.setPasswordEnc(encryptOrNull(plainPassword, key));
        }
        db.updateEntry(entry);
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
