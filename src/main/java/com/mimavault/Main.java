package com.mimavault;

import com.mimavault.config.AppConfig;
import com.mimavault.db.DatabaseManager;
import com.mimavault.service.PasswordService;
import com.mimavault.ui.LoginDialog;
import com.mimavault.ui.MainFrame;
import com.mimavault.ui.UiTheme;

import javax.swing.*;
import java.util.Arrays;

/**
 * 密匣 MimaVault 入口
 * 轻量化本地 PC 密码管理程序
 */
public class Main {

    public static void main(String[] args) {
        // 初始化配置与数据库
        AppConfig.load().save();
        DatabaseManager db = new DatabaseManager();
        db.init();
        PasswordService service = new PasswordService(db);
        // 回收站自动过期：启动时清除超过保留天数（30 天）的软删除条目
        try {
            service.purgeExpiredTrashed();
        } catch (Exception e) {
            System.err.println("回收站过期清理失败（不影响启动）: " + e.getMessage());
        }

        SwingUtilities.invokeLater(() -> {
            // 应用全局鲜艳主题（仅视觉层）
            UiTheme.install();
            // 主密码登录 / 设置（成功返回主密码明文 char[]，用完即清）
            char[] masterPassword = LoginDialog.showAndVerify(null, service);
            if (masterPassword == null) {
                System.exit(0);
                return;
            }
            try {
                MainFrame frame = new MainFrame(service, service.deriveKey(masterPassword));
                frame.setVisible(true);
            } finally {
                Arrays.fill(masterPassword, '\0');
            }
        });
    }
}
