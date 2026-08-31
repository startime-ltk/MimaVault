package com.mimavault.ui;

import com.mimavault.service.PasswordService;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/**
 * 主密码登录/设置对话框
 * - 首次启动：设置主密码
 * - 之后启动：验证主密码（PBKDF2），连续输错 5 次锁定 30 秒
 * - 检测到旧版 SHA-256 哈希时自动触发一次性迁移升级
 * - 密码明文以 char[] 承载，用完即清
 * <p>
 * 视觉：渐变背景 + 居中圆角卡片 + 艺术字标题 + 圆角输入框 + 渐变按钮
 */
public class LoginDialog extends JDialog {

    /** 最大失败次数 / 锁定时长（秒） */
    private static final int MAX_FAILURES = 5;
    private static final int LOCK_SECONDS = 30;

    private final PasswordService service;
    private final boolean setupMode;
    private final JPasswordField pwdField = new JPasswordField(16);
    private final JPasswordField pwdField2 = new JPasswordField(16);
    private final GradientButton okBtn = GradientButton.primary("确定");
    private final JLabel statusLabel = new JLabel(" ");

    private boolean authenticated = false;
    private char[] masterPassword = null;
    private int failedCount = 0;
    private boolean locked = false;
    private Timer lockTimer;
    private int lockRemaining;

    public LoginDialog(Window owner, PasswordService service, boolean setupMode) {
        super(owner, setupMode ? "设置主密码" : "密匣 MimaVault - 请输入主密码", ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.setupMode = setupMode;
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
        ArtTextLabel title = new ArtTextLabel(setupMode ? "设置主密码" : "密匣 MimaVault", 28);
        title.setPreferredSize(new Dimension(280, 42));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        card.add(title, gbc);
        gbc.gridwidth = 1;

        JLabel subtitle = new JLabel(setupMode ? "首次使用，请设置主密码" : "请输入主密码解锁", SwingConstants.CENTER);
        subtitle.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        subtitle.setForeground(UiTheme.TEXT_SUB);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        card.add(subtitle, gbc);
        gbc.gridwidth = 1;

        gbc.insets = new Insets(10, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 2;
        card.add(new JLabel("主密码："), gbc);
        gbc.gridx = 1;
        pwdField.setPreferredSize(new Dimension(200, 32));
        card.add(pwdField, gbc);

        if (setupMode) {
            gbc.gridx = 0;
            gbc.gridy = 3;
            card.add(new JLabel("确认密码："), gbc);
            gbc.gridx = 1;
            pwdField2.setPreferredSize(new Dimension(200, 32));
            card.add(pwdField2, gbc);
        }

        JLabel hint = new JLabel(setupMode
                ? "主密码用于加密全部数据，请务必牢记，丢失无法找回"
                : "提示：主密码用于解密本地数据，忘记无法找回；连续输错 5 次将锁定 30 秒");
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        hint.setForeground(UiTheme.TEXT_SUB);
        gbc.gridx = 0;
        gbc.gridy = setupMode ? 4 : 3;
        gbc.gridwidth = 2;
        card.add(hint, gbc);

        // 状态行（错误提示 / 锁定倒计时）
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statusLabel.setForeground(UiTheme.DANGER);
        gbc.gridx = 0;
        gbc.gridy = setupMode ? 5 : 4;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        card.add(statusLabel, gbc);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        btnPanel.setOpaque(false);
        okBtn.addActionListener(e -> onOk());
        GradientButton cancelBtn = GradientButton.secondary("退出");
        cancelBtn.addActionListener(e -> dispose());
        btnPanel.add(okBtn);
        btnPanel.add(cancelBtn);
        gbc.gridx = 0;
        gbc.gridy = setupMode ? 6 : 5;
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
        if (locked) {
            return; // 锁定期间按钮已禁用，防御性拦截
        }
        char[] pwd = pwdField.getPassword();
        try {
            if (pwd.length == 0) {
                statusLabel.setText("主密码不能为空");
                return;
            }
            if (setupMode) {
                char[] pwd2 = pwdField2.getPassword();
                try {
                    if (!Arrays.equals(pwd, pwd2)) {
                        statusLabel.setText("两次输入的密码不一致");
                        return;
                    }
                } finally {
                    Arrays.fill(pwd2, '\0');
                }
                if (pwd.length < 6) {
                    JOptionPane.showMessageDialog(this, "建议主密码不少于 6 位（可继续）", "提示", JOptionPane.WARNING_MESSAGE);
                }
                service.setMasterPassword(pwd);
                authenticated = true;
                masterPassword = Arrays.copyOf(pwd, pwd.length);
                dispose();
            } else {
                PasswordService.VerifyResult result = service.verifyMasterPassword(pwd);
                if (result == PasswordService.VerifyResult.MATCH
                        || result == PasswordService.VerifyResult.MATCH_NEED_UPGRADE) {
                    // 旧格式数据：一次性迁移到 PBKDF2 新密钥
                    if (result == PasswordService.VerifyResult.MATCH_NEED_UPGRADE) {
                        statusLabel.setText("正在升级加密方案...");
                        try {
                            service.upgradeToPbkdf2(pwd);
                        } catch (Exception ex) {
                            statusLabel.setText("升级失败：" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                            return;
                        }
                    }
                    failedCount = 0;
                    authenticated = true;
                    masterPassword = Arrays.copyOf(pwd, pwd.length);
                    dispose();
                } else {
                    failedCount++;
                    if (failedCount >= MAX_FAILURES) {
                        startLock();
                    } else {
                        statusLabel.setText("主密码错误（" + failedCount + "/" + MAX_FAILURES + "），请重试");
                        pwdField.setText("");
                        pwdField.requestFocus();
                    }
                }
            }
        } finally {
            Arrays.fill(pwd, '\0');
        }
    }

    /** 锁定 30 秒：禁用按钮 + 倒计时显示（仅进程内存，不持久化） */
    private void startLock() {
        locked = true;
        failedCount = 0;
        lockRemaining = LOCK_SECONDS;
        okBtn.setEnabled(false);
        pwdField.setEnabled(false);
        statusLabel.setText("尝试次数过多，请 " + lockRemaining + " 秒后重试");
        lockTimer = new Timer(1000, e -> {
            lockRemaining--;
            if (lockRemaining <= 0) {
                lockTimer.stop();
                locked = false;
                okBtn.setEnabled(true);
                pwdField.setEnabled(true);
                statusLabel.setText(" ");
                pwdField.setText("");
                pwdField.requestFocus();
            } else {
                statusLabel.setText("尝试次数过多，请 " + lockRemaining + " 秒后重试");
            }
        });
        lockTimer.start();
    }

    /** 是否通过验证 */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /** 验证成功后的主密码明文副本（char[]，调用方用完请 Arrays.fill 清零） */
    public char[] getMasterPassword() {
        return masterPassword;
    }

    /**
     * 便捷入口：弹出登录/设置对话框
     * 验证成功返回主密码明文 char[]（用于派生密钥，用完请清零）；取消/失败返回 null
     */
    public static char[] showAndVerify(Window owner, PasswordService service) {
        boolean setup = !service.isInitialized();
        LoginDialog dlg = new LoginDialog(owner, service, setup);
        dlg.setVisible(true);
        if (dlg.isAuthenticated()) {
            return dlg.getMasterPassword();
        }
        return null;
    }
}
