package com.mimavault.ui;

import com.mimavault.model.Entry;
import com.mimavault.service.GlobalSearchIndex;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局搜索面板（v8.35.0）
 *
 * Ctrl+K 全局唤起：
 *  - 输入普通关键词：跨条目（含四分类专属字段、备注、回收站外全部条目）模糊子序列匹配，
 *    标题优先排序；Enter/双击打开对应条目详情。
 *  - 输入 > 前缀：命令模式，执行常用命令（锁定、回收站、修改主密码、数据迁移、安全报告、新建条目）。
 */
public class GlobalSearchDialog extends JDialog {

    /** 宿主回调：打开条目详情 / 执行命令 */
    public interface Host {
        void openEntryDetail(Entry entry);

        void runCommand(String command);
    }

    /** 命令定义 */
    public static final class CommandDef {
        public final String id;
        public final String label;
        public final String hint;

        public CommandDef(String id, String label, String hint) {
            this.id = id;
            this.label = label;
            this.hint = hint;
        }
    }

    private static final CommandDef[] COMMANDS = {
            new CommandDef("lock", "锁定", "立即锁定并回到登录界面"),
            new CommandDef("trash", "回收站", "打开回收站（恢复/彻底删除）"),
            new CommandDef("settings", "修改主密码", "验证当前密码后全库重加密"),
            new CommandDef("migrate", "数据迁移", "将数据迁移到其他磁盘/文件夹"),
            new CommandDef("health", "安全报告", "密码健康检测报告"),
            new CommandDef("new", "新建条目", "新增一条记录")
    };

    private final Host host;
    private final GlobalSearchIndex index;
    private final JTextField input = new JTextField();
    private final DefaultListModel<Object> model = new DefaultListModel<>();
    private final JList<Object> resultList = new JList<>(model);
    private final JLabel hintLabel = new JLabel("输入关键词搜索条目；输入 > 使用命令；Enter 打开 · Esc 关闭", SwingConstants.LEFT);

    public GlobalSearchDialog(Window owner, GlobalSearchIndex index, Host host) {
        super(owner, "全局搜索 (Ctrl+K)", ModalityType.MODELESS);
        this.index = index;
        this.host = host;

        setLayout(new BorderLayout());
        RoundedPanel root = new RoundedPanel(16);
        root.setFill(new Color(0xF8FAFF));
        root.setBorderColor(UiTheme.CARD_BORDER);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 10, 14));
        root.setLayout(new BorderLayout(0, 8));
        setContentPane(root);

        // 输入行：放大字号 + 占位提示
        input.putClientProperty("JTextField.placeholderText", "搜索条目…（wx → 微信）  输入 > 使用命令");
        input.setFont(new Font("Microsoft YaHei", Font.PLAIN, 15));
        input.setPreferredSize(new Dimension(0, 36));
        input.setBackground(UiTheme.INPUT_BG);
        root.add(input, BorderLayout.NORTH);

        // 结果列表
        resultList.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        resultList.setCellRenderer(new RowRenderer());
        resultList.setBackground(new Color(0xF8FAFF));
        resultList.setFixedCellHeight(48);
        JScrollPane sp = new JScrollPane(resultList);
        sp.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        sp.getViewport().setBackground(new Color(0xF8FAFF));
        root.add(sp, BorderLayout.CENTER);

        // 底部提示
        hintLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        hintLabel.setForeground(UiTheme.TEXT_SUB);
        JPanel hintRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        hintRow.setOpaque(false);
        hintRow.add(hintLabel);
        root.add(hintRow, BorderLayout.SOUTH);

        setSize(540, 420);
        setLocationRelativeTo(owner);
        setResizable(false);

        // 输入监听
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onInputChanged();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onInputChanged();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                onInputChanged();
            }
        });

        // 键盘绑定：Enter 打开选中项 / 箭头键在结果列表间移动 / Esc 关闭
        resultList.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        resultList.getActionMap().put("open", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openInput");
        input.getActionMap().put("openInput", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "down");
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "up");
        input.getActionMap().put("down", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                moveSelection(1);
            }
        });
        input.getActionMap().put("up", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                moveSelection(-1);
            }
        });
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        root.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });

        // 双击打开
        resultList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelected();
                }
            }
        });
    }

    /** 打开面板：清空输入、聚焦，并重建最新索引（由宿主在显示前调用） */
    public void open() {
        input.setText("");
        onInputChanged();
        SwingUtilities.invokeLater(() -> {
            setVisible(true);
            input.requestFocusInWindow();
        });
    }

    private void onInputChanged() {
        String text = input.getText().trim();
        model.clear();
        if (text.startsWith(">")) {
            showCommands(text.substring(1).trim().toLowerCase());
        } else if (!text.isEmpty()) {
            showHits(text);
        } else {
            hintLabel.setText("输入关键词搜索条目；输入 > 使用命令；Enter 打开 · Esc 关闭");
        }
        if (model.size() > 0) {
            resultList.setSelectedIndex(0);
        }
    }

    private void showCommands(String q) {
        List<CommandDef> matches = new ArrayList<>();
        for (CommandDef cmd : COMMANDS) {
            String key = (cmd.id + " " + cmd.label).toLowerCase();
            if (q.isEmpty() || key.contains(q) || isSubsequence(q, key)) {
                matches.add(cmd);
            }
        }
        for (CommandDef cmd : matches) {
            model.addElement(new CommandRow(cmd));
        }
        hintLabel.setText("命令模式：" + matches.size() + " 条可用命令，Enter 执行");
    }

    private void showHits(String text) {
        List<GlobalSearchIndex.Hit> hits = index.search(text, 50);
        for (GlobalSearchIndex.Hit hit : hits) {
            model.addElement(hit);
        }
        if (hits.isEmpty()) {
            hintLabel.setText("未找到匹配条目，试试更短的关键词或拼音缩写（如 wx → 微信）");
        } else {
            hintLabel.setText("共命中 " + hits.size() + " 条（标题优先排序），Enter 打开详情");
        }
    }

    private void moveSelection(int delta) {
        int idx = resultList.getSelectedIndex();
        int size = model.size();
        if (size == 0) {
            return;
        }
        int next = idx < 0 ? 0 : Math.max(0, Math.min(size - 1, idx + delta));
        resultList.setSelectedIndex(next);
        resultList.ensureIndexIsVisible(next);
    }

    private void openSelected() {
        Object sel = resultList.getSelectedValue();
        if (sel == null) {
            return;
        }
        if (sel instanceof CommandRow) {
            dispose();
            host.runCommand(((CommandRow) sel).cmd.id);
            return;
        }
        if (sel instanceof GlobalSearchIndex.Hit) {
            dispose();
            host.openEntryDetail(((GlobalSearchIndex.Hit) sel).entry);
        }
    }

    /** 命令行 */
    private static final class CommandRow {
        final CommandDef cmd;

        CommandRow(CommandDef cmd) {
            this.cmd = cmd;
        }
    }

    /** 结果行渲染：主行（标题/账号 + 分类），副行（命中字段 + 摘要） */
    private static final class RowRenderer extends JPanel implements ListCellRenderer<Object> {
        private final JLabel title = new JLabel();
        private final JLabel sub = new JLabel();
        private final JLabel tag = new JLabel();
        private boolean selected;

        RowRenderer() {
            super(new BorderLayout(8, 2));
            setOpaque(true);
            setBorder(new EmptyBorder(4, 10, 4, 10));

            tag.setOpaque(true);
            tag.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            tag.setHorizontalAlignment(SwingConstants.CENTER);

            title.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
            sub.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));

            JPanel left = new JPanel(new BorderLayout(0, 2));
            left.setOpaque(false);
            left.add(title, BorderLayout.NORTH);
            left.add(sub, BorderLayout.SOUTH);
            add(left, BorderLayout.CENTER);
            add(tag, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            if (value instanceof CommandRow) {
                CommandDef cmd = ((CommandRow) value).cmd;
                title.setText("> " + cmd.id + "   " + cmd.label);
                title.setForeground(UiTheme.PRIMARY_DARK);
                sub.setText(cmd.hint);
                tag.setText("命令");
                tag.setForeground(Color.WHITE);
                tag.setBackground(UiTheme.ACCENT);
            } else if (value instanceof GlobalSearchIndex.Hit) {
                GlobalSearchIndex.Hit hit = (GlobalSearchIndex.Hit) value;
                title.setText(hit.summary());
                title.setForeground(UiTheme.TEXT_MAIN);
                sub.setText("命中[" + hit.matchedField + "]  " + nullToEmpty(hit.entry.getCategory())
                        + (hit.entry.getNote() == null || hit.entry.getNote().trim().isEmpty()
                        ? "" : "  ·  " + oneLine(hit.entry.getNote())));
                tag.setText(hit.entry.getCategory());
                tag.setForeground(Color.WHITE);
                tag.setBackground(tagColor(hit.entry.getCategory()));
            }
            setBackground(isSelected ? UiTheme.TABLE_SELECT : new Color(0xF8FAFF));
            return this;
        }

        private static Color tagColor(String category) {
            if ("WiFi".equals(category)) {
                return new Color(0x2E86DE);
            }
            if ("银行卡".equals(category)) {
                return new Color(0xE67E22);
            }
            if ("证件".equals(category)) {
                return new Color(0x27AE60);
            }
            if ("API Key".equals(category)) {
                return new Color(0x8E44AD);
            }
            if ("应用".equals(category)) {
                return new Color(0x16A085);
            }
            return UiTheme.PRIMARY;
        }

        private static String oneLine(String s) {
            String t = s.replace('\n', ' ').replace('\r', ' ').trim();
            return t.length() > 24 ? t.substring(0, 24) + "…" : t;
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }
    }

    /** 子序列匹配（命令匹配辅助） */
    private static boolean isSubsequence(String a, String b) {
        if (a == null || a.isEmpty()) {
            return true;
        }
        if (b == null || b.isEmpty()) {
            return false;
        }
        int i = 0;
        int len = a.length();
        for (int j = 0; j < b.length() && i < len; j++) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
            }
        }
        return i == len;
    }
}
