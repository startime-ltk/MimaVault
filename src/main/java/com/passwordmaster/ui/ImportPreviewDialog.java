package com.passwordmaster.ui;

import com.passwordmaster.model.Entry;
import com.passwordmaster.service.PasswordService;
import com.passwordmaster.util.AesUtil;
import com.passwordmaster.util.TextBatchParser;

import javax.crypto.SecretKey;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能导入预览确认对话框
 * - 表格展示解析出的记录（平台/账号/密码/手机/邮箱/来源/备注），带勾选列，默认全选
 * - 每条可编辑修正
 * - 确认导入：按 平台+账号 去重（合并模式），写入数据库
 */
public class ImportPreviewDialog extends JDialog {

    private final PasswordService service;
    private final SecretKey key;
    private final List<TextBatchParser.RawRecord> records;
    private final PreviewTableModel model;
    private final JTable table;
    private final Set<String> existingKeys = new HashSet<>();

    private static final String[] COLUMNS = {"导入", "平台", "账号", "密码", "手机", "邮箱", "来源"};

    public ImportPreviewDialog(Window owner, List<TextBatchParser.RawRecord> records,
                               PasswordService service, SecretKey key) {
        super(owner, "智能导入预览", ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.key = key;
        this.records = records;

        for (Entry e : service.listEntries()) {
            existingKeys.add(keyOf(e));
        }

        setLayout(new BorderLayout());
        model = new PreviewTableModel(records);
        table = new JTable(model);
        table.setRowHeight(24);
        table.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        table.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(100);
        table.getColumnModel().getColumn(5).setPreferredWidth(140);
        table.getColumnModel().getColumn(6).setPreferredWidth(80);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // 顶部统计
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(new JLabel("共解析 " + records.size() + " 条记录"));
        top.add(new JLabel("  来源：" + sourceSummary()));
        top.add(new JLabel("  （勾选导入，双击单元格可修改）"));
        add(top, BorderLayout.NORTH);

        // 底部按钮
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("确认导入");
        ok.addActionListener(e -> onConfirm());
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> dispose());
        bottom.add(ok);
        bottom.add(cancel);
        add(bottom, BorderLayout.SOUTH);

        pack();
        setSize(Math.max(getWidth(), 780), Math.min(Math.max(getHeight(), 360), 520));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private String sourceSummary() {
        Set<String> types = new java.util.LinkedHashSet<>();
        for (TextBatchParser.RawRecord r : records) {
            if (r.sourceType != null && !r.sourceType.isEmpty()) {
                types.add(r.sourceType);
            }
        }
        return types.isEmpty() ? "未知" : String.join("、", types);
    }

    private void onConfirm() {
        List<Entry> toInsert = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < records.size(); i++) {
            if (!model.isChecked(i)) {
                continue;
            }
            Entry e = records.get(i).entry;
            // 从表格编辑值同步
            model.applyEdits(i, e);

            // 去重：平台+账号
            String k = keyOf(e);
            if (existingKeys.contains(k)) {
                skipped++;
                continue;
            }
            existingKeys.add(k);

            // 明文密码加密
            String plain = e.getPasswordEnc(); // 草稿阶段明文暂存于此
            if (plain != null && !plain.isEmpty()) {
                e.setPasswordEnc(AesUtil.encrypt(plain, key));
            } else {
                e.setPasswordEnc(null);
            }
            toInsert.add(e);
        }

        if (toInsert.isEmpty() && skipped == 0) {
            JOptionPane.showMessageDialog(this, "未勾选任何记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (!toInsert.isEmpty()) {
            service.insertAll(toInsert);
        }
        dispose();
        JOptionPane.showMessageDialog(this,
                "导入完成：成功 " + toInsert.size() + " 条" + (skipped > 0 ? "，重复跳过 " + skipped + " 条" : ""),
                "导入完成", JOptionPane.INFORMATION_MESSAGE);
    }

    private String keyOf(Entry e) {
        return nullToEmpty(e.getPlatform()) + "|" + nullToEmpty(e.getAccount());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 预览表格模型：首列勾选，其余字段可编辑 */
    private static class PreviewTableModel extends AbstractTableModel {

        private final List<TextBatchParser.RawRecord> records;
        private final boolean[] checked;

        PreviewTableModel(List<TextBatchParser.RawRecord> records) {
            this.records = records;
            this.checked = new boolean[records.size()];
            for (int i = 0; i < checked.length; i++) {
                checked[i] = true;
            }
        }

        boolean isChecked(int row) {
            return checked[row];
        }

        @Override
        public int getRowCount() {
            return records.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Entry e = records.get(rowIndex).entry;
            switch (columnIndex) {
                case 0:
                    return checked[rowIndex];
                case 1:
                    return nullToEmpty(e.getPlatform());
                case 2:
                    return nullToEmpty(e.getAccount());
                case 3:
                    return nullToEmpty(e.getPasswordEnc()); // 草稿阶段明文
                case 4:
                    return nullToEmpty(e.getPhone());
                case 5:
                    return nullToEmpty(e.getEmail());
                case 6:
                    return records.get(rowIndex).sourceType == null ? "" : records.get(rowIndex).sourceType;
                default:
                    return "";
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 || columnIndex == 1 || columnIndex == 2
                    || columnIndex == 3 || columnIndex == 4 || columnIndex == 5;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                checked[rowIndex] = Boolean.TRUE.equals(aValue);
                fireTableCellUpdated(rowIndex, columnIndex);
                return;
            }
            Entry e = records.get(rowIndex).entry;
            String v = aValue == null ? "" : aValue.toString();
            switch (columnIndex) {
                case 1:
                    e.setPlatform(v);
                    break;
                case 2:
                    e.setAccount(v);
                    break;
                case 3:
                    e.setPasswordEnc(v);
                    break;
                case 4:
                    e.setPhone(v);
                    break;
                case 5:
                    e.setEmail(v);
                    break;
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        /** 将表格中的编辑值同步回 Entry（供确认导入时取最新值） */
        void applyEdits(int row, Entry target) {
            Entry e = records.get(row).entry;
            target.setPlatform(e.getPlatform());
            target.setAccount(e.getAccount());
            target.setPasswordEnc(e.getPasswordEnc());
            target.setPhone(e.getPhone());
            target.setEmail(e.getEmail());
            target.setNote(e.getNote());
        }
    }
}
