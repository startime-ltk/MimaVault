package com.passwordmaster.db;

import com.passwordmaster.config.AppConfig;
import com.passwordmaster.model.Entry;

import java.sql.*;
import java.util.ArrayList;
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
                        + "sync_status TEXT DEFAULT 'local',"
                        + "created_at INTEGER,"
                        + "updated_at INTEGER)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entries_platform ON entries(platform)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entries_category ON entries(category)");
            }
            // 兼容旧库：检测缺失列并补齐
            ensureColumn("entries", "category", "TEXT DEFAULT '网站'");
            ensureColumn("entries", "sync_status", "TEXT DEFAULT 'local'");
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
        String sql = "INSERT INTO entries (category, platform, account, password_enc, phone, email, note, image_path, gesture_seq, sync_status, created_at, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
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
            ps.setString(10, e.getSyncStatus());
            ps.setLong(11, now);
            ps.setLong(12, now);
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
        String sql = "UPDATE entries SET category=?, platform=?, account=?, password_enc=?, phone=?, email=?, note=?, image_path=?, gesture_seq=?, updated_at=? WHERE id=?";
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
            ps.setLong(10, System.currentTimeMillis());
            ps.setLong(11, e.getId());
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

    /** 查询全部条目 */
    public List<Entry> getAllEntries() {
        List<Entry> list = new ArrayList<>();
        String sql = "SELECT * FROM entries ORDER BY updated_at DESC";
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
        StringBuilder sql = new StringBuilder("SELECT * FROM entries WHERE 1=1");
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

    /** 清空全部条目（导入覆盖模式使用） */
    public void clearEntries() {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
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
                    "INSERT INTO entries (category, platform, account, password_enc, phone, email, note, image_path, gesture_seq, sync_status, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
                    ps.setString(10, e.getSyncStatus() == null ? "local" : e.getSyncStatus());
                    ps.setLong(11, e.getCreatedAt() == null ? System.currentTimeMillis() : e.getCreatedAt().getTime());
                    ps.setLong(12, e.getUpdatedAt() == null ? System.currentTimeMillis() : e.getUpdatedAt().getTime());
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
        e.setSyncStatus(rs.getString("sync_status"));
        long created = rs.getLong("created_at");
        long updated = rs.getLong("updated_at");
        if (created > 0) e.setCreatedAt(new java.util.Date(created));
        if (updated > 0) e.setUpdatedAt(new java.util.Date(updated));
        return e;
    }
}
