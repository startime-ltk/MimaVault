package com.passwordmaster.ui;

import javax.swing.*;
import java.awt.*;

/**
 * 手势录入对话框
 * 支持两种方式：
 * 1. 手动输入数字序列（文本框输入 1-9，如 14789）
 * 2. GesturePanel 鼠标点击九宫格连线
 */
public class GestureDialog extends JDialog {

    private final GesturePanel panel;
    private final JTextField seqField;
    private boolean confirmed = false;

    public GestureDialog(Window owner) {
        super(owner, "录入手势密码", ModalityType.APPLICATION_MODAL);
        setLayout(new BorderLayout(10, 10));

        JPanel center = new JPanel(new BorderLayout(10, 10));
        center.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

        panel = new GesturePanel(true);
        center.add(panel, BorderLayout.CENTER);

        // 手动输入区
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        JLabel tip = new JLabel("或手动输入序列（1-9 数字，可逗号分隔）：");
        tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        seqField = new JTextField(12);
        seqField.setFont(new Font("Consolas", Font.PLAIN, 14));
        JButton applyBtn = new JButton("应用序列");
        applyBtn.addActionListener(e -> applyManualSequence());
        inputPanel.add(tip, BorderLayout.WEST);
        inputPanel.add(seqField, BorderLayout.CENTER);
        inputPanel.add(applyBtn, BorderLayout.EAST);
        center.add(inputPanel, BorderLayout.SOUTH);

        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearBtn = new JButton("清空");
        clearBtn.addActionListener(e -> panel.clearSequence());
        JButton okBtn = new JButton("确定");
        okBtn.addActionListener(e -> onOk());
        JButton cancelBtn = new JButton("取消");
        cancelBtn.addActionListener(e -> dispose());
        bottom.add(clearBtn);
        bottom.add(okBtn);
        bottom.add(cancelBtn);
        add(bottom, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
    }

    private void applyManualSequence() {
        String text = seqField.getText().trim();
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= '1' && c <= '9') {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(c);
            }
        }
        panel.setSequence(sb.toString());
    }

    private void onOk() {
        confirmed = true;
        dispose();
    }

    /** 打开对话框并返回手势序列；取消则返回 null */
    public static String showDialog(Window owner, String initialSeq) {
        GestureDialog dlg = new GestureDialog(owner);
        if (initialSeq != null && !initialSeq.isEmpty()) {
            dlg.panel.setSequence(initialSeq);
        }
        dlg.setVisible(true);
        if (dlg.confirmed) {
            return dlg.panel.getSequenceText();
        }
        return null;
    }
}
