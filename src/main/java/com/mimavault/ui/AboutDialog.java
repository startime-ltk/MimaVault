package com.mimavault.ui;

import com.mimavault.util.ClipboardSafe;

import javax.swing.*;
import java.awt.*;
import java.net.URI;

/**
 * 关于对话框：当前版本 + 检查更新入口 + 作者 GitHub 主页（可点击跳转）。
 *
 * <p>入口在工具栏「关于」。检查更新复用 {@link UpdateDialog}，
 * 主页始终打开 {@link #AUTHOR_HOMEPAGE}（写死常量，不从更新清单读，避免被远端改写）。
 */
public class AboutDialog extends JDialog {

    /** 作者 GitHub 主页 */
    public static final String AUTHOR_HOMEPAGE = "https://github.com/startime-ltk";

    private final String version;

    public AboutDialog(Window owner, String version) {
        super(owner, "关于 密匣 MimaVault", ModalityType.APPLICATION_MODAL);
        this.version = version;
        setIconImage(UiTheme.getAppIcon());
        setResizable(false);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(20, 26, 16, 26));

        // 图标
        Image appIcon = UiTheme.getAppIcon();
        JLabel logo = new JLabel();
        if (appIcon != null) {
            logo.setIcon(new ImageIcon(appIcon.getScaledInstance(56, 56, Image.SCALE_SMOOTH)));
        }
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(logo);
        root.add(Box.createVerticalStrut(10));

        JLabel title = new JLabel("密匣 MimaVault");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(title);
        root.add(Box.createVerticalStrut(6));

        JLabel ver = new JLabel("版本 " + version);
        ver.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        ver.setForeground(new Color(0x666666));
        ver.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(ver);
        root.add(Box.createVerticalStrut(12));

        JLabel desc = new JLabel("轻量化本地密码管理程序 · 数据全部本地加密存储");
        desc.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        desc.setForeground(new Color(0x777777));
        desc.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(desc);
        root.add(Box.createVerticalStrut(18));

        // 操作按钮
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.CENTER_ALIGNMENT);

        GradientButton updateBtn = GradientButton.accent("检查更新");
        updateBtn.setToolTipText("检查是否有新版本（程序内下载并静默安装）");
        updateBtn.addActionListener(e -> {
            dispose();
            new UpdateDialog(owner, version).setVisible(true);
        });

        GradientButton homeBtn = GradientButton.secondary("项目主页");
        homeBtn.setToolTipText(AUTHOR_HOMEPAGE);
        homeBtn.addActionListener(e -> openHomepage());

        GradientButton closeBtn = GradientButton.secondary("关闭");
        closeBtn.addActionListener(e -> dispose());

        buttons.add(updateBtn);
        buttons.add(homeBtn);
        buttons.add(closeBtn);
        root.add(buttons);
        root.add(Box.createVerticalStrut(14));

        // 作者行：链接可点击跳转
        JPanel authorRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
        authorRow.setOpaque(false);
        authorRow.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel by = new JLabel("作者：");
        by.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        by.setForeground(new Color(0x777777));

        JLabel link = new JLabel("<html><u>" + AUTHOR_HOMEPAGE + "</u></html>");
        link.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        link.setForeground(new Color(0x1E88E5));
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText("点击用浏览器打开 " + AUTHOR_HOMEPAGE);
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent ev) {
                openHomepage();
            }
        });

        authorRow.add(by);
        authorRow.add(link);
        root.add(authorRow);

        setContentPane(root);
        pack();
        setMinimumSize(new Dimension(Math.max(420, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(closeBtn);
    }

    /** 当前展示的版本号 */
    public String getVersion() {
        return version;
    }

    /** 打开作者主页；无浏览器时退化为复制链接 */
    private void openHomepage() {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(AUTHOR_HOMEPAGE));
                return;
            }
        } catch (Exception ignored) {
            // 落到下面的兜底
        }
        ClipboardSafe.copy(AUTHOR_HOMEPAGE);
        JOptionPane.showMessageDialog(this,
                "无法自动打开浏览器，主页地址已复制到剪贴板：\n" + AUTHOR_HOMEPAGE,
                "项目主页", JOptionPane.INFORMATION_MESSAGE);
    }
}
