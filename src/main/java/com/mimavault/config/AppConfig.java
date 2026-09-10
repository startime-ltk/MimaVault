package com.mimavault.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地应用配置（数据目录内 config.json + 数据目录定位文件）
 *
 * 数据目录规则（v8.30.1 起支持用户自选）：
 *  - 数据目录（dataDir）为存放 config.json / MimaVault.db / images / backup 等的目录，
 *    默认与历史版本一致 = 程序运行目录/data，不迁移旧数据、开箱即用。
 *  - 用户可通过主界面「数据目录」设置选择其它目录：迁移 = 把整个数据目录复制到新位置并
 *    记录定位文件，重启程序后所有读写自动切换到新位置；旧目录数据保留供用户核验后手动删除。
 *  - 数据目录的实际位置记录在全局定位文件 {@link #LOCATOR_FILE}（%APPDATA%\MimaVault\），
 *    与程序安装/运行目录解耦，避免升级覆盖或换目录运行时丢失定位。
 *  - 兼容场景：定位文件缺失（旧版本升级/首次运行/便携目录整体搬迁到新电脑）时自动回退到
 *    程序目录/data；定位指向的目录已不存在且程序目录/data 存在时同样回退并重写定位文件。
 */
public class AppConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 应用根目录（jar 所在目录） */
    public static final Path APP_DIR = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

    /** 历史默认数据目录（jar 目录/data），旧数据兼容位置 */
    public static final Path DEFAULT_DATA_DIR = APP_DIR.resolve("data");

    /** 数据目录定位文件（全局，用户级，与程序目录解耦）：%APPDATA%\MimaVault\data_location.json */
    public static final Path LOCATOR_FILE = resolveLocatorFile();

    /** 数据目录（用户可自选，默认 = 程序目录/data），运行时可变，调用方统一经本字段取路径 */
    public static Path DATA_DIR = DEFAULT_DATA_DIR;

    /** 图片附件目录 data/images */
    public static Path IMAGE_DIR = DATA_DIR.resolve("images");

    /** 配置文件路径 data/config.json */
    public static Path CONFIG_FILE = DATA_DIR.resolve("config.json");

    /** 数据库文件路径 data/MimaVault.db（旧库 PasswordMaster.db 由 DatabaseManager 自动迁移） */
    public static Path DB_FILE = DATA_DIR.resolve("MimaVault.db");

    /** 智谱 AI API Key（AI 辅助识别用；空表示未配置，AI 识别默认关闭） */
    public String zhipuApiKey = "";

    /** 点击关闭按钮时的行为：""=未设置（每次询问）、"exit"=关闭程序、"minimize"=最小化到托盘 */
    public String closeAction = "";

    /** 当前数据目录（冗余记录到 config.json，便于排查；空 = 尚未写入） */
    public String dataDir = "";

    static {
        initDataPaths();
    }

    private static Path resolveLocatorFile() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData).resolve("MimaVault").resolve("data_location.json");
        }
        // 无 APPDATA 时退回用户主目录
        return Paths.get(System.getProperty("user.home"), ".mimavault", "data_location.json");
    }

    /**
     * 启动时确定数据目录并刷新派生路径常量。
     * 优先级：定位文件指向的有效目录 > 程序目录/data（旧数据兼容）> 默认目录并写回定位文件。
     */
    public static void initDataPaths() {
        Path dir = readLocator();
        if (dir == null || !Files.isDirectory(dir)) {
            // 首次运行 / 定位文件缺失或损坏 / 定位目录已不存在：沿用历史默认位置（兼容旧数据）
            dir = DEFAULT_DATA_DIR;
        }
        setDataDir(dir);
        try {
            saveLocator(DATA_DIR);
        } catch (IOException ignored) {
            // 定位文件写入失败不阻塞启动（下次启动仍按默认 data 兼容）
        }
    }

    /** 应用新的数据目录并刷新全部派生路径（启动与迁移成功后调用） */
    private static void setDataDir(Path dataDir) {
        DATA_DIR = dataDir.toAbsolutePath().normalize();
        IMAGE_DIR = DATA_DIR.resolve("images");
        CONFIG_FILE = DATA_DIR.resolve("config.json");
        DB_FILE = DATA_DIR.resolve("MimaVault.db");
    }

    /** 读取定位文件记录的数据目录；无记录/解析失败返回 null */
    private static Path readLocator() {
        try {
            if (Files.exists(LOCATOR_FILE)) {
                String json = Files.readString(LOCATOR_FILE, StandardCharsets.UTF_8);
                if (json != null && !json.trim().isEmpty()) {
                    Locator loc = GSON.fromJson(json, Locator.class);
                    if (loc != null && loc.dataDir != null && !loc.dataDir.isBlank()) {
                        return Paths.get(loc.dataDir);
                    }
                }
            }
        } catch (Exception ignored) {
            // 损坏按首次运行处理
        }
        return null;
    }

    /** 把数据目录记录写入全局定位文件（先建父目录，临时文件+原子移动） */
    public static void saveLocator(Path dataDir) throws IOException {
        Files.createDirectories(LOCATOR_FILE.getParent());
        Locator loc = new Locator();
        loc.dataDir = dataDir.toAbsolutePath().normalize().toString();
        Path tmp = LOCATOR_FILE.resolveSibling(LOCATOR_FILE.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(loc), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, LOCATOR_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    public static AppConfig load() {
        AppConfig cfg = new AppConfig();
        try {
            if (Files.exists(CONFIG_FILE)) {
                String json = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
                if (json != null && !json.trim().isEmpty()) {
                    AppConfig loaded = GSON.fromJson(json, AppConfig.class);
                    if (loaded != null) {
                        cfg = loaded;
                    }
                }
            }
        } catch (Exception ignored) {
            // 配置损坏时使用默认值，不影响启动
        }
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(DATA_DIR);
            dataDir = DATA_DIR.toString();
            Files.writeString(CONFIG_FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存配置失败", e);
        }
    }

    /** 定位文件 JSON 结构 */
    private static class Locator {
        String dataDir;
    }
}
