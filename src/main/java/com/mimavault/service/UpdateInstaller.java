package com.mimavault.service;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载完成后的"安装并重启"编排。
 *
 * <p>为什么不能原地覆盖：Windows 上运行中的 {@code 密匣 MimaVault.exe} 与 {@code app\MimaVault.jar}
 * 都被占用，无法被安装程序替换。所以必须走「主程序退出 → 安装程序替换 → 重新启动」。
 *
 * <p>做法：写一个<b>纯 ASCII</b> 的批处理（{@code apply-update.cmd}），由它
 * 1) 轮询等待本进程 PID 消失；2) 用 {@code /VERYSILENT} 静默安装；3) 重新拉起程序。
 * 批处理本身不含中文 —— 中文路径一律通过命令行参数 {@code %1/%2/%3} 传入，
 * 这样不会踩到 cmd 按 OEM 代码页解析脚本导致的乱码问题。
 *
 * <p>适用范围：只在 jpackage 打出来的安装版里能自动重启（该版本的启动器会注入
 * {@code jpackage.app-path}）。开发期用 {@code java -jar} 运行时拿不到启动器路径，
 * 此时 {@link #canAutoRestart()} 返回 false，界面会退化成"打开安装程序，手动关闭密匣"。
 */
public final class UpdateInstaller {

    /** jpackage 启动器注入的系统属性，值为启动器 exe 的完整路径 */
    private static final String PROP_APP_PATH = "jpackage.app-path";

    /** 批处理内容：纯 ASCII，绝不写入中文字符（包级可见以便单测校验） */
    static final String SCRIPT = String.join("\r\n",
            "@echo off",
            "setlocal",
            "set \"TARGET_PID=%~1\"",
            "set \"SETUP=%~2\"",
            "set \"APPEXE=%~3\"",
            "set \"LOG=%~dp0apply.log\"",
            "echo [%DATE% %TIME%] waiting for pid %TARGET_PID% >>\"%LOG%\"",
            ":waitloop",
            "for /l %%i in (1,1,120) do (",
            "  tasklist /FI \"PID eq %TARGET_PID%\" /NH 2>nul | findstr /C:\"%TARGET_PID%\" >nul",
            "  if errorlevel 1 goto install",
            "  ping -n 2 127.0.0.1 >nul",
            ")",
            ":install",
            "echo [%DATE% %TIME%] running setup >>\"%LOG%\"",
            "\"%SETUP%\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /FORCECLOSEAPPLICATIONS >>\"%LOG%\" 2>&1",
            "echo [%DATE% %TIME%] setup exit=%ERRORLEVEL% >>\"%LOG%\"",
            "start \"\" \"%APPEXE%\"",
            "echo [%DATE% %TIME%] relaunched >>\"%LOG%\"",
            "endlocal",
            "");

    private UpdateInstaller() {
    }

    /**
     * 更新工作目录：{@code %LOCALAPPDATA%/MimaVault/update}。
     *
     * <p>注：路径刻意写成正斜杠 —— Java 在词法分析之前会先处理 {@code \\uXXXX} 转义，
     * 注释里出现「反斜杠 + u」（例如 <code>.../update</code> 的反斜杠版本）会直接编译失败。
     */
    public static Path updateDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base;
        if (localAppData != null && !localAppData.isBlank()) {
            base = Paths.get(localAppData);
        } else {
            base = Paths.get(System.getProperty("user.home", "."));
        }
        return base.resolve("MimaVault").resolve("update");
    }

    /** 批处理脚本路径（每次更新覆写） */
    public static Path scriptPath() {
        return updateDir().resolve("apply-update.cmd");
    }

    /** 安装版启动器 exe 路径；开发期运行返回 null */
    public static Path appLauncher() {
        String p = System.getProperty(PROP_APP_PATH);
        if (p == null || p.isBlank()) {
            return null;
        }
        Path path = Paths.get(p);
        return Files.exists(path) ? path : null;
    }

    /** 是否具备"自动关闭并重启"的能力 */
    public static boolean canAutoRestart() {
        return appLauncher() != null;
    }

    /**
     * 生成批处理并脱离宿主进程执行，然后<b>立即退出当前进程</b>。
     *
     * <p>调用本方法后不会返回 —— 它内部会调用 {@code System.exit(0)}。
     *
     * @param setupExe 已下载并校验通过的安装程序
     * @throws IOException 无法生成脚本或启动批处理
     */
    public static void applyAndRestart(Path setupExe) throws IOException {
        Path launcher = appLauncher();
        if (launcher == null) {
            throw new IOException("当前不是安装版运行（缺少 jpackage.app-path），无法自动重启");
        }
        if (setupExe == null || !Files.isRegularFile(setupExe)) {
            throw new IOException("安装程序不存在：" + setupExe);
        }

        Path dir = updateDir();
        Files.createDirectories(dir);
        Path script = scriptPath();
        // 纯 ASCII 内容：无论系统代码页是什么都能被 cmd 正确解析
        Files.writeString(script, SCRIPT, StandardCharsets.US_ASCII);
        Files.deleteIfExists(dir.resolve("apply.log"));

        List<String> cmd = new ArrayList<>();
        cmd.add("cmd.exe");
        cmd.add("/c");
        cmd.add(script.toString());
        cmd.add(String.valueOf(ProcessHandle.current().pid()));
        cmd.add(setupExe.toAbsolutePath().toString());
        cmd.add(launcher.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("apply.log").toFile()));
        pb.start();

        // 立即退出，把文件句柄交给批处理等待并替换
        System.exit(0);
    }

    /**
     * 兜底方案：不做自动重启，直接用系统默认程序打开安装程序。
     * 适用于开发期运行，或用户选择"稍后自行安装"。
     *
     * @return 是否成功唤起
     */
    public static boolean openInstaller(Path setupExe) {
        if (setupExe == null || !Files.isRegularFile(setupExe)) {
            return false;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(setupExe.toFile());
                return true;
            }
        } catch (Exception ignored) {
            // 落到下面由调用方提示路径
        }
        return false;
    }
}
