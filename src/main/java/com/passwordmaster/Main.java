package com.passwordmaster;

import com.formdev.flatlaf.FlatLightLaf;
import com.passwordmaster.config.AppConfig;
import com.passwordmaster.db.DatabaseManager;
import com.passwordmaster.service.PasswordService;
import com.passwordmaster.ui.LoginDialog;
import com.passwordmaster.ui.MainFrame;

import javax.swing.*;
import java.awt.*;

/**
 * 密码大师 入口
 * 轻量化本地 PC 密码管理程序
 */
public class Main {

    public static void main(String[] args) {
        // FlatLaf 浅色简约主题
        FlatLightLaf.setup();

        // 初始化配置与数据库
        AppConfig.load().save();
        DatabaseManager db = new DatabaseManager();
        db.init();
        PasswordService service = new PasswordService(db);

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.put("OptionPane.messageFont", new Font("Microsoft YaHei", Font.PLAIN, 13));
                UIManager.put("OptionPane.buttonFont", new Font("Microsoft YaHei", Font.PLAIN, 13));
                UIManager.put("Label.font", new Font("Microsoft YaHei", Font.PLAIN, 13));
                UIManager.put("Button.font", new Font("Microsoft YaHei", Font.PLAIN, 13));
                UIManager.put("TextField.font", new Font("Microsoft YaHei", Font.PLAIN, 13));
                UIManager.put("Table.font", new Font("Microsoft YaHei", Font.PLAIN, 13));
                UIManager.put("TableHeader.font", new Font("Microsoft YaHei", Font.BOLD, 12));
            } catch (Exception ignored) {
            }

            // 主密码登录 / 设置（成功返回主密码明文，用于派生密钥）
            String masterPassword = LoginDialog.showAndVerify(null, service);
            if (masterPassword == null) {
                System.exit(0);
                return;
            }

            MainFrame frame = new MainFrame(service, service.deriveKey(masterPassword));
            frame.setVisible(true);
        });
    }
}
