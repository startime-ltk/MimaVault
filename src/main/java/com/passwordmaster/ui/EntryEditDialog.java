package com.passwordmaster.ui;

import com.passwordmaster.config.AppConfig;
import com.passwordmaster.model.Entry;
import com.passwordmaster.util.DragDropUtil;
import com.passwordmaster.util.ImageUtil;
import com.passwordmaster.util.OcrUtil;
import com.passwordmaster.util.TextParser;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 新增 / 编辑条目对话框
 * - 平台、账号、密码（可留空）、手机、邮箱、备注
 * - 分类下拉：网站 / 应用 / 其他
 * - 附件截图：选择本地图片复制到 data/images/
 * - 手势密码：可录入手势
 * - 上传了截图则密码可不填
 */
public class EntryEditDialog extends JDialog {

    private final Entry entry;
    private final boolean isEdit;
    private final JComboBox<String> categoryBox = new JComboBox<>(Entry.CATEGORIES);
    private final JTextField platformField = new JTextField(20);
    private final JTextField accountField = new JTextField(20);
    private final JPasswordField passwordField = new JPasswordField(20);
    private final JTextField phoneField = new JTextField(20);
    private final JTextField emailField = new JTextField(20);
    private final JTextArea noteArea = new JTextArea(3, 20);
    private final JLabel imageLabel = new JLabel("未选择");
    private final JLabel gestureLabel = new JLabel("未设置");
    private String imagePath;     // 新选择的图片相对路径
    private String gestureSeq;    // 手势序列
    private boolean saved = false;

    public EntryEditDialog(Window owner, Entry entry) {
        super(owner, entry == null ? "新增条目" : "编辑条目", ModalityType.APPLICATION_MODAL);
        this.entry = entry == null ? new Entry() : entry;
        this.isEdit = entry != null;
        this.imagePath = this.entry.getImagePath();
        setIconImage(UiTheme.getAppIcon());
        this.gestureSeq = this.entry.getGestureSeq();

        setLayout(new BorderLayout());
        RoundedPanel form = new RoundedPanel(16);
        form.setLayout(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("分类："), gbc);
        gbc.gridx = 1;
        form.add(categoryBox, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("平台 *："), gbc);
        gbc.gridx = 1;
        form.add(platformField, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("账号："), gbc);
        gbc.gridx = 1;
        form.add(accountField, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("密码："), gbc);
        gbc.gridx = 1;
        form.add(passwordField, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("手机号："), gbc);
        gbc.gridx = 1;
        form.add(phoneField, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("邮箱："), gbc);
        gbc.gridx = 1;
        form.add(emailField, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("备注："), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        noteArea.setLineWrap(true);
        form.add(new JScrollPane(noteArea), gbc);
        gbc.fill = GridBagConstraints.NONE;

        // 图片附件
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("截图附件："), gbc);
        JPanel imgPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        imgPanel.setOpaque(false);
        imageLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        GradientButton chooseImg = GradientButton.secondary("选择图片...");
        chooseImg.addActionListener(e -> chooseImage());
        GradientButton ocrFill = GradientButton.secondary("识图填充...");
        ocrFill.addActionListener(e -> ocrFill());
        GradientButton clearImg = GradientButton.secondary("移除");
        clearImg.addActionListener(e -> {
            imagePath = null;
            imageLabel.setText("未选择");
            imageLabel.setIcon(null);
        });
        imgPanel.add(chooseImg);
        imgPanel.add(ocrFill);
        imgPanel.add(clearImg);
        imgPanel.add(imageLabel);
        gbc.gridx = 1;
        form.add(imgPanel, gbc);
        // 图片区域支持拖入图片：设置附件并自动触发识图填充
        DragDropUtil.installImageDrop(imgPanel, (images, ignored) -> {
            if (images.isEmpty()) {
                if (!ignored.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                            "仅支持图片文件（png/jpg/jpeg/bmp/gif/webp）", "提示", JOptionPane.WARNING_MESSAGE);
                }
                return;
            }
            handleDropImage(images.get(0));
        });

        // 手势密码
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("手势密码："), gbc);
        JPanel gesturePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        gesturePanel.setOpaque(false);
        gestureLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        GradientButton recordGesture = GradientButton.secondary("录入手势...");
        recordGesture.addActionListener(e -> {
            String seq = GestureDialog.showDialog(this, gestureSeq);
            if (seq != null) {
                gestureSeq = seq;
                gestureLabel.setText(seq);
            }
        });
        GradientButton clearGesture = GradientButton.secondary("清除");
        clearGesture.addActionListener(e -> {
            gestureSeq = null;
            gestureLabel.setText("未设置");
        });
        gesturePanel.add(recordGesture);
        gesturePanel.add(clearGesture);
        gesturePanel.add(gestureLabel);
        gbc.gridx = 1;
        form.add(gesturePanel, gbc);

        add(form, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.setOpaque(false);
        GradientButton ok = GradientButton.primary("保存");
        ok.addActionListener(e -> onSave());
        GradientButton cancel = GradientButton.secondary("取消");
        cancel.addActionListener(e -> dispose());
        bottom.add(ok);
        bottom.add(cancel);
        add(bottom, BorderLayout.SOUTH);

        loadValues();
        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
        getRootPane().setDefaultButton(ok);
    }

    private void loadValues() {
        categoryBox.setSelectedItem(entry.getCategory() == null ? Entry.CATEGORY_WEBSITE : entry.getCategory());
        platformField.setText(nullToEmpty(entry.getPlatform()));
        accountField.setText(nullToEmpty(entry.getAccount()));
        phoneField.setText(nullToEmpty(entry.getPhone()));
        emailField.setText(nullToEmpty(entry.getEmail()));
        noteArea.setText(nullToEmpty(entry.getNote()));
        if (isEdit && entry.getPasswordEnc() != null && !entry.getPasswordEnc().isEmpty()) {
            passwordField.setText("********");
        }
        if (imagePath != null && !imagePath.isEmpty()) {
            Path p = ImageUtil.resolveImagePath(imagePath);
            ImageIcon icon = ImageUtil.loadScaledIcon(p, 80);
            if (icon != null) {
                imageLabel.setIcon(icon);
                imageLabel.setText("（已有截图）");
            } else {
                imageLabel.setText(imagePath);
            }
        }
        if (gestureSeq != null && !gestureSeq.isEmpty()) {
            gestureLabel.setText(gestureSeq);
        }
    }

    private void chooseImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择截图附件");
        chooser.setFileFilter(new FileNameExtensionFilter("图片文件 (jpg/png/bmp/gif/webp)", "jpg", "jpeg", "png", "bmp", "gif", "webp"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path source = chooser.getSelectedFile().toPath();
            if (!ImageUtil.isSupportedImage(source)) {
                JOptionPane.showMessageDialog(this, "不支持的图片格式", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String rel = ImageUtil.importImageToData(source);
            if (rel != null) {
                imagePath = rel;
                ImageIcon icon = ImageUtil.loadScaledIcon(AppConfig.DATA_DIR.resolve(rel), 80);
                imageLabel.setIcon(icon);
                imageLabel.setText(source.getFileName().toString());
            } else {
                JOptionPane.showMessageDialog(this, "图片复制失败", "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** 拖入图片：复制为附件并自动触发识图填充（复用 ocrFill 逻辑） */
    private void handleDropImage(File f) {
        Path source = f.toPath();
        if (!ImageUtil.isSupportedImage(source)) {
            JOptionPane.showMessageDialog(this, "不支持的图片格式", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String rel = ImageUtil.importImageToData(source);
        if (rel == null) {
            JOptionPane.showMessageDialog(this, "图片复制失败", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        imagePath = rel;
        ImageIcon icon = ImageUtil.loadScaledIcon(AppConfig.DATA_DIR.resolve(rel), 80);
        if (icon != null) {
            imageLabel.setIcon(icon);
        }
        imageLabel.setText(f.getName());
        // 附件已就绪，ocrFill 会优先复用 imagePath，不再弹文件选择器
        ocrFill();
    }

    /** 识图填充：OCR 识别截图并自动填充字段（只填充识别到的字段） */
    private void ocrFill() {
        // 1. 确定图片：优先复用已选图片，否则弹出文件选择器
        Path source;
        if (imagePath != null && !imagePath.isEmpty()) {
            source = ImageUtil.resolveImagePath(imagePath);
            if (!Files.exists(source)) {
                source = null;
            }
        } else {
            source = null;
        }
        if (source == null) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择要识别的截图");
            chooser.setFileFilter(new FileNameExtensionFilter("图片文件 (jpg/png/bmp/gif/webp)", "jpg", "jpeg", "png", "bmp", "gif", "webp"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            source = chooser.getSelectedFile().toPath();
            if (!ImageUtil.isSupportedImage(source)) {
                JOptionPane.showMessageDialog(this, "不支持的图片格式", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String rel = ImageUtil.importImageToData(source);
            if (rel != null) {
                imagePath = rel;
                ImageIcon icon = ImageUtil.loadScaledIcon(AppConfig.DATA_DIR.resolve(rel), 80);
                imageLabel.setIcon(icon);
                imageLabel.setText(source.getFileName().toString());
            }
        }

        // 2. OCR 识别（首次自动释放内置语言包，完全零联网）
        final Path ocrFile = source;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            String text = OcrUtil.doOcr(ocrFile.toFile());
            if (text == null || text.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "未从图片中识别到文本，请换一张更清晰的截图", "识图结果", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            // 3. 正则提取字段
            java.util.Map<String, String> fields = TextParser.parse(text);

            // 4. 只填充识别到的字段
            String platform = fields.getOrDefault("platform", "");
            String account = fields.getOrDefault("account", "");
            String password = fields.getOrDefault("password", "");
            String phone = fields.getOrDefault("phone", "");
            String email = fields.getOrDefault("email", "");

            boolean changed = false;
            if (!platform.isEmpty()) {
                platformField.setText(platform);
                changed = true;
            }
            if (!account.isEmpty()) {
                accountField.setText(account);
                changed = true;
            }
            if (!password.isEmpty()) {
                passwordField.setText(password);
                changed = true;
            }
            if (!phone.isEmpty()) {
                phoneField.setText(phone);
                changed = true;
            }
            if (!email.isEmpty()) {
                emailField.setText(email);
                changed = true;
            }

            // 5. 摘要弹窗供用户确认修正
            StringBuilder sb = new StringBuilder("识别完成：");
            sb.append("平台=").append(platform.isEmpty() ? "未识别" : platform);
            sb.append("，账号=").append(account.isEmpty() ? "未识别" : account);
            sb.append("，密码=").append(password.isEmpty() ? "未识别" : password);
            sb.append("，手机=").append(phone.isEmpty() ? "未识别" : phone);
            sb.append("，邮箱=").append(email.isEmpty() ? "未识别" : email);
            if (!changed) {
                sb.append("\n未识别到可填充的字段，请检查图片清晰度后重试。");
            } else {
                sb.append("\n已自动填充识别到的字段，请核对并手动修正。");
            }
            JOptionPane.showMessageDialog(this, sb.toString(), "识图结果", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            String msg = ex.getMessage();
            if (msg == null || msg.trim().isEmpty()) {
                msg = ex.toString();
            }
            JOptionPane.showMessageDialog(this, "OCR 识别失败：\n" + msg, "识图失败", JOptionPane.ERROR_MESSAGE);
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private void onSave() {
        String platform = platformField.getText().trim();
        if (platform.isEmpty()) {
            JOptionPane.showMessageDialog(this, "平台名称不能为空", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        char[] pwdChars = passwordField.getPassword();
        String password;
        try {
            password = new String(pwdChars).trim();
        } finally {
            java.util.Arrays.fill(pwdChars, '\0'); // 明文用完即清
        }
        boolean hasPassword = password.length() > 0 && !"********".equals(password);
        boolean hasImage = imagePath != null && !imagePath.isEmpty();
        boolean keepOldPassword = isEdit && "********".equals(password);

        // 密码可留空：仅当有截图或已有密码时允许
        if (!hasPassword && !hasImage && !(isEdit && keepOldPassword)) {
            int r = JOptionPane.showConfirmDialog(this,
                    "密码为空且未上传截图，确定保存吗？",
                    "确认", JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) {
                return;
            }
        }

        entry.setCategory((String) categoryBox.getSelectedItem());
        entry.setPlatform(platform);
        entry.setAccount(accountField.getText().trim());
        entry.setPhone(phoneField.getText().trim());
        entry.setEmail(emailField.getText().trim());
        entry.setNote(noteArea.getText().trim());
        entry.setImagePath(imagePath);
        entry.setGestureSeq(gestureSeq);
        saved = true;
        passwordPlain = hasPassword ? password : null;
        dispose();
    }

    private String passwordPlain;

    public boolean isSaved() {
        return saved;
    }

    public Entry getEntry() {
        return entry;
    }

    /** 用户新输入的明文密码；null 表示未修改/为空 */
    public String getPlainPassword() {
        return passwordPlain;
    }

    /** 是否保留原密码（编辑时密码框未改动） */
    public boolean isKeepOldPassword() {
        char[] pwdChars = passwordField.getPassword();
        try {
            return isEdit && new String(pwdChars).equals("********");
        } finally {
            java.util.Arrays.fill(pwdChars, '\0'); // 明文用完即清
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
