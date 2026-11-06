package com.mimavault;

import com.mimavault.config.AppConfig;
import com.mimavault.db.DatabaseManager;
import com.mimavault.service.PasswordService;
import com.mimavault.ui.LoginDialog;
import com.mimavault.ui.MainFrame;
import com.mimavault.ui.MissingDataDirDialog;
import com.mimavault.ui.UiTheme;

import javax.swing.*;
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
     * 数据目录丢失时的处理：先后台扫盘把候选库找出来让用户挑（认条数即可），
     * 挑中后写回定位文件并立即生效。
     *
     * @return false 表示用户选择退出程序
     */
    private static boolean resolveMissingDataDir() {
        Path missing = AppConfig.MISSING_DATA_DIR;
        final MissingDataDirDialog.Outcome[] holder = new MissingDataDirDialog.Outcome[1];
        try {
            SwingUtilities.invokeAndWait(() ->
                    holder[0] = MissingDataDirDialog.show(missing, AppConfig.DATA_DIR));
        } catch (Exception e) {
            // 弹窗异常不应阻塞启动
            System.err.println("数据目录提示失败（继续启动）: " + e.getMessage());
            return true;
        }

        MissingDataDirDialog.Outcome outcome = holder[0];
        if (outcome == null || outcome.choice == MissingDataDirDialog.Choice.EXIT) {
            return false;
        }
        if (outcome.choice == MissingDataDirDialog.Choice.CONTINUE_EMPTY) {
            return true;
        }

        try {
            AppConfig.saveLocator(outcome.dir);
            // 立即生效：重新解析路径，后面的建库与登录都用这个目录，不必重启
            AppConfig.initDataPaths();
            if (AppConfig.MISSING_DATA_DIR != null) {
                JOptionPane.showMessageDialog(null,
                        "目录写入后又变得不可访问了：\n" + outcome.dir
                                + "\n\n本次先用空目录启动，请检查该目录是否存在。",
                        "切换未完成", JOptionPane.ERROR_MESSAGE);
                return true;
            }
            JOptionPane.showMessageDialog(null,
                    "已切换到数据目录：\n" + outcome.dir
                            + "\n\n请用这个库里原来的主密码登录。",
                    "设置成功", JOptionPane.INFORMATION_MESSAGE);
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "写入定位文件失败，本次先用空目录启动：\n" + e.getMessage(),
                    "设置失败", JOptionPane.ERROR_MESSAGE);
            return true;
        }
    }
}
