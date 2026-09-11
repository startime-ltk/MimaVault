package com.mimavault;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.mimavault.db.DatabaseManager;
import com.mimavault.service.VaultSession;
import com.mimavault.ui.UnlockActivity;

/**
 * 应用入口：持有全局 Context，初始化数据库，并统一处理自动锁定与截屏保护
 */
public class MimaVaultApp extends Application {

    private static Context appContext;
    private static DatabaseManager db;
    private static volatile long lastActive = System.currentTimeMillis();

    /** 自动锁定阈值：无操作 3 分钟后锁定 */
    public static final long AUTO_LOCK_MILLIS = 3 * 60 * 1000L;

    /** 全局锁定检查间隔 */
    private static final long LOCK_CHECK_INTERVAL = 10_000L;

    private final Handler lockHandler = new Handler(Looper.getMainLooper());

    /** 全局锁定检查：任意界面停留超时都会触发，不再只依赖主界面 */
    private final Runnable globalLockCheck = new Runnable() {
        @Override
        public void run() {
            if (VaultSession.get().isOpen() && idleMillis() > AUTO_LOCK_MILLIS) {
                lockNow();
                return;
            }
            lockHandler.postDelayed(this, LOCK_CHECK_INTERVAL);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        db = new DatabaseManager();
        db.init();
        registerActivityLifecycleCallbacks(new LifecycleGuard());
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

    /** 立即锁定：关闭会话并拉起解锁页（清空任务栈，防止返回键绕过） */
    public static void lockNow() {
        VaultSession.get().close();
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        Intent i = new Intent(ctx, UnlockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(i);
    }

    /** 全局界面守卫：截屏保护 + 跨界面自动锁定 */
    private class LifecycleGuard implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            // 截屏与最近任务预览保护：避免密码明文被截屏或出现在系统任务缩略图中
            activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            lockHandler.removeCallbacks(globalLockCheck);
            if (activity instanceof UnlockActivity) {
                return;
            }
            if (VaultSession.get().isOpen() && idleMillis() > AUTO_LOCK_MILLIS) {
                lockNow();
                return;
            }
            lockHandler.postDelayed(globalLockCheck, LOCK_CHECK_INTERVAL);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            lockHandler.removeCallbacks(globalLockCheck);
            if (activity instanceof UnlockActivity) {
                // 离开解锁页即视为刚通过验证，重置无操作计时，避免解锁后立刻被再次锁定
                touch();
            }
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}
