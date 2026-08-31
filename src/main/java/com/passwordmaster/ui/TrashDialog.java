package com.passwordmaster.ui;

import com.passwordmaster.model.Entry;
import com.passwordmaster.service.PasswordService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * 回收站对话框
 * - 展示已删除条目（软删除，deleted_at 非空）
 * - 支持恢复 / 彻底删除单条 / 清空回收站
 * <p>
 * 视觉：渐变背景 + 居中圆角卡片 + 渐变按钮，与主界面一致
 */
public class TrashDialog extends JDialog {

    private final PasswordService service;
    private DefaultTableModel tableModel;
    private JTable table;
    private List<Entry> trashList;

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public TrashDialog(Window owner, PasswordService service) {
        super(owner, "回收站", ModalityType.APPLICATION_MODAL);
        this.service = service;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImage(UiTheme.getAppIcon());

        GradientPanel bg = new GradientPanel(UiTheme.BG_TOP, UiTheme.BG_BOTTOM);
        bg.setLayout(new BorderLayout());
        setContentPane(bg);

        // 顶部标题区
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        titlePanel.setOpaque(false);
        titlePanel.setBorder(BorderFactory.createEmptyBorder(16, 20, 6, 20));
        ArtTextLabel title = new ArtTextLabel("回收站", 22);
        title.setPreferredSize(new Dimension(160, 34));
        JLabel sub = new JLabel("删除的条目暂存在这里，可恢复或彻底删除");
        sub.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        sub.setForeground(UiTheme.TEXT_SUB);
        titlePanel.add(title);
        titlePanel.add(sub);
        bg.add(titlePanel, BorderLayout.NORTH);

        // 表格卡片
        RoundedPanel card = new RoundedPanel(14);
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        tableModel = new DefaultTableModel(new String[]{"类型", "平台", "账号", "手机", "邮箱", "删除时间"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setRowHeight(28);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(8, 4));
        table.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        table.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(UiTheme.TABLE_SELECT);
        table.setSelectionForeground(Color.WHITE);
        table.setFillsViewportHeight(true);
        GradientTableHeader.apply(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(160);
        table.getColumnModel().getColumn(5).setPreferredWidth(140);

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        sp.getViewport().setBackground(Color.WHITE);
        card.add(sp, BorderLayout.CENTER);

        // 底部按钮区
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        btnPanel.setOpaque(false);
        GradientButton restoreBtn = GradientButton.primary("恢复选中");
        restoreBtn.addActionListener(e -> onRestore());
        GradientButton purgeBtn = GradientButton.secondary("彻底删除选中");
        purgeBtn.addActionListener(e -> onPurge());
        GradientButton clearBtn = GradientButton.secondary("清空回收站");
        clearBtn.addActionListener(e -> onClearAll());
        GradientButton closeBtn = GradientButton.secondary("关闭");
        closeBtn.addActionListener(e -> dispose());
        btnPanel.add(restoreBtn);
        btnPanel.add(purgeBtn);
        btnPanel.add(clearBtn);
        btnPanel.add(closeBtn);
        card.add(btnPanel, BorderLayout.SOUTH);

        bg.add(card, BorderLayout.CENTER);

        refresh();

        setSize(760, 460);
        setLocationRelativeTo(owner);
        setMinimumSize(new Dimension(640, 380));
    }

    /** 刷新回收站列表 */
    private void refresh() {
        trashList = service.listTrashed();
        tableModel.setRowCount(0);
        for (Entry e : trashList) {
            tableModel.addRow(new Object[]{
                    nullToEmpty(e.getCategory()),
                    nullToEmpty(e.getPlatform()),
                    nullToEmpty(e.getAccount()),
                    nullToEmpty(e.getPhone()),
                    nullToEmpty(e.getEmail()),
                    e.getDeletedAt() == null ? "" : TIME_FMT.format(e.getDeletedAt())
            });
        }
    }

    private Entry selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= trashList.size()) {
            return null;
        }
        return trashList.get(row);
    }

    private void onRestore() {
        Entry e = selected();
        if (e == null) {
            JOptionPane.showMessageDialog(this, "请先选中一条记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        service.restore(e.getId());
        refresh();
        JOptionPane.showMessageDialog(this, "已恢复：【" + nullToEmpty(e.getPlatform()) + " / " + nullToEmpty(e.getAccount()) + "】", "恢复成功", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onPurge() {
        Entry e = selected();
        if (e == null) {
            JOptionPane.showMessageDialog(this, "请先选中一条记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "彻底删除【" + nullToEmpty(e.getPlatform()) + " / " + nullToEmpty(e.getAccount()) + "】？\n该操作不可恢复！",
                "确认彻底删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            service.purge(e.getId());
            refresh();
        }
    }

    private void onClearAll() {
        if (trashList.isEmpty()) {
            JOptionPane.showMessageDialog(this, "回收站是空的", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "确定清空回收站吗？共 " + trashList.size() + " 条记录将被彻底删除，该操作不可恢复！",
                "确认清空回收站", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            service.purgeAllTrashed();
            refresh();
            JOptionPane.showMessageDialog(this, "回收站已清空", "完成", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
