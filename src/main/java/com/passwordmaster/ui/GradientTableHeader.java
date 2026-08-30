package com.passwordmaster.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;

/**
 * 渐变表头渲染器（仅表现层）
 * 表头蓝紫渐变背景 + 白色粗体文字，统一应用到主表格与导入预览表格。
 */
public final class GradientTableHeader {

    private GradientTableHeader() {
    }

    public static void apply(JTable table) {
        JTableHeader header = table.getTableHeader();
        header.setDefaultRenderer(new HeaderRenderer());
        header.setPreferredSize(new Dimension(0, 34));
        header.setReorderingAllowed(false);
    }

    private static class HeaderRenderer extends DefaultTableCellRenderer {

        private final Color start = UiTheme.PRIMARY;
        private final Color end = UiTheme.PURPLE;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            l.setHorizontalAlignment(SwingConstants.CENTER);
            l.setForeground(Color.WHITE);
            l.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
            l.setOpaque(false);
            return l;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, start, getWidth(), 0, end));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
