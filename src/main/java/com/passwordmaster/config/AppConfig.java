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

    /** 数据库文件路径 data/PasswordMaster.db */
    public static final Path DB_FILE = DATA_DIR.resolve("PasswordMaster.db");

    public static AppConfig load() {
        // 数据目录固定为运行目录下的 data/（见 DATA_DIR 常量），config.json 不再有生效字段；
        // 保留 save() 仅为兼容旧逻辑与后期扩展。
        return new AppConfig();
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
