package com.passwordmaster.util;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;

/**
 * 安全剪贴板工具
 * 复制密码等敏感内容后，30 秒自动清除剪贴板；
 * 到期时若用户已复制了其他内容，则不覆盖用户的新内容（避免误伤）。
 */
public final class ClipboardSafe {

    /** 自动清除延迟（毫秒） */
    private static final int CLEAR_DELAY_MS = 30_000;

    private ClipboardSafe() {
    }

    /**
     * 复制普通文本到系统剪贴板（不自动清理）。
     * 用于非机密内容（如生成的邮箱地址）的复制。
     */
    public static void copy(String text) {
        String content = text == null ? "" : text;
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(content), null);
    }

    /**
     * 复制敏感文本到系统剪贴板，30 秒后自动清除。
     * 清除条件：剪贴板当前内容仍等于本次复制的文本（即用户未复制新内容）。
     */
    public static void copySecret(String text) {
        final String secret = text == null ? "" : text;
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(secret), null);

        Timer timer = new Timer(CLEAR_DELAY_MS, e -> {
            try {
                Transferable current = clipboard.getContents(null);
                if (current != null && current.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    Object data = current.getTransferData(DataFlavor.stringFlavor);
                    if (data != null && secret.equals(data.toString())) {
                        // 内容仍是我们复制的密码，安全清空
                        clipboard.setContents(new StringSelection(""), null);
                    }
                    // 用户已复制其他内容 -> 不清除，避免误伤
                }
            } catch (Exception ex) {
                // 剪贴板被占用等异常时静默失败，不影响用户
            }
        });
        timer.setRepeats(false);
        timer.start();
    }
}
