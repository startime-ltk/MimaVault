package com.mimavault.ui;

import javax.swing.*;
import java.awt.*;

/**
 * 渐变背景面板（仅表现层）
 * 用于窗口/标题栏/工具栏的背景渐变，不承载业务逻辑。
 */
public class GradientPanel extends JPanel {

    private final Color colorTop;
    private final Color colorBottom;

    public GradientPanel(Color colorTop, Color colorBottom) {
        this.colorTop = colorTop;
        this.colorBottom = colorBottom;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setPaint(new GradientPaint(0, 0, colorTop, 0, getHeight(), colorBottom));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
