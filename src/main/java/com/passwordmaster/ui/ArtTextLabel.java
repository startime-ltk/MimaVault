package com.passwordmaster.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;

/**
 * 艺术字标题组件（仅表现层）
 * 使用 Graphics2D 绘制：渐变文字 + 白色描边 + 半透明投影，提升立体感。
 * 用于主界面标题「密码大师 PasswordMaster」与登录窗口标题。
 */
public class ArtTextLabel extends JLabel {

    private Color gradStart = UiTheme.PRIMARY;
    private Color gradEnd = UiTheme.PURPLE;
    private Color strokeColor = Color.WHITE;
    private float strokeWidth = 1.6f;
    private Color shadowColor = new Color(0, 0, 0, 60);
    private int shadowOffset = 2;

    /** 创建艺术字标题（默认水平居中） */
    public ArtTextLabel(String text, int fontSize) {
        super(text, SwingConstants.CENTER);
        setFont(new Font("Microsoft YaHei", Font.BOLD, fontSize));
        setForeground(UiTheme.TEXT_MAIN);
        setOpaque(false);
    }

    public ArtTextLabel setGradient(Color start, Color end) {
        this.gradStart = start;
        this.gradEnd = end;
        return this;
    }

    public ArtTextLabel setStroke(Color color, float width) {
        this.strokeColor = color;
        this.strokeWidth = width;
        return this;
    }

    public ArtTextLabel setShadow(Color color, int offset) {
        this.shadowColor = color;
        this.shadowOffset = offset;
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        String text = getText();
        if (text == null || text.isEmpty()) {
            g2.dispose();
            return;
        }

        Font font = getFont();
        FontMetrics fm = g2.getFontMetrics(font);
        int textWidth = fm.stringWidth(text);
        int x = Math.max(0, (getWidth() - textWidth) / 2);
        int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();

        // 1. 投影（偏移绘制）
        g2.setColor(shadowColor);
        g2.setFont(font);
        g2.drawString(text, x + shadowOffset, y + shadowOffset);

        // 2. 描边 + 渐变填充（基于 TextLayout 轮廓，保证渐变沿笔画）
        TextLayout layout = new TextLayout(text, font, g2.getFontRenderContext());
        Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(x, y));

        g2.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(strokeColor);
        g2.draw(outline);

        Rectangle2D bounds = outline.getBounds2D();
        float gradX2 = Math.max((float) bounds.getMaxX(), (float) bounds.getMinX() + 1);
        g2.setPaint(new LinearGradientPaint(
                (float) bounds.getMinX(), (float) bounds.getMinY(),
                gradX2, (float) bounds.getMinY(),
                new float[]{0f, 1f}, new Color[]{gradStart, gradEnd}));
        g2.fill(outline);

        g2.dispose();
    }
}
