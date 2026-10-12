package com.mimavault.ui;

import com.mimavault.service.UpdateChecker;

import javax.swing.*;
import java.awt.*;
import java.awt.Desktop;
import java.net.URI;

/**
 * 检查更新对话框（v8.35.0，对齐安卓端多源检查更新）。
 * 后台线程依次尝试 CDN → 镜像 → 直连，展示命中源与新版信息；
 * 提供「打开下载页」按钮（系统浏览器打开命中源安装包地址）与「暂不更新」。
 */
public class UpdateDialog extends JDialog {

    private final JLabel statusLabel;
    private final JButton downloadBtn;

    public UpdateDialog(Window owner, String currentVersion) {
        super(owner, "检查更新", ModalityType.APPLICATION_MODAL);
        setSize(420, 240);
        setLocationRelativeTo(owner);
        setResizable(false);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));

        JLabel title = new JLabel("密匣 MimaVault 检查更新");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(title);
        root.add(Box.createVerticalStrut(16));

        statusLabel = new JLabel("正在检查更新…", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(statusLabel);
        root.add(Box.createVerticalStrut(20));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttons.setOpaque(false);
        downloadBtn = new JButton("打开下载页");
        downloadBtn.setEnabled(false);
        downloadBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        downloadBtn.addActionListener(e -> openDownload());
        JButton closeBtn = new JButton("暂不更新");
        closeBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        closeBtn.addActionListener(e -> dispose());
        buttons.add(downloadBtn);
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
                    UpdateChecker.UpdateInfo info = get();
                    if (info == null) {
                        statusLabel.setText("当前已是最新版本（" + currentVersion + "）");
                        statusLabel.setForeground(new Color(0x27AE60));
                    } else {
                        statusLabel.setText("<html>发现新版本：" + info.version + "<br>当前版本：" + currentVersion
                                + "<br>更新源：" + sourceLabel(info.sourceName) + "</html>");
                        statusLabel.setForeground(new Color(0xE67E22));
                        downloadBtn.setEnabled(true);
                        pendingUrl = info.packageUrl();
                    }
                } catch (Exception ex) {
                    statusLabel.setText("<html>检查更新失败，所有更新源均不可达。<br>请检查网络后重试。</html>");
                    statusLabel.setForeground(new Color(0xE74C3C));
                }
            }
        };
        worker.execute();
    }

    /** 更新源显示名（与安卓端 UI 字符串 check_update_source 语义一致） */
    private static String sourceLabel(String name) {
        return name == null ? "" : name;
    }

    private String pendingUrl;

    private void openDownload() {
        if (pendingUrl == null || pendingUrl.isEmpty()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(pendingUrl));
            } else {
                JOptionPane.showMessageDialog(this, "无法打开浏览器，请手动访问：\n" + pendingUrl,
                        "下载地址", JOptionPane.INFORMATION_MESSAGE);
            }
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "打开下载页失败：\n" + pendingUrl,
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
}
