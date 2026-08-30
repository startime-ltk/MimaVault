package com.passwordmaster.ui;

import com.passwordmaster.model.Entry;
import com.passwordmaster.service.PasswordService;
import com.passwordmaster.util.BackupUtil;
import com.passwordmaster.util.ImageUtil;

import javax.crypto.SecretKey;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 主界面
 * - JTable 展示条目（类型/平台/账号/手机/邮箱/备注）
 * - 顶部搜索框实时模糊搜索（平台/账号/手机/邮箱）+ 分类筛选
 * - 工具栏：新增 / 编辑 / 删除 / 查看详情 / 导出 / 导入
 */
public class MainFrame extends JFrame {

    private final PasswordService service;
    private final SecretKey key;

    private DefaultTableModel tableModel;
    private JTable table;
    private final JTextField searchField = new JTextField(18);
    private final JComboBox<String> categoryFilter = new JComboBox<>(new String[]{"全部", "网站", "应用", "其他"});

    private List<Entry> currentList;

    public MainFrame(PasswordService service, SecretKey key) {
        super("密码大师 PasswordMaster");
        this.service = service;
        this.key = key;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        buildToolbar();
        buildTable();

        refreshTable(null, null);

        setSize(860, 520);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(720, 420));
    }

    private void buildToolbar() {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row1.add(new JLabel("搜索："));
        searchField.putClientProperty("JTextField.placeholderText", "平台 / 账号 / 手机 / 邮箱");
        searchField.setPreferredSize(new Dimension(240, 26));
        row1.add(searchField);
        row1.add(new JLabel("类型："));
        categoryFilter.setPreferredSize(new Dimension(80, 26));
        row1.add(categoryFilter);
        top.add(row1);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row2.add(createToolButton("新增", e -> onAdd()));
        row2.add(createToolButton("编辑", e -> onEdit()));
        row2.add(createToolButton("删除", e -> onDelete()));
        row2.add(createToolButton("详情", e -> onDetail()));
        row2.add(createToolButton("导出", e -> onExport()));
        row2.add(createToolButton("导入", e -> onImport()));
        row2.add(createToolButton("智能导入", e -> onSmartImport()));
        top.add(row2);

        add(top, BorderLayout.NORTH);
    }

    private JButton createToolButton(String text, java.awt.event.ActionListener listener) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        btn.addActionListener(listener);
        return btn;
    }

    private void buildTable() {
        tableModel = new DefaultTableModel(new String[]{"类型", "平台", "账号", "手机", "邮箱", "备注"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setRowHeight(26);
        table.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        table.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(160);
        table.getColumnModel().getColumn(5).setPreferredWidth(200);

        // 双击查看详情
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    onDetail();
                }
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);

        // 实时搜索
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refreshTable(null, null);
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refreshTable(null, null);
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refreshTable(null, null);
            }
        });
        categoryFilter.addActionListener(e -> refreshTable(null, null));
    }

    /** 刷新表格数据 */
    private void refreshTable(String kw, String cat) {
        String keyword = kw == null ? searchField.getText() : kw;
        String category = cat == null ? (String) categoryFilter.getSelectedItem() : cat;
        currentList = service.search(keyword, category);

        tableModel.setRowCount(0);
        for (Entry e : currentList) {
            tableModel.addRow(new Object[]{
                    nullToEmpty(e.getCategory()),
                    nullToEmpty(e.getPlatform()),
                    nullToEmpty(e.getAccount()),
                    nullToEmpty(e.getPhone()),
                    nullToEmpty(e.getEmail()),
                    nullToEmpty(e.getNote())
            });
        }
    }

    private Entry selectedEntry() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= currentList.size()) {
            return null;
        }
        return currentList.get(row);
    }

    private void onAdd() {
        EntryEditDialog dlg = new EntryEditDialog(this, null);
        dlg.setVisible(true);
        if (dlg.isSaved()) {
            service.addEntry(dlg.getEntry(), dlg.getPlainPassword(), key);
            refreshTable(null, null);
        }
    }

    private void onEdit() {
        Entry selected = selectedEntry();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "请先选中一条记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        EntryEditDialog dlg = new EntryEditDialog(this, selected);
        dlg.setVisible(true);
        if (dlg.isSaved()) {
            service.updateEntry(dlg.getEntry(), dlg.getPlainPassword(), key, dlg.isKeepOldPassword());
            refreshTable(null, null);
        }
    }

    private void onDelete() {
        Entry selected = selectedEntry();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "请先选中一条记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "确定删除【" + nullToEmpty(selected.getPlatform()) + " / " + nullToEmpty(selected.getAccount()) + "】？",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            service.delete(selected.getId());
            refreshTable(null, null);
        }
    }

    private void onDetail() {
        Entry selected = selectedEntry();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "请先选中一条记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new EntryDetailDialog(this, selected, service, key).setVisible(true);
    }

    private void onExport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出备份");
        chooser.setSelectedFile(new java.io.File("PasswordMaster_backup_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".pmaster"));
        chooser.setFileFilter(new FileNameExtensionFilter("密码大师备份 (*.pmaster)", "pmaster"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (!target.toString().toLowerCase().endsWith(".pmaster")) {
            target = Paths.get(target.toString() + ".pmaster");
        }
        try {
            List<Entry> all = service.listEntries();
            if (all.isEmpty()) {
                JOptionPane.showMessageDialog(this, "当前没有可导出的数据", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            BackupUtil.export(all, key, target);
            JOptionPane.showMessageDialog(this, "导出成功，共 " + all.size() + " 条记录\n" + target, "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导出失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onImport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入备份");
        chooser.setFileFilter(new FileNameExtensionFilter("密码大师备份 (*.pmaster)", "pmaster"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = chooser.getSelectedFile().toPath();

        // 输入主密码解密
        JPasswordField pwdField = new JPasswordField(16);
        int r = JOptionPane.showConfirmDialog(this, pwdField, "请输入主密码解密备份文件", JOptionPane.OK_CANCEL_OPTION);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        SecretKey importKey;
        try {
            importKey = com.passwordmaster.util.AesUtil.deriveKey(new String(pwdField.getPassword()));
            BackupUtil.BackupPackage pack = BackupUtil.importBackup(source, importKey);
            if (pack.items == null || pack.items.isEmpty()) {
                JOptionPane.showMessageDialog(this, "备份文件中没有数据", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            doImport(pack);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导入失败：主密码错误或文件损坏\n" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 智能导入：来源选择 -> 解析 -> 预览确认 -> 入库 */
    private void onSmartImport() {
        java.util.List<com.passwordmaster.util.TextBatchParser.RawRecord> records =
                com.passwordmaster.ui.SmartImportDialog.collect(this);
        if (records == null || records.isEmpty()) {
            return;
        }
        new ImportPreviewDialog(this, records, service, key).setVisible(true);
        refreshTable(null, null);
    }

    private void doImport(BackupUtil.BackupPackage pack) {
        boolean hasExisting = !service.listEntries().isEmpty();
        String mode = "合并";
        if (hasExisting) {
            Object[] options = {"覆盖", "合并"};
            int choice = JOptionPane.showOptionDialog(this,
                    "当前已有数据，请选择导入方式：\n覆盖：清空现有数据后导入\n合并：按 平台+账号 判断重复，重复跳过",
                    "导入方式", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
            if (choice == JOptionPane.CLOSED_OPTION) {
                return;
            }
            mode = (choice == 0) ? "覆盖" : "合并";
        }

        int skipped = 0;
        int restored = 0;

        if ("覆盖".equals(mode)) {
            service.clearAll();
        }

        // 合并模式：收集现有 平台+账号 键
        Set<String> existingKeys = new HashSet<>();
        if ("合并".equals(mode)) {
            for (Entry e : service.listEntries()) {
                existingKeys.add(keyOf(e));
            }
        }

        for (BackupUtil.BackupItem item : pack.items) {
            Entry e = item.toEntry();
            // 恢复图片
            if (item.imageBase64 != null && !item.imageBase64.isEmpty()) {
                Path imgPath = ImageUtil.base64ToImage(item.imageBase64, item.imageName);
                if (imgPath != null) {
                    e.setImagePath("images/" + imgPath.getFileName().toString());
                }
            }
            if ("合并".equals(mode)) {
                String k = keyOf(e);
                if (existingKeys.contains(k)) {
                    skipped++;
                    continue;
                }
                existingKeys.add(k);
            }
            service.insertAll(java.util.Collections.singletonList(e));
            restored++;
        }

        refreshTable(null, null);
        JOptionPane.showMessageDialog(this,
                "导入完成：成功 " + restored + " 条" + (skipped > 0 ? "，重复跳过 " + skipped + " 条" : ""),
                "导入完成", JOptionPane.INFORMATION_MESSAGE);
    }

    private String keyOf(Entry e) {
        return nullToEmpty(e.getPlatform()) + "|" + nullToEmpty(e.getAccount());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
