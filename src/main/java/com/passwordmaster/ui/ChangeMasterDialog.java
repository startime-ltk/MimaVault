package com.passwordmaster.ui;

import com.passwordmaster.service.PasswordService;

import javax.crypto.SecretKey;
import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/**
 * 修改主密码对话框
 * - 需输入当前主密码验证身份（错误则拒绝）
 * - 输入并确认新主密码（少于 6 位时二次确认）
 * - 成功后由 PasswordService 校验旧密码 → 全库重加密 → 更新主密码哈希，
 *   返回新密钥供主界面替换内存中持有的旧密钥
 * <p>
 * 视觉：渐变背景 + 居中圆角卡片 + 艺术字标题 + 圆角输入框 + 渐变按钮（与登录界面一致）
 */
public class ChangeMasterDialog extends JDialog {

    private final PasswordService service;
    private final JPasswordField oldField = new JPasswordField(16);
    private final JPasswordField newField = new JPasswordField(16);
    private final JPasswordField confirmField = new JPasswordField(16);
    private final GradientButton okBtn = GradientButton.primary("确定");
    private final JLabel statusLabel = new JLabel(" ");

    /** 修改成功后的新密钥；未成功为 null */
    private SecretKey newKey = null;

    public ChangeMasterDialog(Window owner, PasswordService service) {
        super(owner, "修改主密码", ModalityType.APPLICATION_MODAL);
        this.service = service;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImage(UiTheme.getAppIcon());

        // 窗口背景渐变
        GradientPanel bg = new GradientPanel(UiTheme.BG_TOP, UiTheme.BG_BOTTOM);
        bg.setLayout(new GridBagLayout());
        setContentPane(bg);

        // 居中圆角卡片
        RoundedPanel card = new RoundedPanel(22);
        card.setLayout(new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(26, 34, 26, 34));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;

        // 艺术字标题
        ArtTextLabel title = new ArtTextLabel("修改主密码", 26);
        title.setPreferredSize(new Dimension(300, 40));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        card.add(title, gbc);
        gbc.gridwidth = 1;

        JLabel subtitle = new JLabel("修改后全部数据将使用新主密码重新加密", SwingConstants.CENTER);
        subtitle.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        subtitle.setForeground(UiTheme.TEXT_SUB);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        card.add(subtitle, gbc);
        gbc.gridwidth = 1;

        gbc.insets = new Insets(10, 8, 4, 8);
        gbc.gridx = 0;
        gbc.gridy = 2;
        card.add(new JLabel("当前主密码："), gbc);
        gbc.gridx = 1;
        oldField.setPreferredSize(new Dimension(200, 32));
        card.add(oldField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        card.add(new JLabel("新主密码："), gbc);
        gbc.gridx = 1;
        newField.setPreferredSize(new Dimension(200, 32));
        card.add(newField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        card.add(new JLabel("确认新密码："), gbc);
        gbc.gridx = 1;
        confirmField.setPreferredSize(new Dimension(200, 32));
        card.add(confirmField, gbc);

        JLabel hint = new JLabel("新主密码用于加密全部数据，请务必牢记，丢失无法找回");
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        hint.setForeground(UiTheme.TEXT_SUB);
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        card.add(hint, gbc);

        // 状态行（错误提示）
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statusLabel.setForeground(UiTheme.DANGER);
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        card.add(statusLabel, gbc);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        btnPanel.setOpaque(false);
        okBtn.addActionListener(e -> onOk());
        GradientButton cancelBtn = GradientButton.secondary("取消");
        cancelBtn.addActionListener(e -> dispose());
        btnPanel.add(okBtn);
        btnPanel.add(cancelBtn);
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(14, 8, 0, 8);
        card.add(btnPanel, gbc);

        // 卡片居中放入背景
        GridBagConstraints bgGbc = new GridBagConstraints();
        bg.add(card, bgGbc);

        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
        getRootPane().setDefaultButton(okBtn);
    }

    private void onOk() {
        char[] oldPwd = oldField.getPassword();
        char[] newPwd = newField.getPassword();
        char[] confirmPwd = confirmField.getPassword();
        try {
            if (oldPwd.length == 0) {
                statusLabel.setText("请输入当前主密码");
                return;
            }
            if (newPwd.length == 0) {
                statusLabel.setText("请输入新主密码");
                return;
            }
            if (!Arrays.equals(newPwd, confirmPwd)) {
                statusLabel.setText("两次输入的新密码不一致");
                return;
            }
            if (Arrays.equals(oldPwd, newPwd)) {
                statusLabel.setText("新密码不能与当前主密码相同");
                return;
            }
            if (newPwd.length < 6) {
                int r = JOptionPane.showConfirmDialog(this,
                        "新主密码少于 6 位，安全性较低，仍要使用吗？",
                        "提示", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            okBtn.setEnabled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            statusLabel.setText("正在重新加密全部数据...");
            try {
                newKey = service.changeMasterPassword(oldPwd, newPwd);
                dispose();
            } catch (Exception ex) {
                okBtn.setEnabled(true);
                setCursor(Cursor.getDefaultCursor());
                statusLabel.setText("修改失败：" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        } finally {
            Arrays.fill(oldPwd, '\0');
            Arrays.fill(newPwd, '\0');
            Arrays.fill(confirmPwd, '\0');
        }
    }

    /** 修改成功后的新密钥（未成功为 null） */
    public SecretKey getNewKey() {
        return newKey;
    }
}
