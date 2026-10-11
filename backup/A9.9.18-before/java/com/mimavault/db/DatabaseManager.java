package com.mimavault.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.mimavault.config.AppConfig;
import com.mimavault.model.Entry;
import com.mimavault.model.PasswordHistoryItem;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * SQLite 数据库管理，与 PC 端 DatabaseManager 逐字段对齐
 * 表结构与 PC 端密匣 MimaVault 数据库完全一致
 */
public class DatabaseManager {

    private SQLiteDatabase db;

    private static final String SQL_SETTINGS =
            "CREATE TABLE IF NOT EXISTS settings (" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                    "master_hash TEXT NOT NULL," +
                    "created_at INTEGER)";

    private static final String SQL_ENTRIES =
            "CREATE TABLE IF NOT EXISTS entries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "category TEXT DEFAULT '网站'," +
                    "platform TEXT," +
                    "account TEXT," +
                    "password_enc TEXT," +
                    "phone TEXT," +
                    "email TEXT," +
                    "note TEXT," +
                    "image_path TEXT," +
                    "gesture_seq TEXT," +
                    "sync_status TEXT DEFAULT 'local'," +
                    "created_at INTEGER," +
                    "updated_at INTEGER," +
                    "deleted_at INTEGER)";

    /** 密码历史版本表：改密前的旧密码（加密存储），与 entries 解耦，旧库升级仅新增该表 */
    private static final String SQL_PASSWORD_HISTORY =
            "CREATE TABLE IF NOT EXISTS password_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "entry_id INTEGER NOT NULL," +
                    "password_enc TEXT," +
                    "changed_at INTEGER)";

    /** 初始化：建表 + 兼容补列 */
    public void init() {
        db = SQLiteDatabase.openOrCreateDatabase(AppConfig.dbFile(), null);
        db.execSQL(SQL_SETTINGS);
        db.execSQL(SQL_ENTRIES);
        db.execSQL(SQL_PASSWORD_HISTORY);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_platform ON entries(platform)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_category ON entries(category)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pwd_history_entry ON password_history(entry_id)");
        ensureColumn("entries", "category", "TEXT DEFAULT '网站'");
        ensureColumn("entries", "sync_status", "TEXT DEFAULT 'local'");
        ensureColumn("entries", "deleted_at", "INTEGER");
    }

    private void ensureColumn(String table, String column, String definition) {
        boolean found = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                if (column.equalsIgnoreCase(c.getString(c.getColumnIndexOrThrow("name")))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    public boolean isMasterSet() {
        try (Cursor c = db.rawQuery("SELECT master_hash FROM settings WHERE id = 1", null)) {
            return c.moveToFirst();
        }
    }

    public void saveMasterHash(String hash) {
        ContentValues cv = new ContentValues();
        cv.put("id", 1);
        cv.put("master_hash", hash);
        cv.put("created_at", System.currentTimeMillis());
        db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getMasterHash() {
        try (Cursor c = db.rawQuery("SELECT master_hash FROM settings WHERE id = 1", null)) {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        }
        return null;
    }

    public long insertEntry(Entry e) {
        ContentValues cv = toValues(e);
        long now = System.currentTimeMillis();
        cv.put("created_at", now);
        cv.put("updated_at", now);
        return db.insert("entries", null, cv);
    }

    public void updateEntry(Entry e) {
        ContentValues cv = toValues(e);
        cv.put("updated_at", System.currentTimeMillis());
        db.update("entries", cv, "id=?", new String[]{String.valueOf(e.getId())});
    }

    public void deleteEntry(long id) {
        db.delete("entries", "id=?", new String[]{String.valueOf(id)});
    }

    // ---------- 回收站（软删除） ----------

    /** 移入回收站：仅置 deleted_at，不物理删除 */
    public void trashEntry(long id) {
        ContentValues cv = new ContentValues();
        long now = System.currentTimeMillis();
        cv.put("deleted_at", now);
        cv.put("updated_at", now);
        db.update("entries", cv, "id=? AND deleted_at IS NULL", new String[]{String.valueOf(id)});
    }

    /** 从回收站恢复：清空 deleted_at */
    public void restoreEntry(long id) {
        ContentValues cv = new ContentValues();
        cv.putNull("deleted_at");
        cv.put("updated_at", System.currentTimeMillis());
        db.update("entries", cv, "id=? AND deleted_at IS NOT NULL", new String[]{String.valueOf(id)});
    }

    /** 彻底删除回收站中的单条条目（连带清理其密码历史） */
    public void purgeEntry(long id) {
        db.delete("entries", "id=? AND deleted_at IS NOT NULL", new String[]{String.valueOf(id)});
        deletePasswordHistory(id);
    }

    /** 清空回收站：物理删除所有已标记删除的条目（连带清理其密码历史） */
    public void purgeAllTrashed() {
        db.delete("password_history",
                "entry_id IN (SELECT id FROM entries WHERE deleted_at IS NOT NULL)", null);
        db.delete("entries", "deleted_at IS NOT NULL", null);
    }

    /** 查询回收站全部条目（按删除时间倒序） */
    public List<Entry> listTrashedEntries() {
        return query("SELECT * FROM entries WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC", null);
    }

    public List<Entry> getAllEntries() {
        return query("SELECT * FROM entries WHERE deleted_at IS NULL ORDER BY updated_at DESC", null);
    }

    public List<Entry> searchEntries(String keyword, String category) {
        StringBuilder sql = new StringBuilder("SELECT * FROM entries WHERE deleted_at IS NULL");
        List<String> args = new ArrayList<>();
        if (category != null && !category.trim().isEmpty() && !"全部".equals(category.trim())) {
            sql.append(" AND category=?");
            args.add(category.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (platform LIKE ? OR account LIKE ? OR phone LIKE ? OR email LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY updated_at DESC");
        return query(sql.toString(), args.toArray(new String[0]));
    }

    public Entry getEntryById(long id) {
        List<Entry> list = query("SELECT * FROM entries WHERE id=?", new String[]{String.valueOf(id)});
        return list.isEmpty() ? null : list.get(0);
    }

    public void clearEntries() {
        db.delete("password_history", null, null);
        db.delete("entries", null, null);
    }

    /** 批量插入（导入恢复使用，事务） */
    public void insertAll(List<Entry> entries) {
        db.beginTransaction();
        try {
            for (Entry e : entries) {
                ContentValues cv = toValues(e);
                if (e.getCreatedAt() != null) {
                    cv.put("created_at", e.getCreatedAt().getTime());
                } else {
                    cv.put("created_at", System.currentTimeMillis());
                }
                cv.put("updated_at", e.getUpdatedAt() == null ? System.currentTimeMillis() : e.getUpdatedAt().getTime());
                db.insert("entries", null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---------- 密码历史版本 ----------

    /** 写入一条历史密码（加密串） */
    public long insertPasswordHistory(long entryId, String passwordEnc) {
        ContentValues cv = new ContentValues();
        cv.put("entry_id", entryId);
        cv.put("password_enc", passwordEnc);
        cv.put("changed_at", System.currentTimeMillis());
        return db.insert("password_history", null, cv);
    }

    /** 查询某条目的历史密码（按时间倒序，最新在前） */
    public List<PasswordHistoryItem> listPasswordHistory(long entryId) {
        List<PasswordHistoryItem> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT id, entry_id, password_enc, changed_at FROM password_history "
                        + "WHERE entry_id=? ORDER BY changed_at DESC, id DESC",
                new String[]{String.valueOf(entryId)})) {
            while (c.moveToNext()) {
                PasswordHistoryItem it = new PasswordHistoryItem();
                it.setId(c.getLong(c.getColumnIndexOrThrow("id")));
                it.setEntryId(c.getLong(c.getColumnIndexOrThrow("entry_id")));
                it.setPasswordEnc(c.getString(c.getColumnIndexOrThrow("password_enc")));
                long t = c.getLong(c.getColumnIndexOrThrow("changed_at"));
                if (t > 0) {
                    it.setChangedAt(new Date(t));
                }
                list.add(it);
            }
        }
        return list;
    }

    /** 历史条数 */
    public int countPasswordHistory(long entryId) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM password_history WHERE entry_id=?",
                new String[]{String.valueOf(entryId)})) {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        }
        return 0;
    }

    /** 超出保留上限时裁剪最旧的历史记录 */
    public void trimPasswordHistory(long entryId, int keep) {
        db.execSQL("DELETE FROM password_history WHERE entry_id=? AND id NOT IN "
                        + "(SELECT id FROM password_history WHERE entry_id=? "
                        + "ORDER BY changed_at DESC, id DESC LIMIT ?)",
                new Object[]{entryId, entryId, Math.max(0, keep)});
    }

    /** 删除某条目的全部历史 */
    public void deletePasswordHistory(long entryId) {
        db.delete("password_history", "entry_id=?", new String[]{String.valueOf(entryId)});
    }

    /** 删除单条历史记录 */
    public void deletePasswordHistoryById(long historyId) {
        db.delete("password_history", "id=?", new String[]{String.valueOf(historyId)});
    }

    private ContentValues toValues(Entry e) {
        ContentValues cv = new ContentValues();
        cv.put("category", e.getCategory() == null ? Entry.CATEGORY_WEBSITE : e.getCategory());
        cv.put("platform", e.getPlatform());
        cv.put("account", e.getAccount());
        cv.put("password_enc", e.getPasswordEnc());
        cv.put("phone", e.getPhone());
        cv.put("email", e.getEmail());
        cv.put("note", e.getNote());
        cv.put("image_path", e.getImagePath());
        cv.put("gesture_seq", e.getGestureSeq());
        cv.put("sync_status", e.getSyncStatus() == null ? "local" : e.getSyncStatus());
        if (e.getDeletedAt() != null) {
            cv.put("deleted_at", e.getDeletedAt().getTime());
        } else {
            cv.putNull("deleted_at");
        }
        return cv;
    }

    private List<Entry> query(String sql, String[] args) {
        List<Entry> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.setId(c.getLong(c.getColumnIndexOrThrow("id")));
                e.setCategory(c.getString(c.getColumnIndexOrThrow("category")));
                e.setPlatform(c.getString(c.getColumnIndexOrThrow("platform")));
                e.setAccount(c.getString(c.getColumnIndexOrThrow("account")));
                e.setPasswordEnc(c.getString(c.getColumnIndexOrThrow("password_enc")));
                e.setPhone(c.getString(c.getColumnIndexOrThrow("phone")));
                e.setEmail(c.getString(c.getColumnIndexOrThrow("email")));
                e.setNote(c.getString(c.getColumnIndexOrThrow("note")));
                e.setImagePath(c.getString(c.getColumnIndexOrThrow("image_path")));
                e.setGestureSeq(c.getString(c.getColumnIndexOrThrow("gesture_seq")));
                e.setSyncStatus(c.getString(c.getColumnIndexOrThrow("sync_status")));
                long created = c.getLong(c.getColumnIndexOrThrow("created_at"));
                long updated = c.getLong(c.getColumnIndexOrThrow("updated_at"));
                if (created > 0) e.setCreatedAt(new Date(created));
                if (updated > 0) e.setUpdatedAt(new Date(updated));
                long deleted = c.getLong(c.getColumnIndexOrThrow("deleted_at"));
                if (deleted > 0) e.setDeletedAt(new Date(deleted));
                list.add(e);
            }
        }
        return list;
    }
}
