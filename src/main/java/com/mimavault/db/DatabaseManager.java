package com.mimavault.db;

import com.mimavault.config.AppConfig;
import com.mimavault.model.Entry;
import com.mimavault.model.PasswordHistoryItem;

import java.sql.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * SQLite 数据库管理
 * 负责建表、兼容升级与条目 CRUD
 */
public class DatabaseManager {

    private final String dbUrl;

    public DatabaseManager() {
        this.dbUrl = "jdbc:sqlite:" + AppConfig.DB_FILE.toString().replace('\\', '/');
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    /**
     * 初始化数据库：创建目录、建表、兼容旧库补列
     */
    public void init() {
        try {
            // 品牌统一迁移：检测旧库 data/PasswordMaster.db，自动迁移为 data/MimaVault.db（重命名，不删数据）
            try {
                java.nio.file.Path oldDb = AppConfig.DATA_DIR.resolve("PasswordMaster.db");
                java.nio.file.Path newDb = AppConfig.DB_FILE;
                if (java.nio.file.Files.exists(oldDb) && !java.nio.file.Files.exists(newDb)) {
                    java.nio.file.Files.move(oldDb, newDb);
                    java.nio.file.Path oldWal = AppConfig.DATA_DIR.resolve("PasswordMaster.db-wal");
                    java.nio.file.Path newWal = AppConfig.DATA_DIR.resolve("MimaVault.db-wal");
                    if (java.nio.file.Files.exists(oldWal)) java.nio.file.Files.move(oldWal, newWal);
                    java.nio.file.Path oldShm = AppConfig.DATA_DIR.resolve("PasswordMaster.db-shm");
                    java.nio.file.Path newShm = AppConfig.DATA_DIR.resolve("MimaVault.db-shm");
                    if (java.nio.file.Files.exists(oldShm)) java.nio.file.Files.move(oldShm, newShm);
                }
            } catch (Exception ignore) { /* 迁移失败则继续按新库路径运行 */ }
            if (!java.nio.file.Files.exists(AppConfig.DATA_DIR)) {
                java.nio.file.Files.createDirectories(AppConfig.DATA_DIR);
            }
            try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS settings ("
                        + "id INTEGER PRIMARY KEY CHECK (id = 1),"
                        + "master_hash TEXT NOT NULL,"
                        + "created_at INTEGER)");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS entries ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "category TEXT DEFAULT '网站',"
                        + "platform TEXT,"
                        + "account TEXT,"
                        + "password_enc TEXT,"
                        + "phone TEXT,"
                        + "email TEXT,"
                        + "note TEXT,"
                        + "image_path TEXT,"
                        + "gesture_seq TEXT,"
                        + "totp_secret_enc TEXT,"
                        + "sync_status TEXT DEFAULT 'local',"
                        + "created_at INTEGER,"
                        + "updated_at INTEGER)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entries_platform ON entries(platform)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entries_category ON entries(category)");
                // 密码历史版本表：改密前的旧密码（密文存储），与 entries 解耦，旧库升级仅新增该表
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS password_history ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "entry_id INTEGER NOT NULL,"
                        + "password_enc TEXT,"
                        + "changed_at INTEGER)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pwd_history_entry ON password_history(entry_id)");
            }
            // 兼容旧库：检测缺失列并补齐
            ensureColumn("entries", "category", "TEXT DEFAULT '网站'");
            ensureColumn("entries", "sync_status", "TEXT DEFAULT 'local'");
            ensureColumn("entries", "deleted_at", "INTEGER");
            // TOTP 动态验证码密钥（otpauth 链接密文），与安卓端 TOTP 能力对齐
            ensureColumn("entries", "totp_secret_enc", "TEXT");
        } catch (Exception e) {
            throw new IllegalStateException("数据库初始化失败", e);
        }
    }

    /** 检测表缺失列并 ALTER TABLE 补齐（兼容已有数据库） */
    private void ensureColumn(String table, String column, String definition) {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            boolean found = false;
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                try (Statement alter = conn.createStatement()) {
                    alter.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                }
                System.out.println("数据库升级：已为 " + table + " 表补充列 " + column);
            }
        } catch (SQLException e) {
            System.err.println("检测列失败（不影响主流程）: " + e.getMessage());
        }
    }

    /** 是否已设置主密码 */
    public boolean isMasterSet() {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT master_hash FROM settings WHERE id = 1")) {
            return rs.next();
        } catch (SQLException e) {
            throw new IllegalStateException("读取主密码状态失败", e);
        }
    }

    /** 保存主密码哈希 */
    public void saveMasterHash(String hash) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO settings (id, master_hash, created_at) VALUES (1, ?, ?) "
                             + "ON CONFLICT(id) DO UPDATE SET master_hash = excluded.master_hash")) {
            ps.setString(1, hash);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存主密码失败", e);
        }
    }

    /** 读取主密码哈希 */
    public String getMasterHash() {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT master_hash FROM settings WHERE id = 1")) {
            if (rs.next()) {
                return rs.getString("master_hash");
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("读取主密码失败", e);
        }
    }

    /** 插入条目 */
    public long insertEntry(Entry e) {
        String sql = "INSERT INTO entries (category, platform, account, password_enc, phone, email, note, image_path, gesture_seq, totp_secret_enc, sync_status, created_at, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            long now = System.currentTimeMillis();
            ps.setString(1, e.getCategory());
            ps.setString(2, e.getPlatform());
            ps.setString(3, e.getAccount());
            ps.setString(4, e.getPasswordEnc());
            ps.setString(5, e.getPhone());
            ps.setString(6, e.getEmail());
            ps.setString(7, e.getNote());
            ps.setString(8, e.getImagePath());
            ps.setString(9, e.getGestureSeq());
            ps.setString(10, e.getTotpSecretEnc());
            ps.setString(11, e.getSyncStatus());
            ps.setLong(12, now);
            ps.setLong(13, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            return -1;
        } catch (SQLException ex) {
            throw new IllegalStateException("插入条目失败", ex);
        }
    }

    /** 更新条目 */
    public void updateEntry(Entry e) {
        String sql = "UPDATE entries SET category=?, platform=?, account=?, password_enc=?, phone=?, email=?, note=?, image_path=?, gesture_seq=?, totp_secret_enc=?, updated_at=? WHERE id=?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, e.getCategory());
            ps.setString(2, e.getPlatform());
            ps.setString(3, e.getAccount());
            ps.setString(4, e.getPasswordEnc());
            ps.setString(5, e.getPhone());
            ps.setString(6, e.getEmail());
            ps.setString(7, e.getNote());
            ps.setString(8, e.getImagePath());
            ps.setString(9, e.getGestureSeq());
            ps.setString(10, e.getTotpSecretEnc());
            ps.setLong(11, System.currentTimeMillis());
            ps.setLong(12, e.getId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("更新条目失败", ex);
        }
    }

    /** 删除条目 */
    public void deleteEntry(long id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM entries WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("删除条目失败", ex);
        }
    }

    // ---------- 回收站（软删除） ----------

    /** 移入回收站：仅置 deleted_at，不物理删除 */
    public void trashEntry(long id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("UPDATE entries SET deleted_at=?, updated_at=? WHERE id=? AND deleted_at IS NULL")) {
            long now = System.currentTimeMillis();
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("移入回收站失败", ex);
        }
    }

    /** 从回收站恢复：清空 deleted_at */
    public void restoreEntry(long id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("UPDATE entries SET deleted_at=NULL, updated_at=? WHERE id=? AND deleted_at IS NOT NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("恢复条目失败", ex);
        }
    }

    /** 彻底删除回收站中的单条条目（连带清理其密码历史） */
    public void purgeEntry(long id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM entries WHERE id=? AND deleted_at IS NOT NULL")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("彻底删除失败", ex);
        }
        deletePasswordHistory(id);
    }

    /** 清空回收站：物理删除所有已标记删除的条目（连带清理其密码历史） */
    public void purgeAllTrashed() {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM password_history WHERE entry_id IN "
                    + "(SELECT id FROM entries WHERE deleted_at IS NOT NULL)");
            stmt.executeUpdate("DELETE FROM entries WHERE deleted_at IS NOT NULL");
        } catch (SQLException e) {
            throw new IllegalStateException("清空回收站失败", e);
        }
    }

    /** 查询回收站全部条目（按删除时间倒序） */
    public List<Entry> listTrashedEntries() {
        List<Entry> list = new ArrayList<>();
        String sql = "SELECT * FROM entries WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询回收站失败", e);
        }
        return list;
    }

    /** 查询全部条目（不含回收站） */
    public List<Entry> getAllEntries() {
        List<Entry> list = new ArrayList<>();
        String sql = "SELECT * FROM entries WHERE deleted_at IS NULL ORDER BY updated_at DESC";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询条目失败", e);
        }
        return list;
    }

    /**
     * 组合查询：按分类过滤 + 关键词模糊搜索（平台/账号/手机/邮箱）
     *
     * @param keyword  关键词，null/空表示不过滤
     * @param category 分类，null/空/全部 表示不过滤
     */
    public List<Entry> searchEntries(String keyword, String category) {
        List<Entry> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM entries WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (category != null && !category.trim().isEmpty() && !"全部".equals(category.trim())) {
            sql.append(" AND category=?");
            params.add(category.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (platform LIKE ? OR account LIKE ? OR phone LIKE ? OR email LIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY updated_at DESC");
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("搜索条目失败", e);
        }
        return list;
    }

    /** 按 ID 查询单条 */
    public Entry getEntryById(long id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM entries WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询条目失败", e);
        }
        return null;
    }

    /** 清空全部条目（导入覆盖模式使用，连带清理密码历史） */
    public void clearEntries() {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM password_history");
            stmt.executeUpdate("DELETE FROM entries");
        } catch (SQLException e) {
            throw new IllegalStateException("清空条目失败", e);
        }
    }

    /** 批量插入（导入恢复使用） */
    public void insertAll(List<Entry> entries) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO entries (category, platform, account, password_enc, phone, email, note, image_path, gesture_seq, totp_secret_enc, sync_status, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (Entry e : entries) {
                    ps.setString(1, e.getCategory() == null ? Entry.CATEGORY_WEBSITE : e.getCategory());
                    ps.setString(2, e.getPlatform());
                    ps.setString(3, e.getAccount());
                    ps.setString(4, e.getPasswordEnc());
                    ps.setString(5, e.getPhone());
                    ps.setString(6, e.getEmail());
                    ps.setString(7, e.getNote());
                    ps.setString(8, e.getImagePath());
                    ps.setString(9, e.getGestureSeq());
                    ps.setString(10, e.getTotpSecretEnc());
                    ps.setString(11, e.getSyncStatus() == null ? "local" : e.getSyncStatus());
                    ps.setLong(12, e.getCreatedAt() == null ? System.currentTimeMillis() : e.getCreatedAt().getTime());
                    ps.setLong(13, e.getUpdatedAt() == null ? System.currentTimeMillis() : e.getUpdatedAt().getTime());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("批量导入失败", e);
        }
    }

    // ---------- 密码历史版本 ----------

    /** 写入一条历史密码（加密串），返回新记录 id */
    public long insertPasswordHistory(long entryId, String passwordEnc) {
        String sql = "INSERT INTO password_history (entry_id, password_enc, changed_at) VALUES (?,?,?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, entryId);
            ps.setString(2, passwordEnc);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            return -1;
        } catch (SQLException e) {
            throw new IllegalStateException("写入密码历史失败", e);
        }
    }

    /** 查询某条目的历史密码（按时间倒序，最新在前） */
    public List<PasswordHistoryItem> listPasswordHistory(long entryId) {
        List<PasswordHistoryItem> list = new ArrayList<>();
        String sql = "SELECT id, entry_id, password_enc, changed_at FROM password_history "
                + "WHERE entry_id=? ORDER BY changed_at DESC, id DESC";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PasswordHistoryItem it = new PasswordHistoryItem();
                    it.setId(rs.getLong("id"));
                    it.setEntryId(rs.getLong("entry_id"));
                    it.setPasswordEnc(rs.getString("password_enc"));
                    long t = rs.getLong("changed_at");
                    if (t > 0) {
                        it.setChangedAt(new Date(t));
                    }
                    list.add(it);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询密码历史失败", e);
        }
        return list;
    }

    /** 查询全部历史密码记录（改主密码时需整体重加密） */
    public List<PasswordHistoryItem> listAllPasswordHistory() {
        List<PasswordHistoryItem> list = new ArrayList<>();
        String sql = "SELECT id, entry_id, password_enc, changed_at FROM password_history ORDER BY id";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                PasswordHistoryItem it = new PasswordHistoryItem();
                it.setId(rs.getLong("id"));
                it.setEntryId(rs.getLong("entry_id"));
                it.setPasswordEnc(rs.getString("password_enc"));
                long t = rs.getLong("changed_at");
                if (t > 0) {
                    it.setChangedAt(new Date(t));
                }
                list.add(it);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询密码历史失败", e);
        }
        return list;
    }

    /** 更新单条历史记录的密文（改主密码重加密使用） */
    public void updatePasswordHistoryEnc(long historyId, String passwordEnc) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("UPDATE password_history SET password_enc=? WHERE id=?")) {
            ps.setString(1, passwordEnc);
            ps.setLong(2, historyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新密码历史失败", e);
        }
    }

    /** 某条目的历史密码条数 */
    public int countPasswordHistory(long entryId) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM password_history WHERE entry_id=?")) {
            ps.setLong(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计密码历史失败", e);
        }
        return 0;
    }

    /** 超出保留上限时裁剪最旧的历史记录（仅保留最新 keep 条） */
    public void trimPasswordHistory(long entryId, int keep) {
        String sql = "DELETE FROM password_history WHERE entry_id=? AND id NOT IN "
                + "(SELECT id FROM password_history WHERE entry_id=? "
                + "ORDER BY changed_at DESC, id DESC LIMIT ?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entryId);
            ps.setLong(2, entryId);
            ps.setInt(3, Math.max(0, keep));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("裁剪密码历史失败", e);
        }
    }

    /** 删除某条目的全部历史 */
    public void deletePasswordHistory(long entryId) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM password_history WHERE entry_id=?")) {
            ps.setLong(1, entryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除密码历史失败", e);
        }
    }

    /** 删除单条历史记录 */
    public void deletePasswordHistoryById(long historyId) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM password_history WHERE id=?")) {
            ps.setLong(1, historyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除密码历史失败", e);
        }
    }

    private Entry mapRow(ResultSet rs) throws SQLException {
        Entry e = new Entry();
        e.setId(rs.getLong("id"));
        e.setCategory(rs.getString("category"));
        e.setPlatform(rs.getString("platform"));
        e.setAccount(rs.getString("account"));
        e.setPasswordEnc(rs.getString("password_enc"));
        e.setPhone(rs.getString("phone"));
        e.setEmail(rs.getString("email"));
        e.setNote(rs.getString("note"));
        e.setImagePath(rs.getString("image_path"));
        e.setGestureSeq(rs.getString("gesture_seq"));
        try {
            e.setTotpSecretEnc(rs.getString("totp_secret_enc"));
        } catch (SQLException ignore) {
            e.setTotpSecretEnc(null); // 老库升级前的行不含该列（极端情况兜底）
        }
        e.setSyncStatus(rs.getString("sync_status"));
        long created = rs.getLong("created_at");
        long updated = rs.getLong("updated_at");
        long deleted = rs.getLong("deleted_at");
        if (created > 0) e.setCreatedAt(new java.util.Date(created));
        if (updated > 0) e.setUpdatedAt(new java.util.Date(updated));
        if (deleted > 0) e.setDeletedAt(new java.util.Date(deleted));
        return e;
    }
}
