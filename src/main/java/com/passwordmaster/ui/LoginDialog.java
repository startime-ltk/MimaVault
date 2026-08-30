package com.passwordmaster.ui;

import com.passwordmaster.service.PasswordService;

import javax.swing.*;
import java.awt.*;

/**
 * 主密码登录/设置对话框
 * - 首次启动：设置主密码
 * - 之后启动：验证主密码，错误提示重试
 */
public class LoginDialog extends JDialog {

    private final PasswordService service;
    private final boolean setupMode;
    private final JPasswordField pwdField = new JPasswordField(16);
    private final JPasswordField pwdField2 = new JPasswordField(16);
    private boolean authenticated = false;

    public LoginDialog(Window owner, PasswordService service, boolean setupMode) {
        super(owner, setupMode ? "设置主密码" : "密码大师 - 请输入主密码", ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.setupMode = setupMode;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;

        JLabel title = new JLabel(setupMode ? "首次使用，请设置主密码" : "请输入主密码解锁");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        panel.add(title, gbc);
        gbc.gridwidth = 1;

        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(new JLabel("主密码："), gbc);
        gbc.gridx = 1;
        panel.add(pwdField, gbc);

        if (setupMode) {
            gbc.gridx = 0;
            gbc.gridy = 2;
            panel.add(new JLabel("确认密码："), gbc);
            gbc.gridx = 1;
            panel.add(pwdField2, gbc);
        }

        JLabel hint = new JLabel(setupMode
                ? "主密码用于加密全部数据，请务必牢记，丢失无法找回"
                : "提示：主密码用于解密本地数据，忘记无法找回");
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        hint.setForeground(new Color(140, 140, 140));
        gbc.gridx = 0;
        gbc.gridy = setupMode ? 3 : 2;
        gbc.gridwidth = 2;
        panel.add(hint, gbc);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        JButton okBtn = new JButton("确定");
        okBtn.addActionListener(e -> onOk());
        JButton cancelBtn = new JButton("退出");
        cancelBtn.addActionListener(e -> dispose());
        btnPanel.add(okBtn);
        btnPanel.add(cancelBtn);
        gbc.gridx = 0;
        gbc.gridy = setupMode ? 4 : 3;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        panel.add(btnPanel, gbc);

        add(panel);
        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
        getRootPane().setDefaultButton(okBtn);
    }

    private void onOk() {
        String pwd = new String(pwdField.getPassword());
        if (pwd.isEmpty()) {
            JOptionPane.showMessageDialog(this, "主密码不能为空", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (setupMode) {
            String pwd2 = new String(pwdField2.getPassword());
            if (!pwd.equals(pwd2)) {
                JOptionPane.showMessageDialog(this, "两次输入的密码不一致", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (pwd.length() < 6) {
                JOptionPane.showMessageDialog(this, "建议主密码不少于 6 位（可继续）", "提示", JOptionPane.WARNING_MESSAGE);
            }
            service.setMasterPassword(pwd);
            authenticated = true;
            dispose();
        } else {
            if (service.verifyMasterPassword(pwd)) {
                authenticated = true;
                dispose();
            } else {
                JOptionPane.showMessageDialog(this, "主密码错误，请重试", "验证失败", JOptionPane.ERROR_MESSAGE);
                pwdField.setText("");
                pwdField.requestFocus();
            }
        }
    }

    /** 是否通过验证 */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * 便捷入口：弹出登录/设置对话框
     * 验证成功返回主密码明文，用于派生 AES 密钥；取消/失败返回 null
     */
    public static String showAndVerify(Window owner, PasswordService service) {
        boolean setup = !service.isInitialized();
        LoginDialog dlg = new LoginDialog(owner, service, setup);
        dlg.setVisible(true);
        if (dlg.isAuthenticated()) {
            return new String(dlg.pwdField.getPassword());
        }
        return null;
    }
}
