package com.passwordmaster.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * 圆角渐变按钮（仅表现层）
 * - 渐变背景（主按钮蓝紫渐变 / 强调按钮暖橙渐变 / 次要按钮浅色描边）
 * - hover 提亮、按下变暗 + 文字微偏移
 * - 不承载业务逻辑，仅替换原 JButton 外观
 */
public class GradientButton extends JButton {

    private final Color start;
    private final Color end;
    private final Color textColor;
    private final Color borderColor;
    private final int arc = 14;

    private boolean hovered = false;
    private boolean armed = false;

    public GradientButton(String text, Color start, Color end, Color textColor, Color borderColor) {
        super(text);
        this.start = start;
        this.end = end;
        this.textColor = textColor;
        this.borderColor = borderColor;

        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                armed = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                armed = true;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                armed = false;
                repaint();
            }
        });
    }

    /** 主操作按钮：蓝紫渐变 */
    public static GradientButton primary(String text) {
        return new GradientButton(text, UiTheme.PRIMARY, UiTheme.PURPLE, Color.WHITE, null);
    }

    /** 强调按钮：暖橙渐变 */
    public static GradientButton accent(String text) {
        return new GradientButton(text, UiTheme.ACCENT, UiTheme.CORAL, Color.WHITE, null);
    }

    /** 危险按钮：红色渐变 */
    public static GradientButton danger(String text) {
        return new GradientButton(text, new Color(0xFF6B6B), new Color(0xE74C3C), Color.WHITE, null);
    }

    /** 次要按钮：浅色渐变 + 细描边 */
    public static GradientButton secondary(String text) {
        return new GradientButton(text, Color.WHITE, new Color(0xE9EDFB), UiTheme.TEXT_MAIN, new Color(0xC9D4F5));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        Shape clip = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, arc, arc);

        Color s = start;
        Color e = end;
        if (!isEnabled()) {
            s = new Color(0xE3E6EF);
            e = new Color(0xD7DBE8);
        } else if (armed) {
            s = darken(start, 22);
            e = darken(end, 22);
        } else if (hovered) {
            s = brighten(start, 14);
            e = brighten(end, 14);
        }

        g2.setPaint(new LinearGradientPaint(0, 0, 0, h, new float[]{0f, 1f}, new Color[]{s, e}));
        g2.fill(clip);

        if (borderColor != null) {
            g2.setColor(borderColor);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(clip);
        }

        // 文字
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        String text = getText();
        int tw = fm.stringWidth(text);
        int tx = (w - tw) / 2;
        int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
        if (armed) {
            tx += 1;
            ty += 1;
        }
        g2.setColor(isEnabled() ? textColor : new Color(0x9AA0B4));
        g2.drawString(text, tx, ty);

        g2.dispose();
    }

    private static Color brighten(Color c, int amount) {
        return new Color(
                Math.min(255, c.getRed() + amount),
                Math.min(255, c.getGreen() + amount),
                Math.min(255, c.getBlue() + amount));
    }

    private static Color darken(Color c, int amount) {
        return new Color(
                Math.max(0, c.getRed() - amount),
                Math.max(0, c.getGreen() - amount),
                Math.max(0, c.getBlue() - amount));
    }
}
