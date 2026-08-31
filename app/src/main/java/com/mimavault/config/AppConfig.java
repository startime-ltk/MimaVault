package com.mimavault.config;

import android.content.Context;

import com.mimavault.MimaVaultApp;

import java.io.File;

/**
 * 数据目录定位（安卓端）
 * 数据根：filesDir/data/，与 PC 端 data/ 布局对应：
 *   data/mimavault.db
 *   data/images/
 */
public final class AppConfig {

    private AppConfig() {
    }

    public static File dataDir() {
        File dir = new File(MimaVaultApp.getAppContext().getFilesDir(), "data");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static File imageDir() {
        File dir = new File(dataDir(), "images");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static File dbFile() {
        File db = new File(dataDir(), "mimavault.db");
        File legacy = new File(dataDir(), "PasswordMaster.db");
        if (!db.exists() && legacy.exists()) {
            //noinspection ResultOfMethodCallIgnored
            legacy.renameTo(db);
        }
        return db;
    }

    /** 相对路径（images/xxx.png）解析为绝对路径，与 PC 端 resolveImagePath 一致 */
    public static File resolveImagePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        return new File(dataDir(), relativePath);
    }
}
