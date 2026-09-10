package com.mimavault.ui;

import com.mimavault.model.Entry;
import com.mimavault.model.PasswordHistoryItem;
import com.mimavault.service.PasswordService;
import com.mimavault.util.ClipboardSafe;

import javax.crypto.SecretKey;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * 密码历史版本对话框
 * <p>
 * 展示某条目改密前的历史密码（默认掩码，不显示明文），支持查看明文、复制、恢复到指定版本。
 * 历史记录与安卓端一致：密文存储、仅保留最近 {@link PasswordService#MAX_PASSWORD_HISTORY} 条。
 */
public class PasswordHistoryDialog extends JDialog {

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final String MASK = "••••••••";

    private final PasswordService service;
    private final SecretKey key;
    private final Entry entry;
    private final JLabel emptyLabel = new JLabel("暂无历史版本（修改该条目密码后会自动留档）");
    private final DefaultTableModel model;
    private final JTable table;
    private final JLabel hint = new JLabel(" ");
    private List<PasswordHistoryItem> items;
    private boolean restored;

    public PasswordHistoryDialog(Window owner, Entry entry, PasswordService service, SecretKey key) {
        super(owner, "密码历史版本 - " + nullToEmpty(entry.getPlatform()), ModalityType.APPLICATION_MODAL);
        this.entry = entry;
        this.service = service;
        this.key = key;
        setIconImage(UiTheme.getAppIcon());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        RoundedPanel main = new RoundedPanel(16);
        main.setLayout(new BorderLayout(10, 10));
        main.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel title = new JLabel("历史密码（仅为密文留档，最多保留最近 " + PasswordService.MAX_PASSWORD_HISTORY + " 条）");
        title.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        title.setForeground(new Color(90, 90, 90));
        main.add(title, BorderLayout.NORTH);

        model = new DefaultTableModel(new Object[]{"序号", "修改时间", "密码"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(model);
        table.setRowHeight(26);
        table.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        table.getColumnModel().getColumn(0).setCellRenderer(center);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setCellRenderer(center);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(460, 240));
        JPanel centerBox = new JPanel(new BorderLayout(6, 6));
        centerBox.setOpaque(false);
        centerBox.add(scroll, BorderLayout.CENTER);
        emptyLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        emptyLabel.setForeground(new Color(130, 130, 130));
        centerBox.add(emptyLabel, BorderLayout.SOUTH);
        main.add(centerBox, BorderLayout.CENTER);

        JPanel southBox = new JPanel(new BorderLayout(8, 6));
        southBox.setOpaque(false);
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        hint.setForeground(new Color(46, 139, 87));
        southBox.add(hint, BorderLayout.NORTH);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnPanel.setOpaque(false);
        GradientButton viewBtn = GradientButton.secondary("查看明文");
        viewBtn.addActionListener(e -> onView());
        GradientButton copyBtn = GradientButton.secondary("复制明文");
        copyBtn.addActionListener(e -> onCopy());
        GradientButton restoreBtn = GradientButton.accent("恢复此版本");
        restoreBtn.addActionListener(e -> onRestore());
        GradientButton closeBtn = GradientButton.primary("关闭");
        closeBtn.addActionListener(e -> dispose());
        btnPanel.add(viewBtn);
        btnPanel.add(copyBtn);
        btnPanel.add(restoreBtn);
        btnPanel.add(closeBtn);
        southBox.add(btnPanel, BorderLayout.SOUTH);
        main.add(southBox, BorderLayout.SOUTH);

        add(main, BorderLayout.CENTER);
        reload();

        pack();
        setMinimumSize(new Dimension(520, 380));
        setLocationRelativeTo(owner);
    }

    /** 重新从库中加载历史列表并刷新表格 */
    private void reload() {
        items = service.listPasswordHistory(entry.getId());
        model.setRowCount(0);
        for (int i = 0; i < items.size(); i++) {
            PasswordHistoryItem it = items.get(i);
            String time = it.getChangedAt() == null ? "-" : TIME_FMT.format(it.getChangedAt());
            model.addRow(new Object[]{i + 1, time, MASK});
        }
        emptyLabel.setVisible(items.isEmpty());
        if (items.isEmpty()) {
            hint.setText(" ");
        }
    }

    private PasswordHistoryItem selectedItem() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= items.size()) {
            JOptionPane.showMessageDialog(this, "请先在列表中选择一条历史密码", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return items.get(row);
    }

    /** 查看选中历史版本的明文（仅内存展示，不落盘） */
    private void onView() {
        PasswordHistoryItem it = selectedItem();
        if (it == null) {
            return;
        }
        String plain = service.decryptHistoryPassword(it, key);
        if (plain.isEmpty()) {
            JOptionPane.showMessageDialog(this, "该历史密码无法解密（可能已被更早版本的主密码加密）", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JTextField field = new JTextField(plain);
        field.setEditable(false);
        field.setFont(new Font("Consolas", Font.PLAIN, 14));
        Object[] msg = {"该历史版本的密码：", field};
        JOptionPane.showMessageDialog(this, msg, "历史密码", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 复制选中历史版本的明文到剪贴板（30 秒后自动清除） */
    private void onCopy() {
        PasswordHistoryItem it = selectedItem();
        if (it == null) {
            return;
        }
        String plain = service.decryptHistoryPassword(it, key);
        if (plain.isEmpty()) {
            JOptionPane.showMessageDialog(this, "该历史密码无法解密（可能已被更早版本的主密码加密）", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ClipboardSafe.copySecret(plain);
        hint.setText("已复制到剪贴板，30 秒后自动清除");
    }

    /** 恢复选中历史版本为当前密码：当前密码反向留档，可再次切回 */
    private void onRestore() {
        PasswordHistoryItem it = selectedItem();
        if (it == null) {
            return;
        }
        String plain = service.decryptHistoryPassword(it, key);
        if (plain.isEmpty()) {
            JOptionPane.showMessageDialog(this, "该历史密码无法解密，无法恢复", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String time = it.getChangedAt() == null ? "-" : TIME_FMT.format(it.getChangedAt());
        JLabel warn = new JLabel("<html>确定把<font color='#C62828'><b>" + time + "</b></font> 的历史密码恢复为该条目的当前密码吗？<br>"
                + "当前密码会自动留档为历史版本，可再次切回。</html>");
        warn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        int r = JOptionPane.showConfirmDialog(this, warn, "恢复历史版本", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        service.restorePasswordFromHistory(entry, it, key);
        restored = true;
        reload();
        hint.setText("已恢复为所选历史版本，当前密码已留档");
        Toast.show(this, "已恢复历史密码");
    }

    /** 本次操作是否发生过历史版本恢复（调用方据此刷新展示） */
    public boolean isRestored() {
        return restored;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
