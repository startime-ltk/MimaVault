package com.passwordmaster.service;

import com.passwordmaster.db.DatabaseManager;
import com.passwordmaster.model.Entry;
import com.passwordmaster.util.AesUtil;

import javax.crypto.SecretKey;
import java.util.List;

/**
 * 密码业务服务
 * 负责主密码校验、条目加解密与 CRUD 编排
 */
public class PasswordService {

    private final DatabaseManager db;

    public PasswordService(DatabaseManager db) {
        this.db = db;
    }

    /** 是否已初始化主密码 */
    public boolean isInitialized() {
        return db.isMasterSet();
    }

    /** 设置（重置）主密码 */
    public void setMasterPassword(String masterPassword) {
        db.saveMasterHash(AesUtil.sha256Hex(masterPassword));
    }

    /** 校验主密码是否正确 */
    public boolean verifyMasterPassword(String masterPassword) {
        String stored = db.getMasterHash();
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        return stored.equalsIgnoreCase(AesUtil.sha256Hex(masterPassword));
    }

    /** 派生密钥（供加解密使用） */
    public SecretKey deriveKey(String masterPassword) {
        return AesUtil.deriveKey(masterPassword);
    }

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
}
