package com.mimavault.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * 主密码登录失败锁定管理（安卓端）：
 * - 5 次失败锁定 30 分钟（与 PC 端策略一致）
 * - 失败计数与锁定截止持久化到 SharedPreferences（跨重启保留）
 * - 单调时间源防系统时间回拨绕过：
 *   锁定时记录 (elapsedRealtime, wall) 锚点对；进程存活期间用 elapsedRealtime 判定真实流逝，
 *   wall 被拨快（now >= lockUntil）但单调时间未满 30 分钟时仍视为锁定；
 *   重启后无单调锚点，退化为 wall 判定。
 */
public final class AuthLockoutManager {

    private static final String PREFS = "auth_lockout";
    private static final String P_FAILS = "fail_count";
    private static final String P_LOCK_UNTIL = "lock_until";      // wall 截止
    private static final String P_LOCK_MONO = "lock_mono";        // 锁定时刻 elapsedRealtime
    private static final String P_LOCK_WALL = "lock_wall";        // 锁定时刻 wall

    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCK_MILLIS = 30 * 60 * 1000L;
    /** 单调与 wall 允许偏差（毫秒），防正常时钟调整误判 */
    private static final long CLOCK_SKEW_TOLERANCE_MS = 5_000L;

    private AuthLockoutManager() {
    }

    /** 是否处于锁定中（持久化 + 单调时间联合判定） */
    public static boolean isLocked(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lockUntil = sp.getLong(P_LOCK_UNTIL, 0L);
        if (lockUntil <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < lockUntil) {
            return true;
        }
        // wall 已到截止：校验单调时间，防系统时间被拨快绕过锁定
        long monoAnchor = sp.getLong(P_LOCK_MONO, 0L);
        long wallAnchor = sp.getLong(P_LOCK_WALL, 0L);
        if (monoAnchor > 0 && wallAnchor > 0) {
            long elapsedMonoMs = SystemClock.elapsedRealtime() - monoAnchor;
            long elapsedWall = now - wallAnchor;
            if (elapsedMonoMs < LOCK_MILLIS - CLOCK_SKEW_TOLERANCE_MS
                    && elapsedWall >= LOCK_MILLIS + CLOCK_SKEW_TOLERANCE_MS) {
                // wall 前进远超单调流逝 → 时钟被拨快，锁定实际未到期
                return true;
            }
        }
        // 锁定到期：自动解除并清零计数
        sp.edit()
                .putLong(P_LOCK_UNTIL, 0L)
                .putLong(P_LOCK_MONO, 0L)
                .putLong(P_LOCK_WALL, 0L)
                .putInt(P_FAILS, 0)
                .apply();
        return false;
    }

    /** 剩余锁定毫秒（供 UI 倒计时；未锁定返回 0） */
    public static long remainingLockMillis(Context context) {
        if (!isLocked(context)) {
            return 0;
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lockUntil = sp.getLong(P_LOCK_UNTIL, 0L);
        long now = System.currentTimeMillis();
        if (now < lockUntil) {
            return lockUntil - now;
        }
        // wall 已过但单调未到（拨快时钟场景）：按单调剩余计算
        long monoAnchor = sp.getLong(P_LOCK_MONO, 0L);
        if (monoAnchor > 0) {
            long elapsedMonoMs = SystemClock.elapsedRealtime() - monoAnchor;
            if (elapsedMonoMs < LOCK_MILLIS) {
                return LOCK_MILLIS - elapsedMonoMs;
            }
        }
        return 0;
    }

    /** 记录一次失败；返回是否刚触发锁定 */
    public static boolean recordFailure(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int count = sp.getInt(P_FAILS, 0) + 1;
        if (count >= MAX_ATTEMPTS) {
            long now = System.currentTimeMillis();
            sp.edit()
                    .putInt(P_FAILS, count)
                    .putLong(P_LOCK_UNTIL, now + LOCK_MILLIS)
                    .putLong(P_LOCK_MONO, SystemClock.elapsedRealtime())
                    .putLong(P_LOCK_WALL, now)
                    .apply();
            return true;
        }
        sp.edit().putInt(P_FAILS, count).apply();
        return false;
    }

    /** 剩余可试次数 */
    public static int remainingAttempts(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return Math.max(0, MAX_ATTEMPTS - sp.getInt(P_FAILS, 0));
    }

    /** 解锁成功：清零计数与锁定 */
    public static void onSuccess(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(P_FAILS, 0)
                .putLong(P_LOCK_UNTIL, 0L)
                .putLong(P_LOCK_MONO, 0L)
                .putLong(P_LOCK_WALL, 0L)
                .apply();
    }
}
