package com.mimavault.ui;

import com.mimavault.service.UpdateChecker;
import com.mimavault.service.UpdateDownloader;
import com.mimavault.service.UpdateInstaller;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 检查更新对话框（本地下载 + 校验 + 静默安装，不再跳转下载页）。
 *
 * <p>流程：多源检查版本 → 下载安装包到本地（带进度、可取消）→ 校验 SHA-256 →
 * 静默安装并自动重启。安装版会自动关闭密匣、装完后重新拉起；开发期运行
 * （{@code java -jar}）无法自动重启，退化为"打开安装程序，手动关闭密匣"。
 *
 * <p>最后一步始终需要用户点击确认 —— 不会在后台悄悄替换程序。
 */
public class UpdateDialog extends JDialog {

    /** 当前所处的阶段 */
    private enum Stage { CHECKING, FOUND, DOWNLOADING, DOWNLOADED, ERROR }

    private final JLabel statusLabel;
    private final JProgressBar progressBar;
    private final JButton primaryBtn;
    private final JButton secondaryBtn;

    private final String currentVersion;

    private Stage stage = Stage.CHECKING;
    private UpdateChecker.UpdateInfo info;
    private Path downloadedFile;

    /** 下载取消标志 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public UpdateDialog(Window owner, String currentVersion) {
        super(owner, "检查更新", ModalityType.APPLICATION_MODAL);
        this.currentVersion = currentVersion;
        setSize(460, 300);
        setLocationRelativeTo(owner);
        setResizable(false);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));

        JLabel title = new JLabel("密匣 MimaVault 检查更新");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(title);
        root.add(Box.createVerticalStrut(14));

        statusLabel = new JLabel("正在检查更新…", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(statusLabel);
        root.add(Box.createVerticalStrut(12));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        root.add(progressBar);
        root.add(Box.createVerticalStrut(12));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttons.setOpaque(false);
        primaryBtn = new JButton("下载更新");
        primaryBtn.setEnabled(false);
        primaryBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        primaryBtn.addActionListener(e -> onPrimary());
        secondaryBtn = new JButton("暂不更新");
        secondaryBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        secondaryBtn.addActionListener(e -> onSecondary());
        buttons.add(primaryBtn);
        buttons.add(secondaryBtn);
        root.add(buttons);

        setContentPane(root);
        startCheck();
    }

    // ==================== 检查 ====================

    private void startCheck() {
        stage = Stage.CHECKING;
        statusLabel.setText("正在检查更新…");
        statusLabel.setForeground(Color.DARK_GRAY);
        progressBar.setVisible(false);
        primaryBtn.setEnabled(false);
        primaryBtn.setText("下载更新");
        secondaryBtn.setEnabled(true);
        secondaryBtn.setText("暂不更新");

        SwingWorker<UpdateChecker.UpdateInfo, Void> worker = new SwingWorker<UpdateChecker.UpdateInfo, Void>() {
            @Override
            protected UpdateChecker.UpdateInfo doInBackground() throws Exception {
                return UpdateChecker.checkRemote(currentVersion);
            }

            @Override
            protected void done() {
                try {
                    UpdateChecker.UpdateInfo found = get();
                    if (found == null) {
                        showUpToDate();
                    } else {
                        showFound(found);
                    }
                } catch (Exception ex) {
                    showError("检查更新失败，所有更新源均不可达。", "请检查网络后重试。");
                }
            }
        };
        worker.execute();
    }

    private void showUpToDate() {
        stage = Stage.FOUND;
        statusLabel.setForeground(new Color(0x27AE60));
        statusLabel.setText("当前已是最新版本（" + currentVersion + "）");
        primaryBtn.setEnabled(false);
        primaryBtn.setText("下载更新");
        secondaryBtn.setText("关闭");
    }

    private void showFound(UpdateChecker.UpdateInfo found) {
        this.info = found;
        stage = Stage.FOUND;
        StringBuilder sb = new StringBuilder("<html><div style='text-align:center'>");
        sb.append("发现新版本：<b>").append(escape(found.version)).append("</b>");
        sb.append("<br>当前版本：").append(escape(currentVersion));
        sb.append("<br>更新源：").append(escape(found.sourceName));
        if (found.sha256 == null) {
            sb.append("<br><span style='color:#C0392B'>⚠ 本次更新未提供 SHA-256 校验值</span>");
        } else {
            sb.append("<br><span style='color:#27AE60'>✓ 已提供 SHA-256 校验值</span>");
        }
        sb.append("</div></html>");
        statusLabel.setForeground(new Color(0xE67E22));
        statusLabel.setText(sb.toString());
        primaryBtn.setEnabled(true);
        primaryBtn.setText("下载更新");
        secondaryBtn.setText("暂不更新");
    }

    // ==================== 按钮 ====================

    private void onPrimary() {
        switch (stage) {
            case FOUND:
                startDownload();
                break;
            case DOWNLOADED:
                applyUpdate();
                break;
            default:
                break;
        }
    }

    private void onSecondary() {
        if (stage == Stage.DOWNLOADING) {
            cancelled.set(true);
            secondaryBtn.setEnabled(false);
            secondaryBtn.setText("正在取消…");
        } else {
            dispose();
        }
    }

    // ==================== 下载 ====================

    private void startDownload() {
        if (info == null) {
            return;
        }
        if (info.sha256 == null) {
            int ok = JOptionPane.showConfirmDialog(this,
                    "本次更新清单没有提供 SHA-256 校验值。\n\n"
                            + "下载完成后将无法验证安装包是否完整、是否被中间环节替换。\n"
                            + "是否仍要继续下载？",
                    "缺少校验值", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) {
                return;
            }
        }

        stage = Stage.DOWNLOADING;
        cancelled.set(false);
        primaryBtn.setEnabled(false);
        secondaryBtn.setEnabled(true);
        secondaryBtn.setText("取消");
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        progressBar.setValue(0);
        progressBar.setString("正在下载…");
        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText("正在下载安装包…");

        final String url = info.packageUrl();
        final String fileName = deriveFileName(url);
        final String expectedSha = info.sha256;

        SwingWorker<Path, long[]> worker = new SwingWorker<Path, long[]>() {
            @Override
            protected Path doInBackground() throws Exception {
                Path dir = UpdateInstaller.updateDir();
                Path file = UpdateDownloader.download(url, dir, fileName,
                        (downloaded, total) -> publish(new long[]{downloaded, total}),
                        cancelled::get);
                if (!UpdateDownloader.verifySha256(file, expectedSha)) {
                    Files.deleteIfExists(file);
                    throw new IOException("SHA-256 校验不通过：文件可能在下载过程中被损坏或替换");
                }
                return file;
            }

            @Override
            protected void process(java.util.List<long[]> chunks) {
                long[] last = chunks.get(chunks.size() - 1);
                long downloaded = last[0];
                long total = last[1];
                if (total > 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(100);
                    progressBar.setValue((int) Math.min(100, downloaded * 100 / total));
                    progressBar.setString(human(downloaded) + " / " + human(total));
                } else {
                    progressBar.setIndeterminate(true);
                    progressBar.setString(human(downloaded));
                }
            }

            @Override
            protected void done() {
                try {
                    downloadedFile = get();
                    showDownloaded();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof UpdateDownloader.CancelledException) {
                        onDownloadCancelled();
                    } else {
                        showError("下载或校验失败。", cause.getMessage());
                    }
                }
            }
        };
        worker.execute();
    }

    private void onDownloadCancelled() {
        stage = Stage.FOUND;
        progressBar.setVisible(false);
        statusLabel.setForeground(new Color(0xE67E22));
        statusLabel.setText("已取消下载。");
        primaryBtn.setEnabled(true);
        primaryBtn.setText("重新下载");
        secondaryBtn.setEnabled(true);
        secondaryBtn.setText("暂不更新");
    }

    private void showDownloaded() {
        stage = Stage.DOWNLOADED;
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(100);
        progressBar.setValue(100);
        progressBar.setString("下载完成");
        long size = 0;
        try {
            size = Files.size(downloadedFile);
        } catch (IOException ignored) {
            // 大小仅用于展示
        }
        statusLabel.setForeground(new Color(0x27AE60));
        statusLabel.setText("<html><div style='text-align:center'>安装包已下载"
                + (info != null && info.sha256 != null ? "并通过 SHA-256 校验" : "（未提供校验值）")
                + "：<b>" + escape(human(size)) + "</b>"
                + "<br><span style='font-size:9px'>" + escape(shortPath(downloadedFile)) + "</span>"
                + "</div></html>");

        if (UpdateInstaller.canAutoRestart()) {
            primaryBtn.setText("立即安装并重启");
        } else {
            primaryBtn.setText("打开安装程序");
        }
        primaryBtn.setEnabled(true);
        secondaryBtn.setEnabled(true);
        secondaryBtn.setText("稍后");
    }

    // ==================== 安装 ====================

    private void applyUpdate() {
        if (downloadedFile == null || !Files.isRegularFile(downloadedFile)) {
            showError("找不到已下载的安装包。", "请重新检查更新。");
            return;
        }

        if (!UpdateInstaller.canAutoRestart()) {
            // 开发期运行：不做自动重启，直接唤起安装程序
            if (UpdateInstaller.openInstaller(downloadedFile)) {
                JOptionPane.showMessageDialog(this,
                        "已打开安装程序。\n\n请先关闭密匣窗口，再在安装程序里点下一步完成更新。",
                        "安装更新", JOptionPane.INFORMATION_MESSAGE);
                dispose();
            } else {
                JOptionPane.showMessageDialog(this,
                        "无法自动打开安装程序，请手动运行：\n" + downloadedFile.toAbsolutePath(),
                        "安装更新", JOptionPane.WARNING_MESSAGE);
            }
            return;
        }

        int ok = JOptionPane.showConfirmDialog(this,
                "密匣将关闭，随后静默安装新版本，安装完成后自动重新打开。\n\n"
                        + "更新期间你的密码库文件不会被改动。现在开始？",
                "安装更新", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            // 不返回：内部会 System.exit(0)，把执行权交给批处理
            UpdateInstaller.applyAndRestart(downloadedFile);
        } catch (IOException ex) {
            showError("无法启动安装程序。", ex.getMessage());
        }
    }

    // ==================== 状态显示 ====================

    private void showError(String headline, String detail) {
        stage = Stage.ERROR;
        progressBar.setVisible(false);
        statusLabel.setForeground(new Color(0xE74C3C));
        statusLabel.setText("<html><div style='text-align:center'>" + escape(headline)
                + (detail == null ? "" : "<br><span style='font-size:9px'>" + escape(detail) + "</span>")
                + "</div></html>");
        primaryBtn.setText("重新检查");
        primaryBtn.setEnabled(true);
    }

    // ==================== 工具 ====================

    /** 从下载地址推断文件名；取不到时给一个稳妥的默认值 */
    private static String deriveFileName(String url) {
        String name = UpdateDownloader.sanitizeFileName(url);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
            return "MimaVault-Setup.exe";
        }
        return name;
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f KB", kb);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    /** 路径过长时只保留尾部，避免撑破对话框 */
    private static String shortPath(Path p) {
        String s = p == null ? "" : p.toAbsolutePath().toString();
        return s.length() <= 64 ? s : "…" + s.substring(s.length() - 61);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
