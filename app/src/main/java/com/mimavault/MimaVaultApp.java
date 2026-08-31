package com.mimavault;

import android.app.Application;
import android.content.Context;

import com.mimavault.db.DatabaseManager;

/**
 * 应用入口：持有全局 Context，初始化数据库
 */
public class MimaVaultApp extends Application {

    private static Context appContext;
    private static DatabaseManager db;
    private static volatile long lastActive = System.currentTimeMillis();

    /** 自动锁定阈值：无操作 3 分钟后锁定 */
    public static final long AUTO_LOCK_MILLIS = 3 * 60 * 1000L;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        db = new DatabaseManager();
        db.init();
    }

    public static Context getAppContext() {
        return appContext;
    }

    public static DatabaseManager db() {
        return db;
    }

    /** 记录用户最近一次交互时间（由各界面在 onUserInteraction 时刷新） */
    public static void touch() {
        lastActive = System.currentTimeMillis();
    }

    /** 距上次交互已过去的毫秒数 */
    public static long idleMillis() {
        return System.currentTimeMillis() - lastActive;
    }
}
