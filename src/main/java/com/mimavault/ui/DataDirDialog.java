package com.mimavault.ui;

import com.mimavault.config.AppConfig;
import com.mimavault.util.DataDirectoryUtil;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;

/**
 * 数据迁移对话框（v8.30.1）
 *
 * 用途：把密匣的全部数据（MimaVault.db 密码库、config.json、images 图片附件、
 *       backup 自动备份、temp、tessdata）整体迁移/转移到电脑的另一个磁盘或文件夹。
 *  - 源 = 当前数据目录（默认程序运行目录/data，与旧版本一致）；
 *  - 迁移 = 完整复制源目录到用户选择的新位置（可跨盘）→ 更新全局定位文件 →
 *    重启程序后所有读写自动切换新位置；
 *  - 旧目录数据保留（核验备份），确认新位置正常后由用户手动删除；
 *  - 支持任意次迁移；升级旧版本时无定位文件，自动回退默认 data，无需手动迁移。
 */
public class DataDirDialog extends JDialog {

    private final JTextArea currentPathArea;

    public DataDirDialog(JFrame owner) {
        super(owner, "数据迁移", true);
        setResizable(false);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        root.setBackground(Color.WHITE);

        // 标题区
        JLabel title = new JLabel("数据迁移");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 17));
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        header.setOpaque(false);
        header.add(title);
        root.add(header, BorderLayout.NORTH);

        // 中间说明 + 当前路径
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);

        JTextArea tip = new JTextArea(
                "把密匣的全部数据整体迁移/转移到电脑的另一个磁盘或文件夹（可跨盘），包括：\n"
                        + "  · 密码库 MimaVault.db     · 配置 config.json\n"
                        + "  · 图片附件 images         · 自动备份 backup（及 temp、OCR tessdata）\n"
                        + "默认位置为程序目录下的 data 文件夹（与旧版本一致，可直接升级）。\n"
                        + "迁移后请重启程序生效；旧位置数据会保留作为核验备份，确认新位置正常后\n"
                        + "可在资源管理器中手动删除旧目录。");
        tip.setEditable(false);
        tip.setOpaque(false);
        tip.setLineWrap(true);
        tip.setWrapStyleWord(true);
        tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        tip.setForeground(new Color(0x555555));
        tip.setRows(6);
        body.add(tip, BorderLayout.NORTH);

        JLabel curLabel = new JLabel("当前数据位置（迁移源）：");
        curLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        JPanel curRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        curRow.setOpaque(false);
        curRow.add(curLabel);

        currentPathArea = new JTextArea(AppConfig.DATA_DIR.toAbsolutePath().toString());
        currentPathArea.setEditable(false);
        currentPathArea.setLineWrap(true);
        currentPathArea.setWrapStyleWord(true);
        currentPathArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        currentPathArea.setBackground(new Color(0xF6F7FB));
        currentPathArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        currentPathArea.setRows(2);
        curRow.add(currentPathArea);

        JPanel curPanel = new JPanel(new BorderLayout());
        curPanel.setOpaque(false);
        curPanel.add(curLabel, BorderLayout.NORTH);
        curPanel.add(currentPathArea, BorderLayout.CENTER);
        body.add(curPanel, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);

        // 底部按钮
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        GradientButton migrateBtn = new GradientButton("选择新位置并迁移…",
                UiTheme.PRIMARY, UiTheme.PURPLE, Color.WHITE, UiTheme.PURPLE.darker());
        migrateBtn.setPreferredSize(new Dimension(180, 34));
        migrateBtn.addActionListener(e -> onMigrate());
        GradientButton closeBtn = GradientButton.secondary("关闭");
        closeBtn.setPreferredSize(new Dimension(90, 34));
        closeBtn.addActionListener(e -> dispose());
        buttons.add(migrateBtn);
        buttons.add(closeBtn);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    private void onMigrate() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择迁移目标位置（可跨磁盘，请选择空文件夹或新建文件夹）");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        Path current = AppConfig.DATA_DIR.toAbsolutePath();
        try {
            File init = current.getParent() == null ? new File(".") : current.getParent().toFile();
            chooser.setCurrentDirectory(init);
        } catch (Exception ignored) {
            // 忽略无法访问的默认位置
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();

        // 校验（拒绝：与源相同/源内部/包含源的上层/程序安装目录内/目标非空）
        String err = DataDirectoryUtil.validateTarget(target, current);
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "无法迁移到该位置", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 迁移确认（复制语义 + 数据清单 + 旧数据保留 + 重启生效）
        String source = current.toString();
        String dest = target.toAbsolutePath().normalize().toString();
        int confirm = JOptionPane.showConfirmDialog(this,
                "将把全部数据从当前位置迁移到：\n" + source + "\n        ↓ 迁 移 ↓\n" + dest + "\n\n"
                        + "迁移内容：MimaVault.db 密码库、config.json 配置、images 图片附件、\n"
                        + "          backup 自动备份、temp 与 OCR tessdata 等全部数据。\n"
                        + "执行方式：完整复制到目标位置并记录新位置，重启程序后读写自动切换；\n"
                        + "旧位置数据将保留（作为核验备份，可稍后手动删除）。\n\n是否开始迁移？",
                "确认数据迁移", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        setEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                DataDirectoryUtil.migrate(target);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    currentPathArea.setText(AppConfig.DATA_DIR.toAbsolutePath().toString());
                    JOptionPane.showMessageDialog(DataDirDialog.this,
                            "数据迁移完成！\n\n新位置：\n" + AppConfig.DATA_DIR.toAbsolutePath() + "\n\n"
                                    + "请重启程序（退出后重新打开），所有读写将自动切换到新位置。\n"
                                    + "旧位置数据已保留于：\n" + source + "\n"
                                    + "确认新位置正常后，可在资源管理器中手动删除该旧目录以释放空间。",
                            "数据迁移完成", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(DataDirDialog.this,
                            "数据迁移失败：\n" + ex.getMessage() + "\n\n数据仍在原位置，未受影响，可重试或更换目标位置。",
                            "迁移失败", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setEnabled(true);
                    setCursor(Cursor.getDefaultCursor());
                }
            }
        }.execute();
    }
}
