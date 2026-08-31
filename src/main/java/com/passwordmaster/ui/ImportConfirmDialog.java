package com.passwordmaster.ui;

import com.passwordmaster.model.Entry;
import com.passwordmaster.util.AesUtil;

import javax.crypto.SecretKey;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * 导入确认对话框（CSV / .pmaster 通用）
 * - 表格展示待导入条目（密码明文显示；无法用当前库密钥解密时标记「已加密」）
 * - 用户点击「确认导入」后才执行入库，取消则不导入任何数据
 */
public class ImportConfirmDialog extends JDialog {

    private boolean confirmed = false;

    public ImportConfirmDialog(Window owner, String title, List<Entry> entries, SecretKey key) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setIconImage(UiTheme.getAppIcon());
        setLayout(new BorderLayout(10, 10));

        String[] cols = {"分类", "平台", "账号", "密码", "手机", "邮箱", "备注"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (Entry e : entries) {
            String pwd = "";
            if (e.getPasswordEnc() != null && !e.getPasswordEnc().isEmpty()) {
                String plain = key == null ? null : AesUtil.decrypt(e.getPasswordEnc(), key);
                pwd = plain == null ? "（已加密）" : plain;
            }
            model.addRow(new Object[]{
                    e.getCategory() == null ? "" : e.getCategory(),
                    nullToEmpty(e.getPlatform()),
                    nullToEmpty(e.getAccount()),
                    pwd,
                    nullToEmpty(e.getPhone()),
                    nullToEmpty(e.getEmail()),
                    nullToEmpty(e.getNote())
            });
        }

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.setOpaque(false);
        JLabel info = new JLabel("以下 " + entries.size() + " 条数据将导入到密码库：");
        info.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        top.add(info);
        add(top, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        table.setRowHeight(24);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.getColumnModel().getColumn(4).setPreferredWidth(110);
        table.getColumnModel().getColumn(5).setPreferredWidth(140);
        table.getColumnModel().getColumn(6).setPreferredWidth(180);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(900, Math.min(420, 48 + entries.size() * 24)));
        add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.setOpaque(false);
        GradientButton ok = GradientButton.primary("确认导入 " + entries.size() + " 条");
        ok.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        GradientButton cancel = GradientButton.secondary("取消");
        cancel.addActionListener(e -> dispose());
        bottom.add(ok);
        bottom.add(cancel);
        add(bottom, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
        getRootPane().setDefaultButton(ok);
    }

    /** 用户是否点击了「确认导入」 */
    public boolean isConfirmed() {
        return confirmed;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
