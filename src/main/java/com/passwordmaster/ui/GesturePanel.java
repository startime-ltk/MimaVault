package com.passwordmaster.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * 3x3 九宫格手势面板
 * - 录入模式：鼠标点击圆点连线，自动记录数字序列（1-9，左上到右下）
 * - 显示模式：按序列绘制轨迹线，每段线中间绘制小三角箭头指示方向
 */
public class GesturePanel extends JPanel {

    public static final int SIZE = 3;

    private final boolean editable;
    private final List<Integer> sequence = new ArrayList<>();
    private final Point[][] points = new Point[SIZE][SIZE];
    private boolean dragging = false;

    /** 录入模式 */
    public GesturePanel(boolean editable) {
        this.editable = editable;
        setPreferredSize(new Dimension(240, 240));
        setBackground(Color.WHITE);
        if (editable) {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    dragging = true;
                    handleClick(e.getPoint());
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragging = false;
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragging) {
                        handleClick(e.getPoint());
                    }
                }
            });
        }
    }

    private void handleClick(Point p) {
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if (points[row][col] != null && points[row][col].distance(p) <= 24) {
                    int num = row * SIZE + col + 1;
                    if (!sequence.contains(num)) {
                        sequence.add(num);
                        repaint();
                    }
                    return;
                }
            }
        }
    }

    /** 设置显示序列（显示模式） */
    public void setSequence(String seqText) {
        sequence.clear();
        if (seqText != null && !seqText.trim().isEmpty()) {
            for (String part : seqText.split("[,，\\s]+")) {
                try {
                    int n = Integer.parseInt(part.trim());
                    if (n >= 1 && n <= 9) {
                        sequence.add(n);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        repaint();
    }

    /** 获取当前序列文本，如 "1,4,7,8,9" */
    public String getSequenceText() {
        StringBuilder sb = new StringBuilder();
        for (Integer n : sequence) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(n);
        }
        return sb.toString();
    }

    /** 清空序列 */
    public void clearSequence() {
        sequence.clear();
        repaint();
    }

    public boolean hasSequence() {
        return !sequence.isEmpty();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int panelSize = Math.min(getWidth(), getHeight());
        int margin = 30;
        int step = (panelSize - margin * 2) / (SIZE - 1);
        int radius = 12;

        // 计算圆点坐标
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                int x = margin + col * step;
                int y = margin + row * step;
                points[row][col] = new Point(x, y);
            }
        }

        // 空序列提示
        if (sequence.isEmpty()) {
            g2.setColor(new Color(150, 150, 150));
            g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g2.drawString(editable ? "点击圆点绘制手势（可连点/拖动）" : "暂无手势", margin + 10, margin + step + 5);
        }

        // 绘制连线（带方向箭头）
        if (sequence.size() >= 2) {
            g2.setColor(new Color(33, 118, 255));
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < sequence.size() - 1; i++) {
                Point p1 = toPoint(sequence.get(i));
                Point p2 = toPoint(sequence.get(i + 1));
                g2.drawLine(p1.x, p1.y, p2.x, p2.y);
                drawArrow(g2, p1, p2);
            }
        }

        // 绘制圆点
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                int num = row * SIZE + col + 1;
                boolean active = sequence.contains(num);
                int x = margin + col * step;
                int y = margin + row * step;
                if (active) {
                    g2.setColor(new Color(33, 118, 255));
                    g2.fillOval(x - radius, y - radius, radius * 2, radius * 2);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(String.valueOf(num), x - fm.stringWidth(String.valueOf(num)) / 2,
                            y + fm.getAscent() / 2 - 2);
                } else {
                    g2.setColor(new Color(33, 118, 255));
                    g2.drawOval(x - radius, y - radius, radius * 2, radius * 2);
                    g2.setColor(new Color(120, 120, 120));
                    g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(String.valueOf(num), x - fm.stringWidth(String.valueOf(num)) / 2,
                            y + fm.getAscent() / 2 - 2);
                }
            }
        }
    }

    private Point toPoint(int num) {
        int row = (num - 1) / SIZE;
        int col = (num - 1) % SIZE;
        return points[row][col];
    }

    /** 在线段中点绘制小三角箭头（AffineTransform 旋转） */
    private void drawArrow(Graphics2D g2, Point from, Point to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double len = Math.hypot(dx, dy);
        if (len < 1) {
            return;
        }
        double midX = from.x + dx / 2.0;
        double midY = from.y + dy / 2.0;
        double angle = Math.atan2(dy, dx);

        int arrowSize = 9;
        AffineTransform old = g2.getTransform();
        g2.translate(midX, midY);
        g2.rotate(angle);
        int[] xs = {arrowSize, -arrowSize / 2, -arrowSize / 2};
        int[] ys = {0, -arrowSize / 2, arrowSize / 2};
        g2.fillPolygon(xs, ys, 3);
        g2.setTransform(old);
    }
}
