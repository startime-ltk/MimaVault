package com.mimavault.ui;

import com.mimavault.util.ClipboardSafe;

import javax.swing.*;
import java.awt.*;
import java.net.URI;

/**
 * 关于对话框（v8.35.7）：版本信息 / 修改主密码 / 检查更新 / 作者与 DSH 插件入口。
 *
 * <p>「修改主密码」「检查更新」只是把动作转交回主窗口（{@link Runnable} 回调），
 * 本类不直接操作密码库，也不自己决定导航 —— 避免这里悄悄改动数据。
 *
 * <p>两个链接都写死常量，不从更新清单或远端读取，避免被远端内容改写跳转目标。
 */
public class AboutDialog extends JDialog {

    /** 作者 GitHub 主页 */
    public static final String AUTHOR_HOMEPAGE = "https://github.com/startime-ltk";
    /** DSH 插件仓库 */
    public static final String PLUGIN_HOMEPAGE = "https://github.com/startime-ltk/dsh-mimavault";

    private final String version;

    /**
     * @param owner           父窗口
     * @param version         当前版本号
     * @param onChangeMaster  打开「修改主密码」流程（可空）
     * @param onCheckUpdate   打开「检查更新」流程（可空）
     */
    public AboutDialog(Window owner, String version, Runnable onChangeMaster, Runnable onCheckUpdate) {
        super(owner, "关于 密匣 MimaVault", ModalityType.APPLICATION_MODAL);
        this.version = version;
        setIconImage(UiTheme.getAppIcon());
        setResizable(false);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(18, 26, 14, 26));

        // ---------- 品牌区 ----------
        Image appIcon = UiTheme.getAppIcon();
        JLabel logo = new JLabel();
        if (appIcon != null) {
            logo.setIcon(new ImageIcon(appIcon.getScaledInstance(56, 56, Image.SCALE_SMOOTH)));
        }
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(logo);
        root.add(Box.createVerticalStrut(8));

        JLabel title = new JLabel("密匣 MimaVault");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(title);
        root.add(Box.createVerticalStrut(4));

        JLabel ver = new JLabel("版本 " + version);
        ver.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        ver.setForeground(new Color(0x666666));
        ver.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(ver);
        root.add(Box.createVerticalStrut(10));

        JLabel desc = new JLabel("轻量化本地密码管理程序 · 数据全部本地加密存储");
        desc.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        desc.setForeground(new Color(0x777777));
        desc.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(desc);
        root.add(Box.createVerticalStrut(14));

        root.add(separator());
        root.add(Box.createVerticalStrut(10));

        // ---------- DSH 插件简介 ----------
        JLabel pluginHead = new JLabel("配套插件：dsh-mimavault");
        pluginHead.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        pluginHead.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(pluginHead);
        root.add(Box.createVerticalStrut(4));

        JLabel pluginDesc = new JLabel(
                "<html><div style='text-align:center;width:380px'>"
                        + "把密匣接进 DeepSeek Harness：让 DSH 能检索本库的密码、API Key 与动态验证码，"
                        + "<b>明文默认不进入模型对话</b>。<br>"
                        + "需先在托盘菜单勾选 <b>Local API (DSH)</b>。"
                        + "</div></html>");
        pluginDesc.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        pluginDesc.setForeground(new Color(0x666666));
        pluginDesc.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(pluginDesc);
        root.add(Box.createVerticalStrut(6));

        JLabel pluginLink = linkLabel("插件仓库：" + PLUGIN_HOMEPAGE, PLUGIN_HOMEPAGE);
        pluginLink.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(pluginLink);

        root.add(Box.createVerticalStrut(14));

        // ---------- 操作按钮 ----------
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.CENTER_ALIGNMENT);

        GradientButton changeMasterBtn = GradientButton.secondary("修改主密码");
        changeMasterBtn.setToolTipText("验证当前主密码后，把整个库重新加密到新主密码");
        changeMasterBtn.addActionListener(e -> {
            dispose();
            if (onChangeMaster != null) {
                onChangeMaster.run();
            }
        });

        GradientButton updateBtn = GradientButton.accent("检查更新");
        updateBtn.setToolTipText("检查是否有新版本（程序内下载并静默安装）");
        updateBtn.addActionListener(e -> {
            dispose();
            if (onCheckUpdate != null) {
                onCheckUpdate.run();
            }
        });

        GradientButton closeBtn = GradientButton.secondary("关闭");
        closeBtn.addActionListener(e -> dispose());

        buttons.add(changeMasterBtn);
        buttons.add(updateBtn);
        buttons.add(closeBtn);
        root.add(buttons);
        root.add(Box.createVerticalStrut(12));

        // ---------- 作者 ----------
        JPanel authorRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
        authorRow.setOpaque(false);
        authorRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel by = new JLabel("作者：");
        by.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        by.setForeground(new Color(0x888888));
        authorRow.add(by);
        authorRow.add(linkLabel(AUTHOR_HOMEPAGE, AUTHOR_HOMEPAGE));
        root.add(authorRow);

        setContentPane(root);
        pack();
        setMinimumSize(new Dimension(Math.max(460, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(closeBtn);
    }

    /** 当前展示的版本号 */
    public String getVersion() {
        return version;
    }

    /** 细分割线 */
    private static JSeparator separator() {
        JSeparator s = new JSeparator();
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        s.setForeground(new Color(0xDDDDDD));
        return s;
    }

    /** 可点击的链接样式标签 */
    private JLabel linkLabel(String text, String url) {
        JLabel link = new JLabel("<html><u>" + text + "</u></html>");
        link.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        link.setForeground(new Color(0x1E88E5));
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText("点击用浏览器打开 " + url);
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent ev) {
                openUrl(url);
            }
        });
        return link;
    }

    /** 打开链接；无浏览器时退化为复制到剪贴板 */
    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception ignored) {
            // 落到下面的兜底
        }
        ClipboardSafe.copy(url);
        JOptionPane.showMessageDialog(this,
                "无法自动打开浏览器，地址已复制到剪贴板：\n" + url,
                "打开链接", JOptionPane.INFORMATION_MESSAGE);
    }
}
