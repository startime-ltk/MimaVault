package com.mimavault.service;

import com.mimavault.db.DatabaseManager;

/**
 * 登录失败锁定管理（PC 端）：
 * - 5 次失败锁定 30 分钟
 * - 失败计数与锁定截止持久化到 settings 表（跨重启保留）
 * - 单调时间源防系统时间回拨绕过：
 *   锁定时记录 (nanoTime, wall) 锚点对；进程存活期间用 nanoTime 判定真实流逝，
 *   wall 被拨快（now >= lockUntil）但单调时间未满 30 分钟时仍视为锁定；
 *   重启后无单调锚点，退化为 wall 判定。
 */
public class LoginLockoutManager {

    public static final int MAX_FAILURES = 5;
    public static final long LOCK_MILLIS = 30 * 60 * 1000L;
    /** 单调与 wall 允许偏差（毫秒），防正常时钟调整误判 */
    private static final long CLOCK_SKEW_TOLERANCE_MS = 5_000L;

    private final DatabaseManager db;

    public LoginLockoutManager(DatabaseManager db) {
        this.db = db;
    }

    /** 是否处于锁定中（持久化 + 单调时间联合判定） */
    public boolean isLocked() {
        long lockUntil = db.getLoginLockUntil();
        if (lockUntil <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < lockUntil) {
            // wall 未到截止：锁定中（回拨只会延长，不构成绕过）
            return true;
        }
        // wall 已到截止：校验单调时间，防系统时间被拨快绕过锁定
        long monoAnchor = db.getLoginLockMono();
        long wallAnchor = db.getLoginLockWall();
        if (monoAnchor > 0 && wallAnchor > 0) {
            long elapsedMonoMs = (System.nanoTime() - monoAnchor) / 1_000_000L;
            long elapsedWall = now - wallAnchor;
            if (elapsedMonoMs < LOCK_MILLIS - CLOCK_SKEW_TOLERANCE_MS
                    && elapsedWall >= LOCK_MILLIS + CLOCK_SKEW_TOLERANCE_MS) {
                // wall 前进远超单调流逝 → 时钟被拨快，锁定实际未到期
                return true;
            }
        }
        // 锁定到期（重启后仅 wall 判定）：自动解除并清零计数
        db.clearLoginLock();
        db.setLoginFailedCount(0);
        return false;
    }

    /** 剩余锁定毫秒（供 UI 倒计时；未锁定返回 0） */
    public long remainingLockMillis() {
        if (!isLocked()) {
            return 0;
        }
        long lockUntil = db.getLoginLockUntil();
        long now = System.currentTimeMillis();
        if (now < lockUntil) {
            return lockUntil - now;
        }
        // wall 已过但单调未到（拨快时钟场景）：按单调剩余计算
        long monoAnchor = db.getLoginLockMono();
        if (monoAnchor > 0) {
            long elapsedMonoMs = (System.nanoTime() - monoAnchor) / 1_000_000L;
            if (elapsedMonoMs < LOCK_MILLIS) {
                return LOCK_MILLIS - elapsedMonoMs;
            }
        }
        return 0;
    }

    /** 记录一次失败；返回是否触发锁定 */
    public boolean recordFailure() {
        int count = db.getLoginFailedCount() + 1;
        db.setLoginFailedCount(count);
        if (count >= MAX_FAILURES) {
            long now = System.currentTimeMillis();
            db.setLoginLock(now + LOCK_MILLIS, System.nanoTime(), now);
            return true;
        }
        return false;
    }

    /** 剩余可试次数 */
    public int remainingAttempts() {
        return Math.max(0, MAX_FAILURES - db.getLoginFailedCount());
    }

    /** 解锁成功：清零计数与锁定 */
    public void onSuccess() {
        db.clearLoginLock();
        db.setLoginFailedCount(0);
    }
}
