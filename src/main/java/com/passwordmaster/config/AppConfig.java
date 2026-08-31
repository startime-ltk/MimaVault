package com.passwordmaster.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地应用配置（data/config.json）
 * 数据目录固定为运行目录下的 data/（见 DATA_DIR 常量），
 * 保留 save() 写配置框架便于后期扩展，不再提供可误导的 dataDir 字段。
 */
public class AppConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 应用根目录（jar 所在目录） */
    public static final Path APP_DIR = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

    /** 数据目录 data/ */
    public static final Path DATA_DIR = APP_DIR.resolve("data");

    /** 图片附件目录 data/images/ */
    public static final Path IMAGE_DIR = DATA_DIR.resolve("images");

    /** 配置文件路径 data/config.json */
    public static final Path CONFIG_FILE = DATA_DIR.resolve("config.json");

    /** 数据库文件路径 data/MimaVault.db（旧库 PasswordMaster.db 由 DatabaseManager 自动迁移） */
    public static final Path DB_FILE = DATA_DIR.resolve("MimaVault.db");

    /** 智谱 AI API Key（AI 辅助识别用；空表示未配置，AI 识别默认关闭） */
    public String zhipuApiKey = "";

    /** 点击关闭按钮时的行为：""=未设置（每次询问）、"exit"=关闭程序、"minimize"=最小化到托盘 */
    public String closeAction = "";

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
            Files.writeString(CONFIG_FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存配置失败", e);
        }
    }
}
