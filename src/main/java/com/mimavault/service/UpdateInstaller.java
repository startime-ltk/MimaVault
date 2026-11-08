package com.mimavault.service;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 启动下载好的安装包做「静默覆盖安装」（v8.35.4）。
 *
 * <p>单独抽成服务类，是为了能在隔离环境里把「检查更新 → 下载 → 校验 → 拉起安装器」
 * 整条链路真跑一遍：验证时注入一个假的 Launcher，只记录参数、不真的安装任何东西。
 *
 * <p>参数含义：
 * <ul>
 *   <li>/SILENT：只显示安装进度条，不弹向导页，不需要用户点下一步</li>
 *   <li>/NORESTART：不因为文件占用而重启操作系统</li>
 *   <li>/CLOSEAPPLICATIONS：允许安装器关掉仍占用安装目录的残留进程</li>
 * </ul>
 * 安装完成后由安装脚本 [Run] 里带 Check: IsSilentInstall 的那一项自动重新打开新版本。
 */
public final class UpdateInstaller {

    /** 进程启动器：真实实现走 ProcessBuilder，验证时可替换成记录参数的假实现 */
    public interface Launcher {
        Process launch(List<String> command, File workDir) throws IOException;
    }

    private static final Launcher REAL_LAUNCHER = (command, workDir) -> {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        return pb.start();
    };

    private UpdateInstaller() {
    }

    /** 构造静默覆盖安装的命令行（抽出来便于断言参数正确） */
    public static List<String> buildCommand(File exe) {
        return Collections.unmodifiableList(Arrays.asList(
                exe.getAbsolutePath(), "/SILENT", "/NORESTART", "/CLOSEAPPLICATIONS"));
    }

    /** 真实启动安装器，返回进程句柄，调用方可据此判断安装器是否立刻异常退出 */
    public static Process launch(File exe) throws IOException {
        return launch(exe, REAL_LAUNCHER);
    }

    /** 可注入启动器的重载，供隔离验证使用 */
    public static Process launch(File exe, Launcher launcher) throws IOException {
        if (exe == null || !exe.isFile()) {
            throw new IOException("安装包不存在：" + (exe == null ? "null" : exe.getAbsolutePath()));
        }
        return launcher.launch(buildCommand(exe), exe.getParentFile());
    }

    /**
     * 启动后短暂观察安装器：若它立刻以非 0 退出，说明安装包有问题，
     * 抛异常让主程序保持打开（否则用户会遇到「程序关了、版本也没升」）。
     *
     * @param graceMs 观察窗口（毫秒），验证时可传 0
     */
    public static void ensureInstallerRunning(Process installer, long graceMs)
            throws IOException, InterruptedException {
        if (graceMs > 0) {
            Thread.sleep(graceMs);
        }
        if (!installer.isAlive() && installer.exitValue() != 0) {
            throw new IOException("安装器启动失败（退出码 " + installer.exitValue()
                    + "），当前版本保持不变");
        }
    }
}
