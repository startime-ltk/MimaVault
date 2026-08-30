package com.passwordmaster.ui;

import com.passwordmaster.service.PasswordHealthChecker;
import com.passwordmaster.service.PasswordHealthChecker.HealthIssue;
import com.passwordmaster.service.PasswordHealthChecker.ReportResult;
import com.passwordmaster.service.PasswordHealthChecker.Severity;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.Consumer;

/**
 * 密码健康检测 - 安全报告对话框（糖果可爱风，区别于主界面蓝紫渐变）
 *
 * <p>马卡龙糖果配色：亮绿 / 天蓝 / 蜜桃橙 / 樱粉，米白背景 #FFF8F0；
 * 大圆角卡片（20px+）、胖按钮（大圆角、粗描边、hover 变色）、渐变艺术字标题。
 * 结果分色：严重红 #E57373、警告橙 #FFB74D、安全绿 #81C784。
 * 检测数据来自内存明文，本窗口不写盘、不打印。
 */
public class SecurityReportDialog extends JDialog {

    // ---------- 糖果配色 ----------
    private static final Color BG = new Color(0xFFF8F0);
    private static final Color GREEN = new Color(0x7ED321);
    private static final Color GREEN_LIGHT = new Color(0xB8E986);
    private static final Color GREEN_DEEP = new Color(0x5DAF1E);
    private static final Color BLUE = new Color(0x4FC3F7);
    private static final Color BLUE_LIGHT = new Color(0xB3E5FC);
    private static final Color ORANGE = new Color(0xFFB74D);
    private static final Color ORANGE_LIGHT = new Color(0xFFE0B2);
    private static final Color PINK = new Color(0xF48FB1);
    private static final Color PINK_LIGHT = new Color(0xF8BBD0);
    private static final Color CRIT = new Color(0xE57373);
    private static final Color WARN = new Color(0xFFB74D);
    private static final Color SAFE = new Color(0x81C784);
    private static final Color TEXT_MAIN = new Color(0x5D4037);
    private static final Color TEXT_SUB = new Color(0xA1887F);

    private final ReportResult result;
    private final List<HealthIssue> issues;
    private final Consumer<Long> locator;
    private JTable table;
    private CandyButton locateBtn;

    public SecurityReportDialog(MainFrame owner, ReportResult result, Consumer<Long> locator) {
        super(owner, "密码健康检测", true);
        this.result = result;
        this.issues = result.issues;
        this.locator = locator;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        setSize(720, 640);
        setLocationRelativeTo(owner);
        setIconImage(UiTheme.getAppIcon());

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        setContentPane(root);

        root.add(buildTitle(), BorderLayout.NORTH);

        JPanel mid = new JPanel(new BorderLayout(0, 14));
        mid.setOpaque(false);
        mid.add(buildScoreCard(), BorderLayout.NORTH);
        mid.add(buildIssueCard(), BorderLayout.CENTER);
        root.add(mid, BorderLayout.CENTER);

        root.add(buildFooter(), BorderLayout.SOUTH);
    }

    /** 渐变艺术字标题 + 副标题 */
    private JPanel buildTitle() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);

        ArtTextLabel title = new ArtTextLabel("安全报告", 30);
        title.setGradient(ORANGE, PINK);
        title.setStroke(Color.WHITE, 2.0f);
        title.setShadow(new Color(0xF8BBD0, true), 2);
        title.setPreferredSize(new Dimension(260, 46));
        p.add(title, BorderLayout.WEST);

        JLabel sub = new JLabel("离线检测 · 数据不出本机", SwingConstants.RIGHT);
        sub.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        sub.setForeground(TEXT_SUB);
        p.add(sub, BorderLayout.EAST);
        return p;
    }

    /** 顶部总分大卡片 + 等级徽章 */
    private JPanel buildScoreCard() {
        RoundedPanel card = new RoundedPanel(26);
        card.setFill(Color.WHITE);
        card.setBorderColor(new Color(0xFFE0B2));
        card.setLayout(new BorderLayout(18, 0));
        card.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));

        // 左侧大分数
        JLabel scoreLabel = new JLabel(String.valueOf(result.score));
        scoreLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 54));
        scoreLabel.setForeground(levelColor());
        scoreLabel.setHorizontalAlignment(SwingConstants.CENTER);
        scoreLabel.setPreferredSize(new Dimension(150, 64));
        card.add(scoreLabel, BorderLayout.WEST);

        // 右侧：等级徽章 + 说明
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setAlignmentX(LEFT_ALIGNMENT);

        JLabel badge = new JLabel(levelBadgeText());
        badge.setOpaque(true);
        badge.setBackground(levelColor());
        badge.setForeground(Color.WHITE);
        badge.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        badge.setBorder(BorderFactory.createEmptyBorder(6, 18, 6, 18));
        badge.setAlignmentX(LEFT_ALIGNMENT);
        right.add(badge);

        right.add(Box.createVerticalStrut(8));
        JLabel desc = new JLabel(levelDesc());
        desc.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        desc.setForeground(TEXT_MAIN);
        desc.setAlignmentX(LEFT_ALIGNMENT);
        right.add(desc);

        right.add(Box.createVerticalStrut(6));
        JLabel stats = new JLabel("共 " + result.totalCount + " 条 · 弱口令 " + result.weakCount
                + " · 重复 " + result.duplicateCount + " · 泄露 " + result.breachedCount);
        stats.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        stats.setForeground(TEXT_SUB);
        stats.setAlignmentX(LEFT_ALIGNMENT);
        right.add(stats);

        card.add(right, BorderLayout.CENTER);
        return card;
    }

    private Color levelColor() {
        return result.level == Severity.CRITICAL ? CRIT : result.level == Severity.WARNING ? WARN : SAFE;
    }

    private String levelBadgeText() {
        return result.level == Severity.CRITICAL ? "需改进" : result.level == Severity.WARNING ? "良好" : "优秀";
    }

    private String levelDesc() {
        return result.level == Severity.CRITICAL
                ? "发现高危问题，请优先处理泄露与弱密码。"
                : result.level == Severity.WARNING
                ? "存在一些隐患，建议逐步整改。"
                : "太棒了！密码库很健康，继续保持。";
    }

    /** 问题列表卡片 */
    private JPanel buildIssueCard() {
        RoundedPanel card = new RoundedPanel(22);
        card.setFill(Color.WHITE);
        card.setBorderColor(new Color(0xFFE0B2));
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel cap = new JLabel("问题清单（点击行可定位条目）");
        cap.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        cap.setForeground(TEXT_MAIN);
        cap.setBorder(BorderFactory.createEmptyBorder(4, 6, 8, 6));
        card.add(cap, BorderLayout.NORTH);

        if (issues.isEmpty()) {
            JLabel empty = new JLabel("✓  没有发现问题，所有密码都很健康！", SwingConstants.CENTER);
            empty.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
            empty.setForeground(SAFE);
            empty.setBorder(BorderFactory.createEmptyBorder(60, 0, 60, 0));
            card.add(empty, BorderLayout.CENTER);
            return card;
        }

        HealthTableModel model = new HealthTableModel();
        for (HealthIssue issue : issues) {
            model.addRow(new Object[]{
                    displayName(issue),
                    issue.typeText,
                    issue.passwordPreview,
                    issue.message
            });
            model.severities.add(issue.severity);
        }

        table = new JTable(model) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table.setRowHeight(36);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(8, 4));
        table.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(new Color(0xFFF3E0));
        table.setSelectionForeground(TEXT_MAIN);
        table.setFillsViewportHeight(true);

        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(280);

        table.getColumnModel().getColumn(1).setCellRenderer(new SeverityRenderer(true));
        table.getColumnModel().getColumn(2).setCellRenderer(new SeverityRenderer(false));
        table.getColumnModel().getColumn(0).setCellRenderer(new SeverityRenderer(false));

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    locateSelected();
                }
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            locateBtn.setEnabled(table.getSelectedRow() >= 0);
        });

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        sp.getViewport().setBackground(Color.WHITE);
        card.add(sp, BorderLayout.CENTER);
        return card;
    }

    /** 条目显示名：平台 / 账号 */
    private String displayName(HealthIssue issue) {
        String p = issue.platform == null || issue.platform.isEmpty() ? "（未命名）" : issue.platform;
        String a = issue.account == null || issue.account.isEmpty() ? "" : " / " + issue.account;
        return p + a;
    }

    /** 底部胖按钮：定位 + 关闭 */
    private JPanel buildFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 4));
        p.setOpaque(false);

        locateBtn = new CandyButton("定位条目", GREEN, GREEN_DEEP, Color.WHITE, new Color(0x4E8E17));
        locateBtn.setEnabled(false);
        locateBtn.addActionListener(e -> locateSelected());
        p.add(locateBtn);

        CandyButton closeBtn = new CandyButton("关 闭", PINK, new Color(0xE57373), Color.WHITE, new Color(0xCE5A77));
        closeBtn.addActionListener(e -> dispose());
        p.add(closeBtn);

        return p;
    }

    private void locateSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= issues.size()) {
            JOptionPane.showMessageDialog(this, "请先在列表中选中一条问题记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (locator != null) {
            locator.accept(issues.get(row).entryId);
        }
        dispose();
    }

    /** 表格模型：附带每行严重程度 */
    private static final class HealthTableModel extends DefaultTableModel {
        private final java.util.List<Severity> severities = new java.util.ArrayList<>();

        HealthTableModel() {
            super(new String[]{"条目", "问题", "密码预览", "说明"}, 0);
        }

        Severity severityAt(int row) {
            return row >= 0 && row < severities.size() ? severities.get(row) : Severity.OK;
        }
    }

    /** 行渲染器：按严重程度着色（非选中态） */
    private static final class SeverityRenderer extends DefaultTableCellRenderer {
        private final boolean bold;

        SeverityRenderer(boolean bold) {
            this.bold = bold;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
            if (t.getModel() instanceof HealthTableModel) {
                Severity sev = ((HealthTableModel) t.getModel()).severityAt(row);
                if (!isSelected) {
                    c.setBackground(bgOf(sev));
                    c.setForeground(colorOf(sev));
                    c.setFont(new Font("Microsoft YaHei", bold ? Font.BOLD : Font.PLAIN, 13));
                }
            }
            return c;
        }

        private static Color bgOf(Severity sev) {
            return sev == Severity.CRITICAL ? new Color(0xFDEAEA) : sev == Severity.WARNING ? new Color(0xFFF3E0) : new Color(0xE8F5E9);
        }

        private static Color colorOf(Severity sev) {
            return sev == Severity.CRITICAL ? new Color(0xC62828) : sev == Severity.WARNING ? new Color(0xE65100) : new Color(0x2E7D32);
        }
    }

    /** 糖果胖按钮：大圆角 24、粗描边 2.5f、渐变填充、hover 变亮 / 按下变暗 */
    private static final class CandyButton extends JButton {

        private final Color start;
        private final Color end;
        private final Color textColor;
        private final Color borderColor;
        private final int arc = 24;
        private boolean hovered = false;
        private boolean armed = false;

        CandyButton(String text, Color start, Color end, Color textColor, Color borderColor) {
            super(text);
            this.start = start;
            this.end = end;
            this.textColor = textColor;
            this.borderColor = borderColor;

            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(10, 26, 10, 26));
            setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
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
                s = new Color(0xEDE4DA);
                e = new Color(0xE0D2C4);
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
                g2.setStroke(new BasicStroke(2.5f));
                g2.draw(clip);
            }

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
            g2.setColor(isEnabled() ? textColor : new Color(0xBFAFA3));
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
}
