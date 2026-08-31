package com.passwordmaster.ui;

import javax.swing.*;
import java.awt.*;

/**
 * 轻量非模态提示气泡：显示在父窗口底部中间，1.6 秒后自动消失。
 * 用于密码生成等操作的即时反馈，不阻塞用户继续操作。
 */
public final class Toast {

    private static final int SHOW_MS = 1600;

    private Toast() {
    }

    /**
     * 在指定窗口底部弹出提示。
     *
     * @param owner 父窗口（对话框/框架）；为 null 时不显示
     * @param text  提示文本
     */
    public static void show(Window owner, String text) {
        if (owner == null || text == null || text.isEmpty()) {
            return;
        }
        JWindow win = new JWindow(owner);
        JLabel label = new JLabel(text);
        label.setOpaque(true);
        label.setBackground(new Color(45, 45, 45, 225));
        label.setForeground(Color.WHITE);
        label.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        label.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        win.setContentPane(label);
        win.pack();

        try {
            Point loc = owner.getLocationOnScreen();
            Dimension size = owner.getSize();
            int x = loc.x + (size.width - win.getWidth()) / 2;
            int y = loc.y + size.height - win.getHeight() - 50;
            win.setLocation(x, y);
        } catch (Exception e) {
            win.setLocationRelativeTo(owner);
        }

        win.setAlwaysOnTop(true);
        win.setVisible(true);

        Timer timer = new Timer(SHOW_MS, e -> win.dispose());
        timer.setRepeats(false);
        timer.start();
    }
}
