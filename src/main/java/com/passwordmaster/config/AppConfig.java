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
 * 目前保存数据目录等信息，为后期扩展预留
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

    /** 数据库文件路径 data/PasswordMaster.db */
    public static final Path DB_FILE = DATA_DIR.resolve("PasswordMaster.db");

    private String dataDir;

    public static AppConfig load() {
        AppConfig config = new AppConfig();
        if (Files.exists(CONFIG_FILE)) {
            try {
                String json = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
                AppConfig loaded = GSON.fromJson(json, AppConfig.class);
                if (loaded != null && loaded.dataDir != null) {
                    config.dataDir = loaded.dataDir;
                }
            } catch (Exception e) {
                System.err.println("读取配置失败，使用默认配置: " + e.getMessage());
            }
        }
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(DATA_DIR);
            Files.writeString(CONFIG_FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存配置失败", e);
        }
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
