package com.mimavault.ui;

import com.mimavault.model.Entry;
import com.mimavault.service.PasswordService;
import com.mimavault.util.ClipboardSafe;
import com.mimavault.util.ImageUtil;
import com.mimavault.util.PasswordStrengthUtil;

import javax.crypto.SecretKey;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 查看详情对话框
 * 显示平台/账号/手机/邮箱/备注/密码（默认隐藏可切换）
 * + 手势轨迹图（有手势则一直显示，关闭才消失）
 * + 图片预览（有图片显示缩略图，点击查看大图）
 */
public class EntryDetailDialog extends JDialog {

    private final PasswordService service;
    private final SecretKey key;
    private final Entry entry;
    private final JLabel passwordLabel = new JLabel();
    private final JLabel copyHint = new JLabel(" ");
    private final JPanel pwdPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    private final JPanel rightPanel = new JPanel(new BorderLayout(8, 8));
    private JLabel categoryLabel = new JLabel();
    private JLabel platformLabel = new JLabel();
    private JLabel accountLabel = new JLabel();
    private JLabel phoneLabel = new JLabel();
    private JLabel emailLabel = new JLabel();
    private JLabel noteLabel = new JLabel();
    private JLabel syncLabel = new JLabel();
    private GradientButton platformCopyBtn;
    private GradientButton toggleBtn;
    private Timer hintTimer;
    private String plainPassword = null;
    private boolean showPassword = false;
    /** 本次打开详情期间是否改动过条目（编辑保存 / 恢复历史版本），供主界面决定是否刷新 */
    private boolean changed = false;

    public EntryDetailDialog(Window owner, Entry entry, PasswordService service, SecretKey key) {
        super(owner, "条目详情 - " + nullToEmpty(entry.getPlatform()), ModalityType.APPLICATION_MODAL);
        this.entry = entry;
        this.service = service;
        this.key = key;
        setIconImage(UiTheme.getAppIcon());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        RoundedPanel main = new RoundedPanel(16);
        main.setLayout(new BorderLayout(10, 10));
        main.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JPanel info = new JPanel(new GridBagLayout());
        info.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        categoryLabel = addInfoRow(info, gbc, row++, "分类：", nullToEmpty(entry.getCategory()));
        // 平台行（网站信息存于 platform 字段）：文本 + 复制按钮，为空时置灰不可复制
        platformCopyBtn = GradientButton.secondary("复制");
        platformLabel = addCopyValueRow(info, gbc, row++, "平台：", nullToEmpty(entry.getPlatform()), platformCopyBtn);
        platformCopyBtn.addActionListener(e -> copyField(platformLabel.getText(), "平台"));
        if (nullToEmpty(entry.getPlatform()).isEmpty()) {
            platformCopyBtn.setEnabled(false);
        }
        // 账号行：文本 + 复制按钮
        GradientButton copyAcctBtn = GradientButton.secondary("复制");
        accountLabel = addCopyValueRow(info, gbc, row++, "账号：", nullToEmpty(entry.getAccount()), copyAcctBtn);
        copyAcctBtn.addActionListener(e -> copyField(accountLabel.getText(), "账号"));

        // 密码行：默认隐藏，点按钮切换
        gbc.gridx = 0;
        gbc.gridy = row;
        info.add(new JLabel("密码："), gbc);
        pwdPanel.setOpaque(false);
        passwordLabel.setFont(new Font("Consolas", Font.PLAIN, 13));
        passwordLabel.setText("••••••••");
        toggleBtn = GradientButton.secondary("显示");
        toggleBtn.addActionListener(e -> togglePassword());
        pwdPanel.add(passwordLabel);
        pwdPanel.add(toggleBtn);
        // 复制密码：经 ClipboardSafe 复制，30 秒后自动清除剪贴板
        GradientButton copyBtn = GradientButton.accent("复制");
        copyBtn.addActionListener(e -> copyPassword());
        pwdPanel.add(copyBtn);
        // 强度徽章：进入详情即解密计算，掩码/明文状态均显示
        String decrypted = service.decryptPassword(entry, key);
        if (!decrypted.isEmpty()) {
            plainPassword = decrypted;
            pwdPanel.add(new StrengthBadge(PasswordStrengthUtil.evaluate(decrypted)));
        } else {
            passwordLabel.setText("（未设置）");
        }
        gbc.gridx = 1;
        info.add(pwdPanel, gbc);
        row++;

        phoneLabel = addInfoRow(info, gbc, row++, "手机号：", nullToEmpty(entry.getPhone()));
        // 邮箱行：文本 + 复制按钮
        GradientButton copyEmailBtn = GradientButton.secondary("复制");
        emailLabel = addCopyValueRow(info, gbc, row++, "邮箱：", nullToEmpty(entry.getEmail()), copyEmailBtn);
        copyEmailBtn.addActionListener(e -> copyField(emailLabel.getText(), "邮箱"));
        noteLabel = addInfoRow(info, gbc, row++, "备注：", nullToEmpty(entry.getNote()));
        syncLabel = addInfoRow(info, gbc, row++, "同步状态：", nullToEmpty(entry.getSyncStatus()));

        main.add(info, BorderLayout.WEST);

        // 右侧：手势轨迹 + 图片
        rightPanel.setOpaque(false);
        refreshRightPanel();
        if (rightPanel.getComponentCount() > 0) {
            main.add(rightPanel, BorderLayout.EAST);
        }

        add(main, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setOpaque(false);
        copyHint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        copyHint.setForeground(new Color(46, 139, 87));
        bottom.add(copyHint, BorderLayout.WEST);
        // 底部按钮：历史版本 + 编辑 + 关闭
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnPanel.setOpaque(false);
        GradientButton historyBtn = GradientButton.secondary("历史版本");
        historyBtn.setToolTipText("查看该条目的历史密码，可恢复到任意历史版本");
        historyBtn.addActionListener(e -> onHistory());
        btnPanel.add(historyBtn);
        GradientButton editBtn = GradientButton.primary("编辑");
        editBtn.addActionListener(e -> onEdit());
        btnPanel.add(editBtn);
        GradientButton closeBtn = GradientButton.primary("关闭");
        closeBtn.addActionListener(e -> dispose());
        btnPanel.add(closeBtn);
        bottom.add(btnPanel, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
        setMinimumSize(new Dimension(480, 260));
    }

    /** 编辑当前条目：复用 EntryEditDialog 保存链路（加密/留空校验不变），保存后刷新详情显示 */
    private void onEdit() {
        EntryEditDialog dlg = new EntryEditDialog(this, entry, key);
        dlg.setVisible(true);
        if (dlg.isSaved()) {
            service.updateEntry(dlg.getEntry(), dlg.getPlainPassword(), key, dlg.isKeepOldPassword());
            changed = true;
            refreshFromEntry();
        }
    }

    /** 打开历史版本对话框：可查看/复制/恢复改密前的旧密码，恢复后同步刷新详情 */
    private void onHistory() {
        PasswordHistoryDialog dlg = new PasswordHistoryDialog(this, entry, service, key);
        dlg.setVisible(true);
        if (dlg.isRestored()) {
            changed = true;
            refreshFromEntry();
        }
    }

    /** 本次打开期间条目是否被改动（编辑保存或恢复历史版本） */
    public boolean isChanged() {
        return changed;
    }

    /** 编辑保存后刷新详情界面（entry 为同一对象引用，字段已就地更新；密码重新解密） */
    private void refreshFromEntry() {
        setTitle("条目详情 - " + nullToEmpty(entry.getPlatform()));
        categoryLabel.setText(nullToEmpty(entry.getCategory()));
        platformLabel.setText(nullToEmpty(entry.getPlatform()));
        // 平台（网站）复制按钮：编辑后按是否为空同步置灰/启用
        if (platformCopyBtn != null) {
            platformCopyBtn.setEnabled(!nullToEmpty(entry.getPlatform()).isEmpty());
        }
        accountLabel.setText(nullToEmpty(entry.getAccount()));
        phoneLabel.setText(nullToEmpty(entry.getPhone()));
        emailLabel.setText(nullToEmpty(entry.getEmail()));
        noteLabel.setText(nullToEmpty(entry.getNote()));
        syncLabel.setText(nullToEmpty(entry.getSyncStatus()));

        // 重置密码展示：重新解密、默认掩码
        showPassword = false;
        toggleBtn.setText("显示");
        plainPassword = null;
        for (Component c : pwdPanel.getComponents()) {
            if (c instanceof StrengthBadge) {
                pwdPanel.remove(c);
            }
        }
        String decrypted = service.decryptPassword(entry, key);
        if (!decrypted.isEmpty()) {
            plainPassword = decrypted;
            pwdPanel.add(new StrengthBadge(PasswordStrengthUtil.evaluate(decrypted)));
            passwordLabel.setText("••••••••");
        } else {
            passwordLabel.setText("（未设置）");
        }
        pwdPanel.revalidate();
        pwdPanel.repaint();

        refreshRightPanel();
        pack();
    }

    /** 重建右侧手势轨迹 + 图片预览区（编辑后同步刷新） */
    private void refreshRightPanel() {
        rightPanel.removeAll();

        // 手势轨迹图（有手势则一直显示）
        if (entry.getGestureSeq() != null && !entry.getGestureSeq().trim().isEmpty()) {
            JPanel gestureBox = new JPanel(new BorderLayout());
            gestureBox.setOpaque(false);
            gestureBox.setBorder(BorderFactory.createTitledBorder("手势轨迹"));
            GesturePanel gp = new GesturePanel(false);
            gp.setSequence(entry.getGestureSeq());
            gp.setPreferredSize(new Dimension(180, 180));
            gestureBox.add(gp, BorderLayout.CENTER);
            rightPanel.add(gestureBox, BorderLayout.CENTER);
        }

        // 图片预览
        if (entry.getImagePath() != null && !entry.getImagePath().isEmpty()) {
            Path imgPath = ImageUtil.resolveImagePath(entry.getImagePath());
            if (Files.exists(imgPath)) {
                JPanel imgBox = new JPanel(new BorderLayout());
                imgBox.setOpaque(false);
                imgBox.setBorder(BorderFactory.createTitledBorder("截图附件"));
                ImageIcon icon = ImageUtil.loadScaledIcon(imgPath, 150);
                if (icon != null) {
                    JLabel imgLabel = new JLabel(icon, SwingConstants.CENTER);
                    imgLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    imgLabel.setToolTipText("点击查看大图");
                    imgLabel.addMouseListener(new java.awt.event.MouseAdapter() {
                        @Override
                        public void mouseClicked(java.awt.event.MouseEvent e) {
                            new ImageViewerDialog(EntryDetailDialog.this, "图片预览", imgPath).setVisible(true);
                        }
                    });
                    imgBox.add(imgLabel, BorderLayout.CENTER);
                    rightPanel.add(imgBox, BorderLayout.SOUTH);
                }
            }
        }

        rightPanel.revalidate();
        rightPanel.repaint();
    }

    private JLabel addInfoRow(JPanel panel, GridBagConstraints gbc, int row, String label, String value) {
        gbc.gridx = 0;
        gbc.gridy = row;
        JLabel l = new JLabel(label);
        l.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        l.setForeground(new Color(100, 100, 100));
        panel.add(l, gbc);
        gbc.gridx = 1;
        JLabel v = new JLabel(value);
        v.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        panel.add(v, gbc);
        return v;
    }

    /** 值行（文本 + 复制按钮并排），返回值 Label 便于后续读取 */
    private JLabel addCopyValueRow(JPanel panel, GridBagConstraints gbc, int row, String label, String value, GradientButton copyBtn) {
        gbc.gridx = 0;
        gbc.gridy = row;
        JLabel l = new JLabel(label);
        l.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        l.setForeground(new Color(100, 100, 100));
        panel.add(l, gbc);
        JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        cell.setOpaque(false);
        JLabel v = new JLabel(value);
        v.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        cell.add(v);
        cell.add(copyBtn);
        gbc.gridx = 1;
        panel.add(cell, gbc);
        return v;
    }

    private void togglePassword() {
        showPassword = !showPassword;
        toggleBtn.setText(showPassword ? "隐藏" : "显示");
        if (showPassword) {
            if (plainPassword == null) {
                plainPassword = service.decryptPassword(entry, key);
            }
            passwordLabel.setText(plainPassword.isEmpty() ? "（未设置）" : plainPassword);
        } else {
            passwordLabel.setText("••••••••");
        }
    }

    /** 复制密码到剪贴板（30 秒后自动清除） */
    private void copyPassword() {
        if (plainPassword == null) {
            plainPassword = service.decryptPassword(entry, key);
        }
        copyField(plainPassword, "密码");
    }

    /** 通用复制：空值提示，非空走 ClipboardSafe + 轻量状态提示 */
    private void copyField(String value, String fieldName) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            JOptionPane.showMessageDialog(this, "该条目未设置" + fieldName, "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ClipboardSafe.copySecret(v);
        showCopyHint(fieldName + "已复制，30 秒后自动清除剪贴板");
    }

    /** 底部状态标签短暂显示复制成功提示 */
    private void showCopyHint(String msg) {
        copyHint.setText(msg);
        copyHint.setForeground(new Color(46, 139, 87));
        if (hintTimer != null) {
            hintTimer.stop();
        }
        hintTimer = new Timer(3000, e -> copyHint.setText(" "));
        hintTimer.setRepeats(false);
        hintTimer.start();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 强度徽章：圆角背景 + 白字（弱=红 / 中=橙 / 强=绿），悬停显示评分依据 */
    private static class StrengthBadge extends JLabel {
        private final Color bg;
        private final int arc = 10;

        StrengthBadge(PasswordStrengthUtil.Result r) {
            super(r.level.getText());
            setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
            setForeground(Color.WHITE);
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
            switch (r.level) {
                case WEAK:
                    bg = new Color(0xE74C3C);
                    break;
                case MEDIUM:
                    bg = new Color(0xE67E22);
                    break;
                default:
                    bg = new Color(0x27AE60);
            }
            String detail = "<html>强度：" + r.level.getText() + "（得分 " + r.score + "/6）<br>"
                    + String.join("<br>", r.reasons) + "</html>";
            setToolTipText(detail);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
