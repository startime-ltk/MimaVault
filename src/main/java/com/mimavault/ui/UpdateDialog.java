package com.mimavault.ui;

import com.mimavault.service.UpdateChecker;
import com.mimavault.service.UpdateInstaller;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URI;

/**
 * 检查更新对话框（v8.35.4：程序内直接下载并静默覆盖安装）
 *
 * <p>改动原因：以前点「打开下载页」会跳到浏览器，用户还得自己找下载好的 exe、关掉正在运行的程序、
 * 再手动点安装——多出来的每一步都可能卡住。现在点「立即更新」就是：下载 → 校验 → 静默覆盖安装 →
 * 自动重新打开，全程不用离开程序。
 *
 * <p>安全兜底：
 * <ul>
 *   <li>下载先落 .part 临时文件，校验 PE 头（MZ）与 sha256 后才转正</li>
 *   <li>主下载地址失败自动换镜像重试</li>
 *   <li>安装器启动后若立刻异常退出，主程序保持打开，不会出现「程序关了、版本也没升」</li>
 *   <li>任一步失败都退回「重试 / 用浏览器下载」，不会把用户卡死</li>
 * </ul>
 */
public class UpdateDialog extends JDialog {

    /** 安装包临时落地目录：%TEMP%\MimaVault-Update */
    private static final File DOWNLOAD_DIR =
            new File(System.getProperty("java.io.tmpdir", "."), "MimaVault-Update");

    private final JLabel statusLabel;
    private final JProgressBar progressBar;
    private final JButton updateBtn;
    private final JButton closeBtn;

    private UpdateChecker.UpdateInfo info;
    private volatile boolean cancelled;
    private volatile boolean downloading;

    public UpdateDialog(Window owner, String currentVersion) {
        super(owner, "检查更新", ModalityType.APPLICATION_MODAL);
        setSize(460, 270);
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
        // 固定宽度：HTML 文案按这个宽度换行，避免长句把对话框撑破
        Dimension statusSize = new Dimension(412, 58);
        statusLabel.setPreferredSize(statusSize);
        statusLabel.setMinimumSize(statusSize);
        statusLabel.setMaximumSize(statusSize);
        root.add(statusLabel);
        root.add(Box.createVerticalStrut(12));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("");
        progressBar.setVisible(false);
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        root.add(progressBar);
        root.add(Box.createVerticalStrut(16));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttons.setOpaque(false);
        updateBtn = new JButton("立即更新");
        updateBtn.setEnabled(false);
        updateBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        updateBtn.addActionListener(e -> startUpdate());
        closeBtn = new JButton("暂不更新");
        closeBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        closeBtn.addActionListener(e -> onClose());
        buttons.add(updateBtn);
        buttons.add(closeBtn);
        root.add(buttons);

        setContentPane(root);
        startCheck(currentVersion);
    }

    /** 后台检查：多源依次尝试，结果回填 UI */
    private void startCheck(String currentVersion) {
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
                        statusLabel.setText("当前已是最新版本（" + currentVersion + "）");
                        statusLabel.setForeground(new Color(0x27AE60));
                    } else {
                        info = found;
                        statusLabel.setText("<html><div style='text-align:center'>发现新版本 <b>"
                                + found.version + "</b>（当前 " + currentVersion + "）<br>"
                                + "点「立即更新」会自动下载并安装，装完自动重开，不需要你手动操作</div></html>");
                        statusLabel.setForeground(new Color(0xE67E22));
                        updateBtn.setEnabled(true);
                    }
                } catch (Exception ex) {
                    statusLabel.setText("<html><div style='text-align:center'>检查更新失败，所有更新源均不可达。<br>"
                            + "请检查网络后重试。</div></html>");
                    statusLabel.setForeground(new Color(0xE74C3C));
                }
            }
        };
        worker.execute();
    }

    // ------------------------------------------------------------------ 更新流程

    private void startUpdate() {
        if (info == null || downloading) {
            return;
        }
        downloading = true;
        cancelled = false;
        updateBtn.setEnabled(false);
        closeBtn.setText("取消");
        progressBar.setVisible(true);
        progressBar.setValue(0);
        progressBar.setString("准备下载…");
        statusLabel.setForeground(new Color(0x2D3A5E));
        statusLabel.setText("<html><div style='text-align:center'>正在下载 " + info.version + "…</div></html>");

        Thread t = new Thread(() -> {
            try {
                File exe = UpdateChecker.download(info, DOWNLOAD_DIR,
                        (read, total) -> SwingUtilities.invokeLater(() -> showProgress(read, total)),
                        () -> cancelled);

                SwingUtilities.invokeLater(() -> {
                    if (cancelled) {
                        return;
                    }
                    progressBar.setValue(100);
                    progressBar.setString("下载完成");
                    statusLabel.setText("<html><div style='text-align:center'>下载完成，正在安装…<br>"
                            + "程序会短暂关闭，装好后自动重新打开</div></html>");
                });

                Process installer = UpdateInstaller.launch(exe);
                // 给安装器一点时间完成初始化；若它立刻异常退出就不退出主程序，
                // 避免「程序关了、版本也没升」的僵局
                UpdateInstaller.ensureInstallerRunning(installer, 1500);
                // 安装器已接管：本进程让位，装完后安装脚本会自动重新打开新版本
                System.exit(0);

            } catch (Exception ex) {
                if (cancelled) {
                    return; // 用户主动取消，不当作错误
                }
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                SwingUtilities.invokeLater(() -> onUpdateFailed(message));
            }
        }, "mimavault-update");
        t.setDaemon(false);
        t.start();
    }

    private void showProgress(long read, long total) {
        if (cancelled) {
            return;
        }
        double mb = read / 1024.0 / 1024.0;
        if (total > 0) {
            int pct = (int) Math.min(100, read * 100 / total);
            progressBar.setValue(pct);
            progressBar.setString(String.format("%d%%　%.1f / %.1f MB", pct, mb, total / 1024.0 / 1024.0));
        } else {
            progressBar.setIndeterminate(true);
            progressBar.setString(String.format("%.1f MB", mb));
        }
    }

    private void onUpdateFailed(String message) {
        downloading = false;
        cancelled = true;
        progressBar.setVisible(false);
        progressBar.setIndeterminate(false);
        updateBtn.setEnabled(true);
        closeBtn.setText("暂不更新");
        statusLabel.setForeground(new Color(0xE74C3C));
        statusLabel.setText("<html><div style='text-align:center'>自动更新失败：" + escapeHtml(message)
                + "<br>可以再试一次，或改用浏览器手动下载</div></html>");

        Object[] options = {"再试一次", "用浏览器下载", "关闭"};
        int choice = JOptionPane.showOptionDialog(this,
                "自动更新没有完成：\n" + message + "\n\n要换一种方式吗？",
                "更新未完成", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            startUpdate();
        } else if (choice == 1) {
            openDownloadPage();
        }
    }

    /** 失败兜底：手动用浏览器下载（正常路径不走这里） */
    private void openDownloadPage() {
        if (info == null) {
            return;
        }
        String url = info.packageUrl();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                dispose();
            } else {
                JOptionPane.showMessageDialog(this, "无法打开浏览器，请手动访问：\n" + url,
                        "下载地址", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "打开浏览器失败，请手动访问：\n" + url,
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ 杂项

    private void onClose() {
        if (downloading) {
            cancelled = true;
            downloading = false;
            statusLabel.setForeground(new Color(0x7A86A8));
            statusLabel.setText("已取消，本次不更新。");
            progressBar.setVisible(false);
            progressBar.setIndeterminate(false);
            updateBtn.setEnabled(true);
            closeBtn.setText("关闭");
            return;
        }
        dispose();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
