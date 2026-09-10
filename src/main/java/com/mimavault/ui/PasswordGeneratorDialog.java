package com.mimavault.ui;

import com.mimavault.util.ClipboardSafe;
import com.mimavault.util.PasswordGenerator;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;

/**
 * 密码生成器对话框（全部本地生成，无任何联网依赖）
 * <p>
 * - 随机密码：长度、字符类别（大写/小写/数字/符号）、排除易混淆字符（0 O 1 l I）、自定义字符集
 * - 口令短语：单词数、分隔符、单词首字母大写、末尾两位数字、自定义词表（默认使用内置本地词表）
 * <p>
 * 参数改动即时重新生成；确认后由 {@link #getResult()} 返回结果，null 表示用户取消。
 */
public class PasswordGeneratorDialog extends JDialog {

    private final JTabbedPane tabs = new JTabbedPane();

    // ---------- 随机密码参数 ----------
    private final JSpinner lengthSpinner = new JSpinner(new SpinnerNumberModel(16, 8, 64, 1));
    private final JCheckBox upperBox = new JCheckBox("大写字母 A-Z", true);
    private final JCheckBox lowerBox = new JCheckBox("小写字母 a-z", true);
    private final JCheckBox digitBox = new JCheckBox("数字 0-9", true);
    private final JCheckBox symbolBox = new JCheckBox("符号 !@#$%^&*-_=+?", true);
    private final JCheckBox excludeAmbiguousBox = new JCheckBox("排除易混淆字符（0 O 1 l I）", true);
    private final JTextField charsetField = new JTextField(16);

    // ---------- 口令短语参数 ----------
    private final JSpinner wordsSpinner = new JSpinner(new SpinnerNumberModel(4, 2, 12, 1));
    private final JComboBox<String> separatorBox = new JComboBox<>(new String[]{"-", "_", ".", "空格", "无"});
    private final JCheckBox capitalizeBox = new JCheckBox("单词首字母大写");
    private final JCheckBox trailingNumberBox = new JCheckBox("末尾追加两位数字");
    private final JTextField customWordsField = new JTextField(16);

    private final JTextField resultField = new JTextField(26);
    private final JLabel hint = new JLabel(" ");

    private String result;

    public PasswordGeneratorDialog(Window owner, String title) {
        super(owner, title == null ? "密码生成器" : title, ModalityType.APPLICATION_MODAL);
        setIconImage(UiTheme.getAppIcon());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        RoundedPanel main = new RoundedPanel(16);
        main.setLayout(new BorderLayout(10, 10));
        main.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        tabs.addTab("随机密码", buildRandomPanel());
        tabs.addTab("口令短语", buildPassphrasePanel());
        tabs.addChangeListener(e -> regenerate());
        main.add(tabs, BorderLayout.CENTER);

        // 结果预览 + 操作按钮
        JPanel south = new JPanel(new BorderLayout(8, 8));
        south.setOpaque(false);

        JPanel resultRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        resultRow.setOpaque(false);
        resultRow.add(new JLabel("生成结果："));
        resultField.setFont(new Font("Consolas", Font.PLAIN, 14));
        resultRow.add(resultField);
        south.add(resultRow, BorderLayout.NORTH);

        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        hint.setForeground(new Color(100, 100, 100));
        south.add(hint, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnPanel.setOpaque(false);
        GradientButton againBtn = GradientButton.secondary("重新生成");
        againBtn.addActionListener(e -> regenerate());
        GradientButton copyBtn = GradientButton.secondary("复制");
        copyBtn.addActionListener(e -> copyResult());
        GradientButton okBtn = GradientButton.primary("使用");
        okBtn.addActionListener(e -> onConfirm());
        GradientButton cancelBtn = GradientButton.primary("取消");
        cancelBtn.addActionListener(e -> {
            result = null;
            dispose();
        });
        btnPanel.add(againBtn);
        btnPanel.add(copyBtn);
        btnPanel.add(okBtn);
        btnPanel.add(cancelBtn);
        south.add(btnPanel, BorderLayout.SOUTH);

        main.add(south, BorderLayout.SOUTH);
        add(main, BorderLayout.CENTER);

        // 参数变动即重新生成，便于直观对比
        ChangeListener any = e -> regenerate();
        lengthSpinner.addChangeListener(any);
        wordsSpinner.addChangeListener(any);
        separatorBox.addActionListener(e -> regenerate());
        upperBox.addActionListener(e -> regenerate());
        lowerBox.addActionListener(e -> regenerate());
        digitBox.addActionListener(e -> regenerate());
        symbolBox.addActionListener(e -> regenerate());
        excludeAmbiguousBox.addActionListener(e -> regenerate());
        capitalizeBox.addActionListener(e -> regenerate());
        trailingNumberBox.addActionListener(e -> regenerate());
        charsetField.getDocument().addDocumentListener(new SimpleDocListener(this::regenerate));
        customWordsField.getDocument().addDocumentListener(new SimpleDocListener(this::regenerate));

        regenerate();

        pack();
        setMinimumSize(new Dimension(460, 340));
        setLocationRelativeTo(owner);
    }

    private JPanel buildRandomPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        p.add(new JLabel("长度："), gbc);
        gbc.gridx = 1;
        lengthSpinner.setPreferredSize(new Dimension(70, 28));
        p.add(lengthSpinner, gbc);
        gbc.gridx = 2;
        JLabel lenTip = new JLabel("（8 - 64 位）");
        lenTip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        lenTip.setForeground(new Color(120, 120, 120));
        p.add(lenTip, gbc);

        JPanel catPanel = new JPanel(new GridLayout(2, 2, 8, 4));
        catPanel.setOpaque(false);
        catPanel.add(upperBox);
        catPanel.add(lowerBox);
        catPanel.add(digitBox);
        catPanel.add(symbolBox);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 3;
        p.add(catPanel, gbc);

        gbc.gridy = 2;
        p.add(excludeAmbiguousBox, gbc);

        gbc.gridy = 3;
        gbc.gridwidth = 1;
        p.add(new JLabel("自定义字符集："), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        charsetField.putClientProperty("JTextField.placeholderText", "如 #$%&@，留空则不使用");
        p.add(charsetField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel tip = new JLabel("<html><font color='#666666'>提示：勾选的每个类别都保证至少出现 1 次；自定义字符集内的字符也会参与生成。</font></html>");
        tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        p.add(tip, gbc);

        return p;
    }

    private JPanel buildPassphrasePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        p.add(new JLabel("单词个数："), gbc);
        gbc.gridx = 1;
        wordsSpinner.setPreferredSize(new Dimension(70, 28));
        p.add(wordsSpinner, gbc);
        gbc.gridx = 2;
        p.add(new JLabel("（2 - 12 个）"), gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        p.add(new JLabel("分隔符："), gbc);
        gbc.gridx = 1;
        separatorBox.setPreferredSize(new Dimension(90, 28));
        p.add(separatorBox, gbc);
        gbc.gridx = 2;
        JLabel sepTip = new JLabel("（“空格”表示用空格连接）");
        sepTip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        sepTip.setForeground(new Color(120, 120, 120));
        p.add(sepTip, gbc);

        JPanel optPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        optPanel.setOpaque(false);
        optPanel.add(capitalizeBox);
        optPanel.add(trailingNumberBox);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        p.add(optPanel, gbc);

        gbc.gridy = 3;
        gbc.gridwidth = 1;
        p.add(new JLabel("自定义词表："), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        customWordsField.putClientProperty("JTextField.placeholderText", "如 apple,banana,cherry，留空用内置词表");
        p.add(customWordsField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel tip = new JLabel("<html><font color='#666666'>提示：词表完全内置本地（约 600 个英文单词），不联网、不依赖第三方服务。</font></html>");
        tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        p.add(tip, gbc);

        return p;
    }

    /** 按当前选项卡与参数重新生成一次 */
    private void regenerate() {
        try {
            if (tabs.getSelectedIndex() == 1) {
                PasswordGenerator.PassphraseOptions o = new PasswordGenerator.PassphraseOptions();
                o.words = (Integer) wordsSpinner.getValue();
                o.separator = currentSeparator();
                o.capitalize = capitalizeBox.isSelected();
                o.appendNumber = trailingNumberBox.isSelected();
                o.customWords = customWordsField.getText();
                resultField.setText(PasswordGenerator.generatePassphrase(o));
                hint.setText(hintTextPassphrase());
            } else {
                PasswordGenerator.Options o = new PasswordGenerator.Options();
                o.length = (Integer) lengthSpinner.getValue();
                o.upper = upperBox.isSelected();
                o.lower = lowerBox.isSelected();
                o.digits = digitBox.isSelected();
                o.symbols = symbolBox.isSelected();
                o.excludeAmbiguous = excludeAmbiguousBox.isSelected();
                o.customCharset = charsetField.getText();
                resultField.setText(PasswordGenerator.generate(o));
                hint.setText(hintTextRandom());
            }
        } catch (Exception ex) {
            hint.setText("生成失败：" + ex.getMessage());
        }
    }

    private String currentSeparator() {
        Object sel = separatorBox.getSelectedItem();
        String s = sel == null ? "-" : sel.toString();
        if ("空格".equals(s)) {
            return " ";
        }
        return "无".equals(s) ? "" : s;
    }

    private String hintTextRandom() {
        if (!upperBox.isSelected() && !lowerBox.isSelected() && !digitBox.isSelected()
                && !symbolBox.isSelected() && charsetField.getText().trim().isEmpty()) {
            return "未选择任何字符类别与自定义字符集，已按“小写字母 + 数字”兜底生成。";
        }
        return "已生成本地随机密码，点“重新生成”可换一个。";
    }

    private String hintTextPassphrase() {
        if (customWordsField.getText() != null && !customWordsField.getText().trim().isEmpty()) {
            return "使用自定义词表生成（逗号/空格/换行分隔）。";
        }
        return "使用内置本地词表生成，点“重新生成”可换一条。";
    }

    private void copyResult() {
        String pwd = resultField.getText();
        if (pwd == null || pwd.isEmpty()) {
            return;
        }
        ClipboardSafe.copySecret(pwd);
        Toast.show(this, "已复制到剪贴板（30 秒后自动清除）");
    }

    private void onConfirm() {
        String pwd = resultField.getText() == null ? "" : resultField.getText().trim();
        if (pwd.isEmpty()) {
            JOptionPane.showMessageDialog(this, "生成结果为空，请调整参数后重试", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        result = pwd;
        dispose();
    }

    /** 确认后的生成结果；用户取消时为 null */
    public String getResult() {
        return result;
    }

    /**
     * 模态弹出密码生成器，返回用户确认的密码（取消返回 null）
     */
    public static String showDialog(Window owner, String title) {
        PasswordGeneratorDialog dlg = new PasswordGeneratorDialog(owner, title);
        dlg.setVisible(true);
        return dlg.getResult();
    }

    /** 轻量文档监听：文本变化即回调（避免各控件重复实现） */
    private static class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable onChange;

        SimpleDocListener(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }
    }
}
