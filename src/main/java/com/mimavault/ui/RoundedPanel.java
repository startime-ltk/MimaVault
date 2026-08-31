package com.mimavault.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * 圆角卡片面板（仅表现层）
 * 白色圆角 + 细边框，用于登录卡片、表格卡片、内容区块等。
 */
public class RoundedPanel extends JPanel {

    private final int arc;
    private Color fill = UiTheme.CARD_BG;
    private Color borderColor = UiTheme.CARD_BORDER;

    public RoundedPanel() {
        this(18);
    }

    public RoundedPanel(int arc) {
        this.arc = arc;
        setOpaque(false);
    }

    public RoundedPanel setFill(Color fill) {
        this.fill = fill;
        return this;
    }

    public RoundedPanel setBorderColor(Color borderColor) {
        this.borderColor = borderColor;
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape shape = new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        g2.setColor(fill);
        g2.fill(shape);
        if (borderColor != null) {
            g2.setColor(borderColor);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(shape);
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
