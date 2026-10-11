package com.mimavault.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 剪贴板工具：复制密码 + 30 秒自动清除
 */
public final class ClipboardUtil {

    private static final long CLEAR_DELAY_MS = 30_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pendingClear;

    private ClipboardUtil() {
    }

    /** 复制到剪贴板，30 秒后若剪贴板仍是该内容则自动清除 */
    public static void copyWithAutoClear(Context context, String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("MimaVault", text));
        if (pendingClear != null) {
            MAIN.removeCallbacks(pendingClear);
        }
        pendingClear = () -> {
            ClipData current = cm.getPrimaryClip();
            if (current != null && current.getItemCount() > 0) {
                CharSequence cur = current.getItemAt(0).getText();
                if (text.equals(String.valueOf(cur))) {
                    cm.clearPrimaryClip();
                }
            }
        };
        MAIN.postDelayed(pendingClear, CLEAR_DELAY_MS);
    }
}
