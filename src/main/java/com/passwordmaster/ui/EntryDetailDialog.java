package com.passwordmaster.ui;

import com.passwordmaster.model.Entry;
import com.passwordmaster.service.PasswordService;
import com.passwordmaster.util.ClipboardSafe;
import com.passwordmaster.util.ImageUtil;

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
    private String plainPassword = null;
    private boolean showPassword = false;

    public EntryDetailDialog(Window owner, Entry entry, PasswordService service, SecretKey key) {
        super(owner, "条目详情 - " + nullToEmpty(entry.getPlatform()), ModalityType.APPLICATION_MODAL);
        this.entry = entry;
        this.service = service;
        this.key = key;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel main = new JPanel(new BorderLayout(10, 10));
        main.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JPanel info = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        addInfoRow(info, gbc, row++, "分类：", nullToEmpty(entry.getCategory()));
        addInfoRow(info, gbc, row++, "平台：", nullToEmpty(entry.getPlatform()));
        addInfoRow(info, gbc, row++, "账号：", nullToEmpty(entry.getAccount()));

        // 密码行：默认隐藏，点按钮切换
        gbc.gridx = 0;
        gbc.gridy = row;
        info.add(new JLabel("密码："), gbc);
        JPanel pwdPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        passwordLabel.setFont(new Font("Consolas", Font.PLAIN, 13));
        passwordLabel.setText("••••••••");
        JButton toggleBtn = new JButton("显示");
        toggleBtn.addActionListener(e -> togglePassword());
        pwdPanel.add(passwordLabel);
        pwdPanel.add(toggleBtn);
        // 复制密码：经 ClipboardSafe 复制，30 秒后自动清除剪贴板
        JButton copyBtn = new JButton("复制");
        copyBtn.addActionListener(e -> copyPassword());
        pwdPanel.add(copyBtn);
        gbc.gridx = 1;
        info.add(pwdPanel, gbc);
        row++;

        addInfoRow(info, gbc, row++, "手机号：", nullToEmpty(entry.getPhone()));
        addInfoRow(info, gbc, row++, "邮箱：", nullToEmpty(entry.getEmail()));
        addInfoRow(info, gbc, row++, "备注：", nullToEmpty(entry.getNote()));
        addInfoRow(info, gbc, row++, "同步状态：", nullToEmpty(entry.getSyncStatus()));

        main.add(info, BorderLayout.WEST);

        // 右侧：手势轨迹 + 图片
        JPanel right = new JPanel(new BorderLayout(8, 8));

        // 手势轨迹图（有手势则一直显示）
        if (entry.getGestureSeq() != null && !entry.getGestureSeq().trim().isEmpty()) {
            JPanel gestureBox = new JPanel(new BorderLayout());
            gestureBox.setBorder(BorderFactory.createTitledBorder("手势轨迹"));
            GesturePanel gp = new GesturePanel(false);
            gp.setSequence(entry.getGestureSeq());
            gp.setPreferredSize(new Dimension(180, 180));
            gestureBox.add(gp, BorderLayout.CENTER);
            right.add(gestureBox, BorderLayout.CENTER);
        }

        // 图片预览
        if (entry.getImagePath() != null && !entry.getImagePath().isEmpty()) {
            Path imgPath = ImageUtil.resolveImagePath(entry.getImagePath());
            if (Files.exists(imgPath)) {
                JPanel imgBox = new JPanel(new BorderLayout());
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
                    right.add(imgBox, BorderLayout.SOUTH);
                }
            }
        }

        if (right.getComponentCount() > 0) {
            main.add(right, BorderLayout.EAST);
        }

        add(main, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dispose());
        bottom.add(closeBtn);
        add(bottom, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
        setMinimumSize(new Dimension(480, 260));
    }

    private void addInfoRow(JPanel panel, GridBagConstraints gbc, int row, String label, String value) {
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
    }

    private void togglePassword() {
        showPassword = !showPassword;
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
        if (plainPassword.isEmpty()) {
            JOptionPane.showMessageDialog(this, "该条目未设置密码", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ClipboardSafe.copySecret(plainPassword);
        JOptionPane.showMessageDialog(this, "密码已复制，30 秒后自动从剪贴板清除", "复制成功", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
