package com.mimavault.ui;

import com.mimavault.model.Entry;
import com.mimavault.service.PasswordService;
import com.mimavault.util.AesUtil;
import com.mimavault.util.ClipboardSafe;
import com.mimavault.util.ImageUtil;
import com.mimavault.util.PasswordGenerator;
import com.mimavault.util.TextBatchParser;

import javax.crypto.SecretKey;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能导入 - 编辑条目对话框（逐条确认）
 * - 顶部展示来源原文（可 A+/A- 调整字号），有图片时显示缩略图，点击可放大查看
 * - 每条记录以编辑表单展示，已识别字段自动填入
 * - 存在未识别字段（平台/账号/密码任一为空）时弹出提示「部分数据未能识别，请手动输入」
 * - 确认 / 跳过逐条处理，最后统一按 平台+账号 去重入库
 */
public class ImportPreviewDialog extends JDialog {

    private final PasswordService service;
    private final SecretKey key;
    private final List<TextBatchParser.RawRecord> records;
    private final String sourceText;       // 用户使用的来源原文
    private final List<Path> images;       // 来源图片路径（预览/放大）
    private final Set<String> existingKeys = new HashSet<>();
    private final Set<String> confirmedKeys = new HashSet<>();
    private final boolean[] confirmed;   // 每条是否确认导入
    private final boolean[] prompted;    // 每条是否已弹过未识别提示

    private final JComboBox<String> categoryBox = new JComboBox<>(Entry.CATEGORIES);
    private final JTextField platformField = new JTextField(22);
    private final JTextField accountField = new JTextField(22);
    private final JTextField passwordField = new JTextField(22);
    private final JTextField phoneField = new JTextField(22);
    private final JTextField emailField = new JTextField(22);
    private final JTextArea noteArea = new JTextArea(3, 22);
    private final JLabel gestureLabel = new JLabel("未设置");
    private final JLabel progressLabel = new JLabel();
    private final JLabel sourceLabel = new JLabel();
    private final JTextArea sourceArea = new JTextArea(4, 40);
    private final GradientButton prevBtn = GradientButton.secondary("上一条");
    private final GradientButton skipBtn = GradientButton.secondary("跳过此条");
    private final GradientButton nextBtn = GradientButton.primary("确认导入此条");

    private int current = 0;
    private int sourceFontSize = 13;
    private String gestureSeq;    // 当前条目的手势序列

    public ImportPreviewDialog(Window owner, SmartImportDialog.Result result,
                               PasswordService service, SecretKey key) {
        super(owner, "智能导入 - 编辑条目", ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.key = key;
        this.records = result.records;
        this.sourceText = result.sourceText;
        this.images = result.images;
        this.confirmed = new boolean[records.size()];
        this.prompted = new boolean[records.size()];
        setIconImage(UiTheme.getAppIcon());

        for (Entry e : service.listEntries()) {
            existingKeys.add(keyOf(e));
        }

        setLayout(new BorderLayout(10, 10));

        // 顶部：进度 + 来源 + 来源原文（A+/A- 调整字号）+ 图片缩略图（点击放大）
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        progressLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        progressLabel.setForeground(UiTheme.PRIMARY_DARK);
        sourceLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        sourceLabel.setForeground(UiTheme.TEXT_SUB);
        JPanel topLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        topLeft.setOpaque(false);
        topLeft.add(progressLabel);
        topLeft.add(sourceLabel);
        top.add(topLeft, BorderLayout.WEST);
        JPanel fontPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        fontPanel.setOpaque(false);
        fontPanel.add(new JLabel("原文字号："));
        GradientButton fontDown = GradientButton.secondary("A-");
        fontDown.setToolTipText("缩小来源原文");
        fontDown.addActionListener(e -> setSourceFont(sourceFontSize - 2));
        GradientButton fontUp = GradientButton.secondary("A+");
        fontUp.setToolTipText("放大来源原文");
        fontUp.addActionListener(e -> setSourceFont(sourceFontSize + 2));
        fontPanel.add(fontDown);
        fontPanel.add(fontUp);
        top.add(fontPanel, BorderLayout.EAST);

        sourceArea.setEditable(false);
        sourceArea.setLineWrap(true);
        sourceArea.setWrapStyleWord(true);
        sourceArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, sourceFontSize));
        sourceArea.setText(sourceText);
        sourceArea.setCaretPosition(0);
        JScrollPane sourceScroll = new JScrollPane(sourceArea);
        sourceScroll.setPreferredSize(new Dimension(440, 110));

        JPanel sourcePanel = new JPanel(new BorderLayout(6, 4));
        sourcePanel.setOpaque(false);
        sourcePanel.setBorder(BorderFactory.createTitledBorder("来源内容（点击图片可放大）"));
        sourcePanel.add(sourceScroll, BorderLayout.CENTER);
        if (images != null && !images.isEmpty()) {
            sourcePanel.add(buildImageRow(), BorderLayout.SOUTH);
        }
        JPanel topBox = new JPanel(new BorderLayout(6, 4));
        topBox.setOpaque(false);
        topBox.add(top, BorderLayout.NORTH);
        topBox.add(sourcePanel, BorderLayout.CENTER);
        add(topBox, BorderLayout.NORTH);

        // 中部：编辑表单
        RoundedPanel form = new RoundedPanel(16);
        form.setLayout(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("分类："), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(categoryBox, gbc);
        gbc.fill = GridBagConstraints.NONE;

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("平台 *："), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(platformField, gbc);
        gbc.fill = GridBagConstraints.NONE;

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("账号："), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(accountField, gbc);
        gbc.fill = GridBagConstraints.NONE;

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("密码："), gbc);
        JPanel pwdPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        pwdPanel.setOpaque(false);
        pwdPanel.add(passwordField);
        // 密码框右侧手势入口：点进去录入手势密码
        GradientButton gestureBtn = GradientButton.secondary("手势");
        gestureBtn.setToolTipText("为此条目设置手势密码");
        gestureBtn.addActionListener(e -> {
            String seq = GestureDialog.showDialog(ImportPreviewDialog.this, gestureSeq);
            if (seq != null) {
                gestureSeq = seq;
                gestureLabel.setText(seq);
            }
        });
        GradientButton clearGestureBtn = GradientButton.secondary("清除手势");
        clearGestureBtn.addActionListener(e -> {
            gestureSeq = null;
            gestureLabel.setText("未设置");
        });
        gestureLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        pwdPanel.add(gestureBtn);
        pwdPanel.add(clearGestureBtn);
        // 密码生成器：打开生成器对话框（随机密码 / 口令短语，可排除易混淆字符、自定义字符集），确认后填入并复制
        GradientButton genPwdBtn = GradientButton.secondary("打开密码生成器");
        genPwdBtn.setToolTipText("随机密码（可排除易混淆字符、自定义字符集）或口令短语（本地词表），确认后填入并复制到剪贴板");
        genPwdBtn.addActionListener(e -> {
            String pwd = PasswordGeneratorDialog.showDialog(ImportPreviewDialog.this, "密码生成器");
            if (pwd == null || pwd.isEmpty()) {
                return;
            }
            passwordField.setText(pwd);
            ClipboardSafe.copySecret(pwd);
            Toast.show(ImportPreviewDialog.this, "已生成密码并复制到剪贴板");
        });
        pwdPanel.add(genPwdBtn);
        pwdPanel.add(gestureLabel);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(pwdPanel, gbc);
        gbc.fill = GridBagConstraints.NONE;

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("手机号："), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(phoneField, gbc);
        gbc.fill = GridBagConstraints.NONE;

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("邮箱："), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(emailField, gbc);
        gbc.fill = GridBagConstraints.NONE;

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("备注："), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        form.add(new JScrollPane(noteArea), gbc);
        gbc.fill = GridBagConstraints.NONE;

        add(form, BorderLayout.CENTER);

        // 底部按钮
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bottom.setOpaque(false);
        prevBtn.setEnabled(false);
        prevBtn.addActionListener(e -> {
            saveCurrentToEntry();
            if (current > 0) {
                showRecord(current - 1);
            }
        });
        skipBtn.addActionListener(e -> {
            if (current >= records.size() - 1) {
                finishImport();
            } else {
                current++;
                showRecord(current);
            }
        });
        nextBtn.addActionListener(e -> {
            saveCurrentToEntry();
            if (current >= records.size() - 1) {
                confirmed[current] = true;
                finishImport();
            } else {
                confirmed[current] = true;
                current++;
                showRecord(current);
            }
        });
        bottom.add(prevBtn);
        bottom.add(skipBtn);
        bottom.add(nextBtn);
        add(bottom, BorderLayout.SOUTH);

        showRecord(0);
        pack();
        setSize(Math.max(getWidth(), 520), Math.min(Math.max(getHeight(), 600), 780));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(true);
        getRootPane().setDefaultButton(nextBtn);
    }

    /** 来源图片缩略图行：点击打开大图查看（滚轮缩放） */
    private JPanel buildImageRow() {
        JPanel imgRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        imgRow.setOpaque(false);
        for (Path p : images) {
            if (p == null || !Files.exists(p)) {
                continue;
            }
            ImageIcon icon = ImageUtil.loadScaledIcon(p, 90);
            if (icon == null) {
                continue;
            }
            JLabel thumb = new JLabel(icon);
            thumb.setToolTipText("点击查看大图（滚轮可缩放）");
            thumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Path imgPath = p;
            thumb.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    showImageViewer(imgPath);
                }
            });
            imgRow.add(thumb);
        }
        return imgRow;
    }

    /** 大图查看窗口：原图 + 鼠标滚轮缩放 */
    private void showImageViewer(Path path) {
        JDialog dlg = new JDialog(this, "查看原图", ModalityType.APPLICATION_MODAL);
        dlg.setIconImage(UiTheme.getAppIcon());
        ImageIcon raw = new ImageIcon(path.toString());
        final double[] scale = {1.0};
        JLabel label = new JLabel(raw, SwingConstants.CENTER);
        label.addMouseWheelListener(e -> {
            double delta = e.getPreciseWheelRotation() < 0 ? 1.1 : 0.9;
            scale[0] = Math.max(0.15, Math.min(5.0, scale[0] * delta));
            int w = (int) Math.max(1, raw.getIconWidth() * scale[0]);
            int h = (int) Math.max(1, raw.getIconHeight() * scale[0]);
            label.setIcon(new ImageIcon(raw.getImage().getScaledInstance(w, h, Image.SCALE_SMOOTH)));
            label.revalidate();
        });
        JScrollPane sp = new JScrollPane(label);
        sp.setPreferredSize(new Dimension(820, 620));
        dlg.add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        bottom.setOpaque(false);
        JLabel tip = new JLabel("滚轮缩放，拖动滚动条查看细节");
        tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        GradientButton close = GradientButton.secondary("关闭");
        close.addActionListener(e -> dlg.dispose());
        bottom.add(tip);
        bottom.add(close);
        dlg.add(bottom, BorderLayout.SOUTH);

        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    /** 调整来源原文显示字号（10~28） */
    private void setSourceFont(int size) {
        sourceFontSize = Math.max(10, Math.min(28, size));
        sourceArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, sourceFontSize));
    }

    /** 展示第 index 条记录；存在未识别字段时弹窗提示（每条最多一次） */
    private void showRecord(int index) {
        current = index;
        TextBatchParser.RawRecord rec = records.get(index);
        Entry e = rec.entry;

        categoryBox.setSelectedItem(e.getCategory() == null ? Entry.CATEGORY_WEBSITE : e.getCategory());
        platformField.setText(nullToEmpty(e.getPlatform()));
        accountField.setText(nullToEmpty(e.getAccount()));
        passwordField.setText(nullToEmpty(e.getPasswordEnc())); // 草稿阶段明文暂存于此
        phoneField.setText(nullToEmpty(e.getPhone()));
        emailField.setText(nullToEmpty(e.getEmail()));
        noteArea.setText(nullToEmpty(e.getNote()));

        gestureSeq = e.getGestureSeq();
        gestureLabel.setText(isBlank(gestureSeq) ? "未设置" : gestureSeq);

        progressLabel.setText("第 " + (index + 1) + " / " + records.size() + " 条");
        sourceLabel.setText("来源：" + (rec.sourceType == null || rec.sourceType.isEmpty() ? "未知" : rec.sourceType));

        boolean last = index == records.size() - 1;
        prevBtn.setEnabled(index > 0);
        nextBtn.setText(last ? "确认并完成导入" : "确认导入此条");
        skipBtn.setText(last ? "跳过并完成导入" : "跳过此条");

        if (!prompted[index] && hasMissingField(e)) {
            prompted[index] = true;
            JOptionPane.showMessageDialog(this,
                    "部分数据未能识别，请手动输入",
                    "提示", JOptionPane.WARNING_MESSAGE);
        }
        platformField.requestFocusInWindow();
    }

    /** 关键字段（平台/账号/密码）任一为空视为存在未识别数据 */
    private boolean hasMissingField(Entry e) {
        return isBlank(e.getPlatform()) || isBlank(e.getAccount()) || isBlank(e.getPasswordEnc());
    }

    /** 将当前表单值写回当前条目 */
    private void saveCurrentToEntry() {
        Entry e = records.get(current).entry;
        e.setCategory((String) categoryBox.getSelectedItem());
        e.setPlatform(platformField.getText().trim());
        e.setAccount(accountField.getText().trim());
        e.setPasswordEnc(passwordField.getText().trim());
        e.setPhone(phoneField.getText().trim());
        e.setEmail(emailField.getText().trim());
        e.setNote(noteArea.getText().trim());
        e.setGestureSeq(gestureSeq);
    }

    /** 汇总已确认条目，去重加密后统一入库 */
    private void finishImport() {
        List<Entry> toInsert = new ArrayList<>();
        int skippedCount = 0;
        int duplicateSkipped = 0;
        for (int i = 0; i < records.size(); i++) {
            if (!confirmed[i]) {
                skippedCount++;
                continue;
            }
            Entry e = records.get(i).entry;
            String k = keyOf(e);
            if (existingKeys.contains(k) || confirmedKeys.contains(k)) {
                duplicateSkipped++;
                continue;
            }
            confirmedKeys.add(k);

            // 明文密码加密
            String plain = e.getPasswordEnc();
            if (plain != null && !plain.isEmpty()) {
                e.setPasswordEnc(AesUtil.encrypt(plain, key));
            } else {
                e.setPasswordEnc(null);
            }
            toInsert.add(e);
        }

        if (toInsert.isEmpty() && duplicateSkipped == 0) {
            JOptionPane.showMessageDialog(this, "未确认任何记录，未执行导入", "提示", JOptionPane.INFORMATION_MESSAGE);
            dispose();
            return;
        }

        if (!toInsert.isEmpty()) {
            service.insertAll(toInsert);
        }
        dispose();
        StringBuilder msg = new StringBuilder("导入完成：成功 " + toInsert.size() + " 条");
        if (skippedCount > 0) {
            msg.append("，跳过 ").append(skippedCount).append(" 条");
        }
        if (duplicateSkipped > 0) {
            msg.append("，重复跳过 ").append(duplicateSkipped).append(" 条");
        }
        JOptionPane.showMessageDialog(this, msg.toString(), "导入完成", JOptionPane.INFORMATION_MESSAGE);
    }

    private String keyOf(Entry e) {
        return nullToEmpty(e.getPlatform()) + "|" + nullToEmpty(e.getAccount());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
