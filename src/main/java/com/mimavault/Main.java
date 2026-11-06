package com.mimavault;

import com.mimavault.config.AppConfig;
import com.mimavault.db.DatabaseManager;
import com.mimavault.service.PasswordService;
import com.mimavault.ui.LoginDialog;
import com.mimavault.ui.MainFrame;
import com.mimavault.ui.UiTheme;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 密匣 MimaVault 入口
 * 轻量化本地 PC 密码管理程序
 */
public class Main {

    public static void main(String[] args) {
        // 初始化配置
        AppConfig.load().save();

        // 上次的数据目录不见了：先问用户，再决定用哪个库初始化。
        // 顺序很重要——必须在 DatabaseManager.init() 之前处理，
        // 否则会先拿空库建表，用户就会看到"0 条"并以为数据丢了。
        if (AppConfig.MISSING_DATA_DIR != null) {
            if (!resolveMissingDataDir()) {
                return; // 用户选择退出
            }
        }

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

    /**
     * 数据目录丢失时的处理：提示 + 允许重新指定一个已存在的数据目录。
     *
     * @return false 表示用户选择退出程序
     */
    private static boolean resolveMissingDataDir() {
        final boolean[] exit = {false};
        try {
            SwingUtilities.invokeAndWait(() -> {
                Path missing = AppConfig.MISSING_DATA_DIR;
                Object[] options = {"重新指定数据目录…", "继续使用空目录", "退出"};
                int choice = JOptionPane.showOptionDialog(null,
                        "上次用的数据目录已经不存在了：\n"
                                + "　" + missing + "\n\n"
                                + "这次启动打开的是空数据目录：\n"
                                + "　" + AppConfig.DATA_DIR + "\n\n"
                                + "如果数据是被移动到了别处，点「重新指定数据目录」找到它\n"
                                + "（那个目录里应该有 MimaVault.db 文件）。\n"
                                + "如果数据确实已删除，可以在备份里的 .pmaster 文件恢复。",
                        "数据目录不见了",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, options, options[0]);

                if (choice == 0) {
                    pickExistingDataDir();
                } else if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                    exit[0] = true;
                }
            });
        } catch (Exception e) {
            // 弹窗异常不应阻塞启动
            System.err.println("数据目录提示失败（继续启动）: " + e.getMessage());
        }
        return !exit[0];
    }

    /** 让用户挑一个已存在的数据目录，校验里面有密码库后写入定位文件并立即生效 */
    private static void pickExistingDataDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择数据目录（里面应有 MimaVault.db）");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        // 起始目录定在"原路径的上一级"，方便用户就近找到被移走的文件夹
        Path missing = AppConfig.MISSING_DATA_DIR;
        if (missing != null && missing.getParent() != null && Files.isDirectory(missing.getParent())) {
            chooser.setCurrentDirectory(missing.getParent().toFile());
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path picked = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!hasDatabase(picked)) {
            JOptionPane.showMessageDialog(null,
                    "这个目录里没有找到密码库（MimaVault.db）：\n" + picked
                            + "\n\n请选择正确的数据目录。",
                    "目录不对", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            AppConfig.saveLocator(picked);
            // 立即生效：重新解析路径，后面的建库与登录都用这个目录，不必重启
            AppConfig.initDataPaths();
            JOptionPane.showMessageDialog(null,
                    "已切换到数据目录：\n" + picked
                            + "\n\n请用这个库里原来的主密码登录。",
                    "设置成功", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "写入定位文件失败：\n" + e.getMessage(),
                    "设置失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static boolean hasDatabase(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        return Files.isRegularFile(dir.resolve("MimaVault.db"))
                || Files.isRegularFile(dir.resolve("PasswordMaster.db"));
    }
}
