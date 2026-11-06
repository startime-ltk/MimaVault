package com.mimavault.ui;

import com.mimavault.config.AppConfig;
import com.mimavault.util.DataDirRecovery;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「数据目录不见了」对话框（v8.35.3）
 *
 * <p>上一版只给一个"重新指定数据目录…"，实际上是把"数据去哪了"这个问题又丢回给用户：
 * 路径那么长，谁记得住。这里改成 <b>程序先自己找</b>——后台扫盘，把含有密码库的目录
 * 连同"多少条记录 / 最后修改时间"一起列出来，用户认条数就能挑对。
 *
 * <p>三个出口：
 * <ul>
 *   <li>用选中的目录（写回定位文件，立即生效，不必重启）</li>
 *   <li>继续使用空目录（本次先能用，定位文件保持原样，下次启动仍会提醒）</li>
 *   <li>退出（先去把数据找回来再开程序）</li>
 * </ul>
 */
public final class MissingDataDirDialog {

    /** 用户的选择 */
    public enum Choice { USE_DIR, CONTINUE_EMPTY, EXIT }

    /** 对话框结果 */
    public static final class Outcome {
        public final Choice choice;
        public final Path dir;

        Outcome(Choice choice, Path dir) {
            this.choice = choice;
            this.dir = dir;
        }
    }

    private static final Comparator<DataDirRecovery.Candidate> ORDER =
            Comparator.<DataDirRecovery.Candidate>comparingInt(c -> c.records < 0 ? -2 : c.records).reversed()
                    .thenComparing(Comparator.<DataDirRecovery.Candidate>comparingLong(c -> c.lastModified).reversed());

    private final Path missing;
    private final Path current;
    private final List<DataDirRecovery.Candidate> found = new ArrayList<>();
    private final DefaultListModel<DataDirRecovery.Candidate> model = new DefaultListModel<>();
    private final JList<DataDirRecovery.Candidate> list = new JList<>(model);
    private final JLabel status = new JLabel(" ");

    private JDialog dialog;
    private Outcome outcome;
    private volatile boolean cancelled;

    private MissingDataDirDialog(Path missing, Path current) {
        this.missing = missing;
        this.current = current;
    }

    /** 弹出对话框并等待用户决定（必须在 EDT 上调用） */
    public static Outcome show(Path missing, Path current) {
        MissingDataDirDialog d = new MissingDataDirDialog(missing, current);
        d.build();
        d.startScan();
        d.dialog.setVisible(true);
        d.cancelled = true;
        return d.outcome == null ? new Outcome(Choice.EXIT, null) : d.outcome;
    }

    // ------------------------------------------------------------------ 界面

    private void build() {
        dialog = new JDialog((Frame) null, "数据目录不见了", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(0, 0));

        JPanel head = new JPanel(new BorderLayout(0, 8));
        head.setBorder(new EmptyBorder(18, 22, 10, 22));
        head.setBackground(Color.WHITE);

        JLabel title = new JLabel("上次用的数据目录不在了");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 17));
        title.setForeground(UiTheme.TEXT_MAIN);

        JTextArea msg = new JTextArea(
                "原路径：" + (missing == null ? "（未知）" : missing.toString()) + "\n"
                        + "现在打开的是空数据目录：" + current + "\n\n"
                        + "下面是这台电脑上找到的密码库。记录条数和你印象中一致的那个，就是它。");
        msg.setEditable(false);
        msg.setOpaque(false);
        msg.setLineWrap(true);
        msg.setWrapStyleWord(true);
        msg.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        msg.setForeground(UiTheme.TEXT_SUB);
        msg.setBorder(null);

        head.add(title, BorderLayout.NORTH);
        head.add(msg, BorderLayout.CENTER);

        list.setCellRenderer(new CandidateRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Color.WHITE);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    useSelected();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(UiTheme.CARD_BORDER));
        scroll.getViewport().setBackground(Color.WHITE);

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setBorder(new EmptyBorder(0, 22, 0, 22));
        center.setBackground(Color.WHITE);
        center.add(scroll, BorderLayout.CENTER);

        status.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        status.setForeground(UiTheme.TEXT_SUB);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);

        JButton useBtn = new JButton("用选中的目录");
        JButton pickBtn = new JButton("手动选择文件夹…");
        JButton emptyBtn = new JButton("继续用空目录");
        JButton exitBtn = new JButton("退出");

        useBtn.addActionListener(e -> useSelected());
        pickBtn.addActionListener(e -> pickManually());
        emptyBtn.addActionListener(e -> finish(new Outcome(Choice.CONTINUE_EMPTY, null)));
        exitBtn.addActionListener(e -> finish(new Outcome(Choice.EXIT, null)));
        dialog.getRootPane().setDefaultButton(useBtn);

        buttons.add(useBtn);
        buttons.add(pickBtn);
        buttons.add(emptyBtn);
        buttons.add(exitBtn);

        JPanel south = new JPanel(new BorderLayout(0, 10));
        south.setBorder(new EmptyBorder(10, 22, 18, 22));
        south.setBackground(Color.WHITE);
        south.add(status, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);

        dialog.add(head, BorderLayout.NORTH);
        dialog.add(center, BorderLayout.CENTER);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.getContentPane().setBackground(Color.WHITE);
        dialog.setSize(700, 470);
        dialog.setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------ 扫描

    private void startScan() {
        status.setText("正在搜索这台电脑上的密码库…");
        Thread t = new Thread(() -> {
            DataDirRecovery.scan(c -> {
                synchronized (found) {
                    found.add(c);
                }
                SwingUtilities.invokeLater(this::refresh);
            }, () -> cancelled);
            SwingUtilities.invokeLater(this::onScanFinished);
        }, "mimavault-datadir-scan");
        t.setDaemon(true);
        t.start();
    }

    /** 把已找到的候选按"条数多的在前"重排并刷新列表（保留用户已选中的那一项） */
    private void refresh() {
        if (cancelled) {
            return;
        }
        DataDirRecovery.Candidate selected = list.getSelectedValue();
        List<DataDirRecovery.Candidate> snapshot;
        synchronized (found) {
            snapshot = new ArrayList<>(found);
        }
        snapshot.sort(ORDER);
        model.clear();
        for (DataDirRecovery.Candidate c : snapshot) {
            model.addElement(c);
        }
        if (selected != null) {
            list.setSelectedValue(selected, false);
        } else if (!model.isEmpty()) {
            list.setSelectedIndex(0); // 默认选中条数最多的那个
        }
        status.setText("已找到 " + model.size() + " 个，正在继续搜索…");
    }

    private void onScanFinished() {
        if (cancelled) {
            return;
        }
        if (model.isEmpty()) {
            status.setText("没找到密码库。可以点「手动选择文件夹…」自己定位，或先用空目录。");
            status.setForeground(UiTheme.ACCENT);
        } else {
            status.setText("搜索完成，共找到 " + model.size() + " 个。确认条数后点「用选中的目录」。");
            status.setForeground(UiTheme.TEXT_SUB);
        }
    }

    // ------------------------------------------------------------------ 动作

    private void useSelected() {
        DataDirRecovery.Candidate c = list.getSelectedValue();
        if (c == null) {
            status.setText("请先在列表里选中一个目录（或点「手动选择文件夹…」）。");
            status.setForeground(UiTheme.DANGER);
            return;
        }
        finish(new Outcome(Choice.USE_DIR, c.dir));
    }

    private void pickManually() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择数据目录（里面应有 MimaVault.db）");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        // 起始目录定在"原路径的上一级"，方便就近找到被移走的文件夹
        if (missing != null && missing.getParent() != null && Files.isDirectory(missing.getParent())) {
            chooser.setCurrentDirectory(missing.getParent().toFile());
        }
        if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path picked = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        boolean ok = Files.isRegularFile(picked.resolve(DataDirRecovery.DB_NAME))
                || Files.isRegularFile(picked.resolve(DataDirRecovery.LEGACY_DB_NAME));
        if (!ok) {
            status.setText("这个目录里没有密码库：" + picked + "，请选另一个。");
            status.setForeground(UiTheme.DANGER);
            return;
        }
        finish(new Outcome(Choice.USE_DIR, picked));
    }

    private void finish(Outcome o) {
        this.outcome = o;
        cancelled = true;
        dialog.dispose();
    }

    // ------------------------------------------------------------------ 渲染

    private final class CandidateRenderer extends JPanel implements ListCellRenderer<DataDirRecovery.Candidate> {

        private final JLabel top = new JLabel();
        private final JLabel bottom = new JLabel();

        CandidateRenderer() {
            setLayout(new BorderLayout(0, 3));
            setBorder(new EmptyBorder(7, 12, 7, 12));
            top.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
            bottom.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            add(top, BorderLayout.NORTH);
            add(bottom, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends DataDirRecovery.Candidate> l,
                                                      DataDirRecovery.Candidate c, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            boolean isCurrent = c.dir.toAbsolutePath().normalize()
                    .equals(AppConfig.DATA_DIR.toAbsolutePath().normalize());
            boolean empty = c.records == 0;

            String head = c.countText() + "　最后修改 " + c.timeText();
            if (isCurrent) {
                head += "　（当前空目录）";
            }
            top.setText(head);
            bottom.setText(c.dir.toString());

            if (isSelected) {
                setBackground(new Color(0xE7EDFF));
                top.setForeground(empty ? UiTheme.TEXT_SUB : UiTheme.PRIMARY_DARK);
                bottom.setForeground(UiTheme.TEXT_MAIN);
            } else {
                setBackground(Color.WHITE);
                top.setForeground(empty ? UiTheme.TEXT_SUB : UiTheme.TEXT_MAIN);
                bottom.setForeground(UiTheme.TEXT_SUB);
            }
            return this;
        }
    }
}
