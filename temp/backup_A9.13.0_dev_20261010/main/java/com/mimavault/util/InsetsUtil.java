package com.mimavault.util;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 状态栏适配：targetSdk 36 强制 edge-to-edge 下系统会把窗口布局到状态栏之后，
 * 本工具为目标 View 增加 statusBars 顶部 inset padding，使内容整体下移避开状态栏。
 *
 * 建议：
 * - 顶部自带彩色背景的 View（渐变 Header / 纯色 Toolbar）直接作为 target，
 *   背景会自动向上延伸覆盖状态栏区域，视觉最自然；
 * - 普通白底页面将根布局作为 target，内容下移且不破坏内部控件坐标。
 */
public final class InsetsUtil {

    private InsetsUtil() {
    }

    /**
     * 为目标 View 叠加状态栏顶部 inset 到其 paddingTop。
     * 保留原有 padding（XML 中已声明的内边距），坐标逻辑不受影响。
     */
    public static void applyTopInset(final View target) {
        if (target == null) {
            return;
        }
        final int baseTop = target.getPaddingTop();
        final int baseLeft = target.getPaddingLeft();
        final int baseRight = target.getPaddingRight();
        final int baseBottom = target.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(target, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
            int top = bars.top > 0 ? bars.top : 0;
            if (v.getPaddingTop() != baseTop + top) {
                v.setPadding(baseLeft, baseTop + top, baseRight, baseBottom);
            }
            return WindowInsetsCompat.CONSUMED;
        });
        // 首次布局时若 inset 分发未触发，主动请求一次，保证各机型/旋转场景都生效
        target.post(() -> {
            View root = target.getRootView();
            if (root != null) {
                ViewCompat.requestApplyInsets(root);
            }
        });
    }
}
