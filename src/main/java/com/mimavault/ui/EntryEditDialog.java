package com.mimavault.ui;

import com.mimavault.config.AppConfig;
import com.mimavault.model.Entry;
import com.mimavault.util.AesUtil;
import com.mimavault.util.ClipboardSafe;
import com.mimavault.util.DragDropUtil;
import com.mimavault.util.ImageUtil;
import com.mimavault.util.OcrUtil;
import com.mimavault.util.PasswordGenerator;
import com.mimavault.util.TextParser;
import com.mimavault.util.TotpUtil;

import javax.crypto.SecretKey;
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
    private final JTextField passwordField = new JTextField(20);
    private final JTextField phoneField = new JTextField(20);
    private final JTextField emailField = new JTextField(20);
    private final JTextArea noteArea = new JTextArea(3, 20);
    private final JLabel imageLabel = new JLabel("未选择");
    private final JLabel gestureLabel = new JLabel("未设置");
    private final JLabel totpLabel = new JLabel("未绑定");
    // 分类专属字段（WiFi/银行卡/证件/API Key）：按分类 CardLayout 切换显示，保存时非空字段全部收集加密
    private final CardLayout extraCardLayout = new CardLayout();
    private final JPanel extraCard = new JPanel(extraCardLayout);
    private final java.util.Map<String, java.util.Map<String, JTextField>> extraFields = new java.util.HashMap<>();
    private final SecretKey key;      // 编辑时用于解密显示原密码；新增可传 null
    private String originalPlain;     // 编辑加载时的明文密码（用于判断是否修改）
    private boolean keepOld = false;  // 是否保留原密码密文
    private String imagePath;     // 新选择的图片相对路径
    private String gestureSeq;    // 手势序列
    private String totpUri;       // TOTP 规范化 otpauth 链接（明文，保存时 AES 加密落库）
    private boolean totpKeepOriginal; // 原 TOTP 密文解不开时置位：保存时原样保留，避免误清空
    private boolean saved = false;

    public EntryEditDialog(Window owner, Entry entry) {
        this(owner, entry, null);
    }

    public EntryEditDialog(Window owner, Entry entry, SecretKey key) {
        super(owner, entry == null ? "新增条目" : "编辑条目", ModalityType.APPLICATION_MODAL);
        this.entry = entry == null ? new Entry() : entry;
        this.isEdit = entry != null;
        this.key = key;
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
        JPanel pwdPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        pwdPanel.setOpaque(false);
        pwdPanel.add(passwordField);
        // 密码框右侧手势入口：点进去录入手势密码
        GradientButton gestureBtn = GradientButton.secondary("手势");
        gestureBtn.setToolTipText("为此条目设置手势密码");
        gestureBtn.addActionListener(e -> {
            String seq = GestureDialog.showDialog(this, gestureSeq);
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
            String pwd = PasswordGeneratorDialog.showDialog(EntryEditDialog.this, "密码生成器");
            if (pwd == null || pwd.isEmpty()) {
                return;
            }
            passwordField.setText(pwd);
            ClipboardSafe.copySecret(pwd);
            Toast.show(EntryEditDialog.this, "已生成密码并复制到剪贴板");
        });
        pwdPanel.add(genPwdBtn);
        pwdPanel.add(gestureLabel);
        gbc.gridx = 1;
        form.add(pwdPanel, gbc);

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

        // 分类专属字段区：按当前分类切换显示（WiFi/银行卡/证件/API Key），默认分类无专属字段
        initExtraCard();
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("专属字段："), gbc);
        gbc.gridx = 1;
        form.add(extraCard, gbc);
        categoryBox.addActionListener(e -> showExtraFor((String) categoryBox.getSelectedItem()));
        showExtraFor(entry.getCategory() == null ? Entry.CATEGORY_WEBSITE : entry.getCategory());

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

        // 动态验证码（TOTP，RFC 6238）：绑定 otpauth 链接或 Base32 密钥，客户端离线生成，与安卓端一致
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("动态验证码："), gbc);
        JPanel totpPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        totpPanel.setOpaque(false);
        GradientButton bindTotp = GradientButton.secondary("绑定验证器...");
        bindTotp.setToolTipText("粘贴 otpauth:// 链接或验证器 App 的 Base32 密钥，本机离线生成 30 秒动态验证码");
        bindTotp.addActionListener(e -> bindTotp());
        GradientButton clearTotp = GradientButton.secondary("清除");
        clearTotp.addActionListener(e -> {
            totpUri = null;
            totpKeepOriginal = false;
            totpLabel.setText("未绑定");
        });
        totpLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        totpPanel.add(bindTotp);
        totpPanel.add(clearTotp);
        totpPanel.add(totpLabel);
        gbc.gridx = 1;
        form.add(totpPanel, gbc);

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
            if (key != null) {
                String plain = AesUtil.decrypt(entry.getPasswordEnc(), key);
                if (plain != null) {
                    passwordField.setText(plain);
                    originalPlain = plain;
                }
            } else {
                passwordField.setText("（已加密，无法显示）");
            }
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
        if (entry.getTotpSecretEnc() != null && !entry.getTotpSecretEnc().isEmpty()) {
            if (key != null) {
                try {
                    String uri = AesUtil.decrypt(entry.getTotpSecretEnc(), key);
                    if (uri != null && !uri.isEmpty()) {
                        totpUri = uri;
                        totpLabel.setText(TotpUtil.parse(uri).label() + "（已绑定）");
                    } else {
                        totpKeepOriginal = true;
                    }
                } catch (Exception ex) {
                    totpUri = null;
                    totpKeepOriginal = true;
                    totpLabel.setText("（已绑定，解析失败）");
                }
            } else {
                totpLabel.setText("（已加密，无法显示）");
            }
        }

        // 分类专属字段：解密 JSON 后填充对应输入框（解不开保持空，不阻断编辑）
        if (entry.getExtraEnc() != null && !entry.getExtraEnc().isEmpty() && key != null) {
            try {
                String json = AesUtil.decrypt(entry.getExtraEnc(), key);
                java.util.Map<String, String> extra = Entry.decodeExtra(json);
                for (java.util.Map.Entry<String, String> kv : extra.entrySet()) {
                    JTextField tf = findExtraField(kv.getKey());
                    if (tf != null) {
                        tf.setText(kv.getValue() == null ? "" : kv.getValue());
                    }
                }
            } catch (Exception ignore) {
                // 无法解密：保持输入框为空
            }
        }
    }

    /**
     * 绑定动态验证码：粘贴 otpauth:// 链接或验证器 App 的 Base32 密钥，
     * 解析后即时生成一次验证码供用户与验证器 App 核对，确认无误才绑定（纯离线，不联网）。
     */
    private void bindTotp() {
        String hint = "<html>粘贴验证器 App 的<font color='#C62828'><b> otpauth:// 链接</b></font>，"
                + "或「手动输入密钥」里提供的 <font color='#C62828'><b>Base32 密钥</b></font>：<br>"
                + "支持 Google Authenticator / 微软 / Authy / 1Password 等（SHA1/SHA256/SHA512，6~8 位，默认 30 秒）。</html>";
        String input = (String) JOptionPane.showInputDialog(this, hint, "绑定动态验证码",
                JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (input == null) {
            return; // 用户取消
        }
        TotpUtil.Config c;
        try {
            c = TotpUtil.parse(input);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, "无法解析：" + ex.getMessage()
                    + "\n\n请输入完整的 otpauth://totp/... 链接，或验证器的 Base32 密钥。", "绑定失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        // 链接未带发行方/账号时，用当前编辑的平台/账号补齐，便于详情页显示
        if (c.issuer == null || c.issuer.trim().isEmpty()) {
            c.issuer = platformField.getText().trim();
        }
        if (c.account == null || c.account.trim().isEmpty()) {
            c.account = accountField.getText().trim();
        }
        String code = TotpUtil.generate(c, System.currentTimeMillis());
        int r = JOptionPane.showConfirmDialog(this,
                "已识别：" + c.label() + "\n"
                        + "当前验证码：" + TotpUtil.formatForDisplay(code)
                        + "（" + c.digits + " 位 / " + c.period + " 秒 / " + c.algorithm + "）\n\n"
                        + "请与验证器 App 上显示的验证码核对，一致即点击「确定」完成绑定。",
                "确认绑定", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r == JOptionPane.OK_OPTION) {
            totpUri = c.toUri();
            totpKeepOriginal = false;
            totpLabel.setText(c.label() + "（已绑定）");
        }
    }

    /**
     * 初始化分类专属字段卡片：每个有专属字段的分类一张面板，另有一张空卡兜底。
     * 键名与 Entry.ExtraKeys 对齐（双端一致），标签为界面展示文案。
     */
    private void initExtraCard() {
        java.util.Map<String, String[][]> defs = new java.util.LinkedHashMap<>();
        defs.put(Entry.CATEGORY_WIFI, new String[][]{
                {Entry.ExtraKeys.WIFI_SSID, "网络名"},
                {Entry.ExtraKeys.WIFI_ENCRYPTION, "加密方式"}});
        defs.put(Entry.CATEGORY_BANK_CARD, new String[][]{
                {Entry.ExtraKeys.BANK_CARD_NO, "卡号"},
                {Entry.ExtraKeys.BANK_HOLDER, "持卡人"},
                {Entry.ExtraKeys.BANK_EXPIRY, "有效期"},
                {Entry.ExtraKeys.BANK_CVV, "CVV"},
                {Entry.ExtraKeys.BANK_NAME, "银行"}});
        defs.put(Entry.CATEGORY_ID_CARD, new String[][]{
                {Entry.ExtraKeys.ID_TYPE, "证件类型"},
                {Entry.ExtraKeys.ID_NUMBER, "证件号"},
                {Entry.ExtraKeys.ID_NAME, "姓名"},
                {Entry.ExtraKeys.ID_AUTHORITY, "签发机关"},
                {Entry.ExtraKeys.ID_EXPIRY, "有效期"}});
        defs.put(Entry.CATEGORY_API_KEY, new String[][]{
                {Entry.ExtraKeys.API_KEY, "Key"},
                {Entry.ExtraKeys.API_ENDPOINT, "接口地址"},
                {Entry.ExtraKeys.API_MODEL, "模型/备注"}});

        for (java.util.Map.Entry<String, String[][]> de : defs.entrySet()) {
            String cat = de.getKey();
            String[][] keyLabels = de.getValue();
            JPanel p = new JPanel(new GridBagLayout());
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(3, 5, 3, 5);
            g.anchor = GridBagConstraints.WEST;
            java.util.Map<String, JTextField> fieldMap = new java.util.HashMap<>();
            int r = 0;
            for (String[] kl : keyLabels) {
                g.gridx = 0;
                g.gridy = r;
                p.add(new JLabel(kl[1] + "："), g);
                JTextField tf = new JTextField(20);
                g.gridx = 1;
                p.add(tf, g);
                fieldMap.put(kl[0], tf);
                r++;
            }
            extraFields.put(cat, fieldMap);
            extraCard.add(p, cat);
        }
        extraCard.add(new JPanel(), "");
    }

    /** 切换到指定分类对应的专属字段卡片；无专属字段的分类显示空卡 */
    private void showExtraFor(String category) {
        if (category != null && extraFields.containsKey(category)) {
            extraCardLayout.show(extraCard, category);
        } else {
            extraCardLayout.show(extraCard, "");
        }
    }

    /** 按键名查找专属字段输入框（跨全部分类面板） */
    private JTextField findExtraField(String key) {
        for (java.util.Map<String, JTextField> m : extraFields.values()) {
            JTextField tf = m.get(key);
            if (tf != null) {
                return tf;
            }
        }
        return null;
    }

    /** 收集全部分类面板中非空的专属字段（跨分类收集，避免切换分类导致已填内容丢失） */
    private java.util.Map<String, String> collectExtra() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, JTextField> m : extraFields.values()) {
            for (java.util.Map.Entry<String, JTextField> e : m.entrySet()) {
                String v = e.getValue().getText().trim();
                if (!v.isEmpty()) {
                    map.put(e.getKey(), v);
                }
            }
        }
        return map;
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
        String password = passwordField.getText().trim();
        boolean hasPassword = password.length() > 0 && !"（已加密，无法显示）".equals(password);
        boolean hasImage = imagePath != null && !imagePath.isEmpty();
        // 密码未修改：保留原密码
        boolean keepOldPassword = isEdit && originalPlain != null && originalPlain.equals(password);

        // 密码可留空：仅当有截图、已有密码或未修改时允许
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
        // TOTP：有绑定则用会话密钥 AES 加密后落库；清除则置空；原密文解不开时原样保留
        boolean hasTotp = totpUri != null && !totpUri.isEmpty();
        if (key != null) {
            if (totpKeepOriginal) {
                entry.setTotpSecretEnc(entry.getTotpSecretEnc());
            } else {
                entry.setTotpSecretEnc(hasTotp ? AesUtil.encrypt(totpUri, key) : null);
            }
        } else if (hasTotp) {
            JOptionPane.showMessageDialog(this, "当前会话缺少密钥，无法加密保存动态验证码", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // 分类专属字段：非空时组装 Map 整体 AES 加密落库，为空则置空
        java.util.Map<String, String> extraMap = collectExtra();
        if (key != null) {
            entry.setExtraEnc(extraMap.isEmpty() ? null : AesUtil.encrypt(Entry.encodeExtra(extraMap), key));
        } else if (!extraMap.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前会话缺少密钥，无法加密保存分类专属字段", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        saved = true;
        keepOld = keepOldPassword;
        passwordPlain = keepOldPassword ? null : (hasPassword ? password : null);
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

    /** 是否保留原密码（编辑时密码框未修改） */
    public boolean isKeepOldPassword() {
        return keepOld;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
