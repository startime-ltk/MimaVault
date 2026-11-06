package com.mimavault.util;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 数据目录失踪后的"找回来"能力（v8.35.3）
 *
 * <p>为什么需要它：定位文件（%APPDATA%\MimaVault\data_location.json）只记住一个路径。
 * 一旦用户用资源管理器把数据文件夹挪走，路径失效，用户既不知道数据去哪了，
 * 也记不住原来的路径——让他自己"选择数据目录"等于让他猜。
 *
 * <p>所以这里做的是：扫一遍各磁盘，把凡是含有 MimaVault.db 的目录都找出来，
 * 并读出每个库的记录条数与最后修改时间，让用户一眼认出
 * "59 条、9 月 5 日那个"才是自己要的。
 *
 * <p>安全约束：全程只读。数据库以 SQLite 只读模式打开（mode=ro），
 * 不会写入、不会新建 -wal/-shm、不会修改任何候选文件。
 */
public final class DataDirRecovery {

    /** 现用库文件名 */
    public static final String DB_NAME = "MimaVault.db";
    /** 旧版库文件名（早期版本叫 PasswordMaster） */
    public static final String LEGACY_DB_NAME = "PasswordMaster.db";

    private static final int MAX_DEPTH = 4;
    private static final int MAX_DEPTH_UNDER_HOME = 6;
    private static final long TIME_BUDGET_MS = 20_000L;
    private static final int MAX_DIRS = 120_000;

    /** 明显不会放用户数据、且体量巨大的目录，直接跳过（避免扫描把磁盘拖满） */
    private static final Set<String> SKIP = new HashSet<>(List.of(
            "windows", "program files", "program files (x86)", "programdata",
            "$recycle.bin", "system volume information", "recovery",
            "appdata", "node_modules", ".git", ".gradle", ".m2", ".cache", ".svn", ".hg",
            "steamapps", "steamlibrary", "__pycache__", "venv", ".venv",
            "target", "build", "out", "dist", ".idea",
            "runtime", "jdk", "jre", "temp", "tmp"
    ));

    private DataDirRecovery() {
    }

    /** 扫描找到的一个候选数据目录 */
    public static final class Candidate {
        public final Path dir;
        /** 记录条数；-1 表示读不出来（库损坏或被占用） */
        public final int records;
        /** 数据库文件最后修改时间（毫秒） */
        public final long lastModified;
        public final String dbName;

        Candidate(Path dir, int records, long lastModified, String dbName) {
            this.dir = dir;
            this.records = records;
            this.lastModified = lastModified;
            this.dbName = dbName;
        }

        public String timeText() {
            if (lastModified <= 0) {
                return "时间未知";
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(lastModified));
        }

        public String countText() {
            return records < 0 ? "条数未知" : records + " 条记录";
        }

        @Override
        public String toString() {
            return countText() + " · " + timeText() + " · " + dir;
        }
    }

    /**
     * 扫描所有可读磁盘，凡含有密码库的目录都会通过 {@code sink} 回调抛出来。
     * 本方法会阻塞，调用方应放在后台线程执行。
     *
     * @param sink 每找到一个就回调一次（在调用线程上执行，界面需自行切回 EDT）
     * @param stop 返回 true 时尽快结束扫描（用户关掉窗口时用）
     */
    public static void scan(Consumer<Candidate> sink, BooleanSupplier stop) {
        long deadline = System.currentTimeMillis() + TIME_BUDGET_MS;
        Set<Path> visited = new HashSet<>();
        int[] budget = {MAX_DIRS};
        List<Path> starts = new ArrayList<>();

        for (File root : File.listRoots()) {
            String name = root.getAbsolutePath();
            if (name.startsWith("A:") || name.startsWith("B:")) {
                continue; // 软驱盘符，跳过
            }
            if (!root.isDirectory() || !root.canRead()) {
                continue;
            }
            starts.add(root.toPath());
        }

        Path home = Paths.get(System.getProperty("user.home", "")).toAbsolutePath().normalize();
        for (Path start : starts) {
            walk(start, 0, home, visited, sink, stop, deadline, budget);
            if (stop.getAsBoolean()) {
                return;
            }
        }
    }

    private static void walk(Path dir, int depth, Path home, Set<Path> visited,
                             Consumer<Candidate> sink, BooleanSupplier stop,
                             long deadline, int[] budget) {
        if (budget[0] <= 0 || System.currentTimeMillis() > deadline) {
            return;
        }
        if (stop.getAsBoolean()) {
            return;
        }
        Path current = dir.toAbsolutePath().normalize();
        if (!visited.add(current)) {
            return;
        }
        budget[0]--;

        int maxDepth = current.startsWith(home) ? MAX_DEPTH_UNDER_HOME : MAX_DEPTH;
        if (depth > maxDepth) {
            return;
        }

        // 1) 这个目录本身是不是数据目录
        checkDir(current, sink);

        // 2) 继续往下找
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
                if (SKIP.contains(name)) {
                    continue;
                }
                if (name.startsWith(".") || name.startsWith("$")) {
                    continue;
                }
                walk(child, depth + 1, home, visited, sink, stop, deadline, budget);
                if (budget[0] <= 0 || System.currentTimeMillis() > deadline || stop.getAsBoolean()) {
                    return;
                }
            }
        } catch (Exception ignored) {
            // 权限不足 / 目录被占用：跳过即可
        }
    }

    /** 目录里有密码库就抛出来（只读，不改动任何东西） */
    private static void checkDir(Path dir, Consumer<Candidate> sink) {
        for (String dbName : new String[]{DB_NAME, LEGACY_DB_NAME}) {
            Path db = dir.resolve(dbName);
            if (!Files.isRegularFile(db)) {
                continue;
            }
            long mtime = 0L;
            try {
                mtime = Files.getLastModifiedTime(db).toMillis();
            } catch (Exception ignored) {
                // 时间读不出来不影响使用
            }
            sink.accept(new Candidate(dir, countRecords(db), mtime, dbName));
            return;
        }
    }

    /**
     * 只读统计记录条数。
     *
     * <p>用 SQLite 的 {@code mode=ro}，保证扫描不会在别人的目录里留下任何文件
     * （普通连接打开 WAL 模式的库可能会生成 -wal/-shm，扫描阶段没这个必要）。
     */
    private static int countRecords(Path db) {
        String url = "jdbc:sqlite:file:" + db.toAbsolutePath().toString().replace('\\', '/') + "?mode=ro";
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM entries")) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
