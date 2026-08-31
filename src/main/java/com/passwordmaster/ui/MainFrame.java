package com.passwordmaster.ui;

import com.passwordmaster.config.AppConfig;
import com.passwordmaster.model.Entry;
import com.passwordmaster.service.PasswordHealthChecker;
import com.passwordmaster.service.PasswordService;
import com.passwordmaster.util.AesUtil;
import com.passwordmaster.util.BackupUtil;
import com.passwordmaster.util.ClipboardSafe;
import com.passwordmaster.util.CsvUtil;
import com.passwordmaster.util.DragDropUtil;
import com.passwordmaster.util.ImageUtil;
import com.passwordmaster.util.OcrUtil;
import com.passwordmaster.util.PasswordStrengthUtil;
import com.passwordmaster.util.TextBatchParser;

import javax.crypto.SecretKey;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final JPanel centerBox = new JPanel(new BorderLayout(0, 8));
    private final JLabel statusLabel = new JLabel("支持将图片拖入窗口，自动识别并导入");

    private List<Entry> currentList;

    /** 是否只显示弱密码条目（点击顶部提醒按钮切换） */
    private boolean weakOnly = false;
    /** 顶部弱密码提醒按钮：显示统计数，点击筛选/恢复全部 */
    private final GradientButton weakBtn = GradientButton.danger("弱密码");

    // ---------- 空闲自动登出（无操作安全锁定） ----------
    /** 空闲超时：连续 3 分钟（180000ms）无鼠标/键盘操作即自动登出，需重新输入主密码 */
    private static final long IDLE_TIMEOUT_MS = 180_000;
    /** 空闲检查周期：每 30 秒检查一次最后活动时间（精度满足需求且开销极小） */
    private static final int IDLE_CHECK_INTERVAL_MS = 30_000;
    /** 最后活动时间戳（任何全局鼠标/键盘事件都会刷新） */
    private volatile long lastActivity = System.currentTimeMillis();
    /** 全局输入监听器（仅登录状态下注册，登出即移除） */
    private java.awt.event.AWTEventListener idleListener;
    /** 空闲检查定时器（仅登录状态下运行） */
    private Timer idleTimer;

    public MainFrame(PasswordService service, SecretKey key) {
        super("密匣 MimaVault");
        this.service = service;
        this.key = key;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        buildHeader();
        centerBox.setOpaque(false);
        centerBox.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        add(centerBox, BorderLayout.CENTER);
        buildToolbar();
        buildTable();
        buildStatusBar();
        installDropSupport();

        refreshTable(null, null);

        setSize(860, 520);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(720, 420));
        setIconImage(UiTheme.getAppIcon());

        // 主界面显示即视为已登录：启动空闲自动登出监控
        startIdleMonitor();
    }

    // ---------- 空闲自动登出：登录后启动，登出后停止 ----------

    /**
     * 注册全局输入监听 + 启动空闲检查定时器。
     * 只监听鼠标移动/点击/滚轮与键盘事件；登录界面打开期间不注册（登录成功后由新 MainFrame 重新启动）。
     */
    private void startIdleMonitor() {
        long mask = AWTEvent.MOUSE_MOTION_EVENT_MASK
                | AWTEvent.MOUSE_EVENT_MASK
                | AWTEvent.MOUSE_WHEEL_EVENT_MASK
                | AWTEvent.KEY_EVENT_MASK;
        idleListener = event -> lastActivity = System.currentTimeMillis();
        Toolkit.getDefaultToolkit().addAWTEventListener(idleListener, mask);
        idleTimer = new Timer(IDLE_CHECK_INTERVAL_MS, e -> checkIdle());
        idleTimer.start();
    }

    /** 每 30 秒检查一次：超过 3 分钟无操作则自动登出（对话框打开期间计时同样生效） */
    private void checkIdle() {
        if (System.currentTimeMillis() - lastActivity >= IDLE_TIMEOUT_MS) {
            autoLogout();
        }
    }

    /** 停止监听与计时（登出/窗口销毁时调用，避免泄漏与登录界面误触发） */
    private void stopIdleMonitor() {
        if (idleTimer != null) {
            idleTimer.stop();
            idleTimer = null;
        }
        if (idleListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(idleListener);
            idleListener = null;
        }
    }

    /**
     * 空闲超时自动登出：
     * 1. 停止空闲监控；
     * 2. 关闭全部窗口（含详情/编辑等模态对话框），释放内存中的 SecretKey 引用（密钥对象随 GC 回收）；
     * 3. 回到主密码登录界面，验证通过后重新创建主界面并重新计时；取消则退出程序。
     */
    private void autoLogout() {
        stopIdleMonitor();
        for (Window w : Window.getWindows()) {
            if (w != this && w.isShowing()) {
                w.dispose();
            }
        }
        dispose();
        char[] masterPassword = LoginDialog.showAndVerify(null, service);
        if (masterPassword == null) {
            System.exit(0);
            return;
        }
        try {
            MainFrame frame = new MainFrame(service, service.deriveKey(masterPassword));
            frame.setVisible(true);
        } finally {
            Arrays.fill(masterPassword, '\0'); // 明文用完即清
        }
    }

    /** 顶部渐变标题栏：艺术字标题 + 副标题 */
    private void buildHeader() {
        GradientPanel header = new GradientPanel(UiTheme.PRIMARY, UiTheme.PURPLE);
        header.setLayout(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));

        ArtTextLabel title = new ArtTextLabel("密匣 MimaVault", 24);
        title.setGradient(Color.WHITE, new Color(0xE8E4FF));
        title.setStroke(new Color(0x4A3A7A), 1.2f);
        title.setShadow(new Color(0, 0, 0, 70), 2);
        title.setPreferredSize(new Dimension(420, 38));
        header.add(title, BorderLayout.WEST);

        JLabel sub = new JLabel("本地加密 · 安全保管", SwingConstants.RIGHT);
        sub.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        sub.setForeground(new Color(0xF0ECFF));
        header.add(sub, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
    }

    private void buildToolbar() {
        RoundedPanel top = new RoundedPanel(16);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row1.setOpaque(false);
        row1.add(new JLabel("搜索："));
        searchField.putClientProperty("JTextField.placeholderText", "平台 / 账号 / 手机 / 邮箱");
        searchField.setPreferredSize(new Dimension(240, 30));
        row1.add(searchField);
        row1.add(new JLabel("类型："));
        categoryFilter.setPreferredSize(new Dimension(80, 30));
        row1.add(categoryFilter);
        top.add(row1);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.setOpaque(false);
        // 密码健康检测入口：糖果绿胖按钮，打开安全报告（基于内存明文检测，不落盘）
        GradientButton healthBtn = new GradientButton("安全报告",
                new Color(0x7ED321), new Color(0x4CAF50), Color.WHITE, new Color(0x5DAF1E));
        healthBtn.setPreferredSize(new Dimension(120, 32));
        healthBtn.addActionListener(e -> onSecurityReport());
        row2.add(healthBtn);
        row2.add(createToolButton("新增", e -> onAdd()));
        row2.add(createToolButton("删除", e -> onDelete()));
        row2.add(createToolButton("详情", e -> onDetail()));
        row2.add(createToolButton("导出", e -> onExport()));
        row2.add(createToolButton("导出CSV", e -> onExportCsv()));
        row2.add(createToolButton("导入", e -> onImport()));
        row2.add(createToolButton("导入CSV", e -> onImportCsv()));
        row2.add(createToolButton("智能导入", e -> onSmartImport()));
        // 弱密码提醒：显示全库弱密码条数，点击筛选弱密码 / 再次点击恢复全部
        weakBtn.addActionListener(e -> toggleWeakFilter());
        row2.add(weakBtn);
        top.add(row2);

        centerBox.add(top, BorderLayout.NORTH);
    }

    private GradientButton createToolButton(String text, java.awt.event.ActionListener listener) {
        GradientButton btn;
        if ("新增".equals(text)) {
            btn = GradientButton.primary(text);
        } else if ("智能导入".equals(text)) {
            btn = GradientButton.accent(text);
        } else {
            btn = GradientButton.secondary(text);
        }
        btn.addActionListener(listener);
        return btn;
    }

    private void buildTable() {
        tableModel = new DefaultTableModel(new String[]{"类型", "平台", "账号", "手机", "邮箱", "备注", "强度"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setRowHeight(30);
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
        table.getColumnModel().getColumn(5).setPreferredWidth(200);
        table.getColumnModel().getColumn(6).setPreferredWidth(60);

        // 强度列着色：弱=红 / 中=橙 / 强=绿 / 未设置=灰
        table.getColumnModel().getColumn(6).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                c.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
                if (!isSelected) {
                    String v = value == null ? "" : value.toString();
                    switch (v) {
                        case "弱":
                            c.setForeground(new Color(0xE74C3C));
                            break;
                        case "中":
                            c.setForeground(new Color(0xE67E22));
                            break;
                        case "强":
                            c.setForeground(new Color(0x27AE60));
                            break;
                        default:
                            c.setForeground(new Color(0xB0B6C8));
                    }
                }
                return c;
            }
        });

        // 双击查看详情 + 右键复制账号/密码/邮箱
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    onDetail();
                }
            }

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showCopyMenu(e);
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showCopyMenu(e);
                }
            }
        });

        // 表格放入圆角卡片，形成卡片感
        RoundedPanel tableCard = new RoundedPanel(14);
        tableCard.setLayout(new BorderLayout());
        tableCard.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        sp.getViewport().setBackground(Color.WHITE);
        tableCard.add(sp, BorderLayout.CENTER);
        centerBox.add(tableCard, BorderLayout.CENTER);

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

    /** 底部状态栏：提示拖拽识别状态 */
    private void buildStatusBar() {
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        status.setOpaque(false);
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statusLabel.setForeground(UiTheme.TEXT_SUB);
        status.add(statusLabel);
        centerBox.add(status, BorderLayout.SOUTH);
    }

    /** 注册图片拖入：窗口任意位置拖入图片 → 自动 OCR 识别并进入导入预览 */
    private void installDropSupport() {
        DragDropUtil.installImageDropRecursively(getContentPane(), (images, ignored) -> {
            if (images.isEmpty()) {
                if (!ignored.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                            "仅支持图片文件（png/jpg/jpeg/bmp/gif/webp）\n已忽略 " + ignored.size() + " 个非图片文件",
                            "提示", JOptionPane.WARNING_MESSAGE);
                }
                return;
            }
            if (!ignored.isEmpty()) {
                statusLabel.setText("已忽略 " + ignored.size() + " 个非图片文件");
            }
            startOcrImport(images);
        });
    }

    /**
     * 拖入图片的识别导入流程：
     * 后台逐个 OCR（状态栏/标题显示进度）→ 文本合并 → TextBatchParser 解析 → ImportPreviewDialog 预览
     */
    private void startOcrImport(List<File> images) {
        final int total = images.size();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        statusLabel.setText("识别中 0/" + total + " ...");
        SwingWorker<StringBuilder, Integer> worker = new SwingWorker<StringBuilder, Integer>() {

            private int failed = 0;
            private final StringBuilder text = new StringBuilder();

            @Override
            protected StringBuilder doInBackground() {
                int i = 0;
                for (File f : images) {
                    i++;
                    publish(i);
                    try {
                        String ocr;
                        if (!OcrUtil.isLanguageReady()) {
                            // 首次需释放内置语言包：弹窗提示必须在 EDT 上执行，避免后台线程弹窗
                            final File ff = f;
                            final String[] result = {null};
                            SwingUtilities.invokeAndWait(() -> {
                                try {
                                    result[0] = OcrUtil.doOcr(ff, MainFrame.this);
                                } catch (Exception ex) {
                                    result[0] = null;
                                }
                            });
                            ocr = result[0];
                        } else {
                            ocr = OcrUtil.doOcr(f, MainFrame.this);
                        }
                        if (ocr == null) {
                            failed++; // 语言包释放失败或识别失败
                        } else if (ocr.trim().isEmpty()) {
                            failed++;
                        } else {
                            if (text.length() > 0) {
                                text.append("\n");
                            }
                            text.append("[图片").append(i).append(":").append(f.getName()).append(" 识别结果]\n");
                            text.append(ocr.trim());
                        }
                    } catch (Exception ex) {
                        failed++;
                    }
                }
                return text;
            }

            @Override
            protected void process(List<Integer> chunks) {
                int cur = chunks.get(chunks.size() - 1);
                statusLabel.setText("识别中 " + cur + "/" + total + " ...");
                setTitle("密匣 MimaVault - 识别中 " + cur + "/" + total);
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                setTitle("密匣 MimaVault");
                try {
                    String raw = get().toString().trim();
                    if (raw.isEmpty()) {
                        String msg = failed > 0
                                ? "共有 " + failed + " 张图片识别失败，未进入导入流程。\n请检查图片清晰度，或确认内置语言包释放正常。"
                                : "未能从图片中识别到文本。";
                        statusLabel.setText("识别失败 " + failed + " 张，未进入导入");
                        JOptionPane.showMessageDialog(MainFrame.this, msg, "识别结果", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    List<TextBatchParser.RawRecord> records = TextBatchParser.parseBatch(raw, "拖拽图片");
                    if (records.isEmpty()) {
                        statusLabel.setText("已识别但未解析出账号/密码记录");
                        JOptionPane.showMessageDialog(MainFrame.this,
                                "已识别到文本，但未能解析出账号/密码记录。\n可尝试「智能导入」或手动新增。",
                                "导入提示", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    statusLabel.setText(failed > 0
                            ? "识别完成（其中 " + failed + " 张失败），共解析 " + records.size() + " 条记录"
                            : "识别完成，共解析 " + records.size() + " 条记录");
                    new ImportPreviewDialog(MainFrame.this, records, service, key).setVisible(true);
                    refreshTable(null, null);
                } catch (Exception ex) {
                    statusLabel.setText("导入失败");
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "导入失败：" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()),
                            "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /**
     * 刷新表格数据
     * 强度列需逐条解密计算：数据量小（本地个人库）时同步计算即可；
     * 若未来条目达到数千级，可改为 SwingWorker 后台解密后回填，避免界面卡顿。
     */
    private void refreshTable(String kw, String cat) {
        String keyword = kw == null ? searchField.getText() : kw;
        String category = cat == null ? (String) categoryFilter.getSelectedItem() : cat;
        currentList = service.search(keyword, category);
        if (weakOnly) {
            List<Entry> filtered = new ArrayList<>();
            for (Entry e : currentList) {
                if (isWeakEntry(e)) {
                    filtered.add(e);
                }
            }
            currentList = filtered;
        }

        tableModel.setRowCount(0);
        for (Entry e : currentList) {
            tableModel.addRow(new Object[]{
                    nullToEmpty(e.getCategory()),
                    nullToEmpty(e.getPlatform()),
                    nullToEmpty(e.getAccount()),
                    nullToEmpty(e.getPhone()),
                    nullToEmpty(e.getEmail()),
                    nullToEmpty(e.getNote()),
                    strengthText(e)
            });
        }
        updateWeakBadge();
    }

    /** 解密并计算条目密码强度；未设置密码显示「—」 */
    private String strengthText(Entry e) {
        String pwd = service.decryptPassword(e, key);
        if (pwd == null || pwd.isEmpty()) {
            return "—";
        }
        return PasswordStrengthUtil.evaluate(pwd).level.getText();
    }

    /** 是否为弱密码条目：有密码且强度为弱 */
    private boolean isWeakEntry(Entry e) {
        String pwd = service.decryptPassword(e, key);
        if (pwd == null || pwd.isEmpty()) {
            return false;
        }
        return PasswordStrengthUtil.evaluate(pwd).level == PasswordStrengthUtil.Level.WEAK;
    }

    /** 刷新顶部弱密码提醒按钮：统计全库弱密码条数，N=0 显示「密码强度良好」 */
    private void updateWeakBadge() {
        int weak = 0;
        for (Entry e : service.listEntries()) {
            if (isWeakEntry(e)) {
                weak++;
            }
        }
        if (weakOnly) {
            weakBtn.setText("显示全部");
        } else if (weak == 0) {
            weakBtn.setText("密码强度良好");
        } else {
            weakBtn.setText("有 " + weak + " 条弱密码");
        }
    }

    /** 点击弱密码提醒：筛选出弱密码条目，再次点击恢复全部 */
    private void toggleWeakFilter() {
        weakOnly = !weakOnly;
        refreshTable(null, null);
    }

    /**
     * 打开密码健康检测「安全报告」。
     * 基于内存中已解密条目检测，不重新读库、不落盘、不打印明文。
     */
    private void onSecurityReport() {
        List<Entry> all = service.listEntries();
        java.util.Map<Long, String> plainMap = new java.util.HashMap<>();
        for (Entry e : all) {
            plainMap.put(e.getId(), service.decryptPassword(e, key));
        }
        PasswordHealthChecker.ReportResult result =
                PasswordHealthChecker.check(all, e -> plainMap.get(e.getId()));
        new SecurityReportDialog(this, result, this::locateEntryById).setVisible(true);
    }

    /**
     * 定位并选中指定条目（安全报告跳转用）。
     * 若条目不在当前列表（处于搜索/分类/弱密码筛选状态），先恢复全部显示再定位，
     * 并滚动到可视区域选中。
     */
    public void locateEntryById(long entryId) {
        int index = -1;
        for (int i = 0; i < currentList.size(); i++) {
            if (currentList.get(i).getId() == entryId) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // 不在当前列表：清空搜索与筛选，恢复全部条目
            searchField.setText("");
            categoryFilter.setSelectedItem("全部");
            weakOnly = false;
            refreshTable(null, null);
            for (int i = 0; i < currentList.size(); i++) {
                if (currentList.get(i).getId() == entryId) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) {
            statusLabel.setText("未找到该条目（可能已被删除）");
            return;
        }
        table.setRowSelectionInterval(index, index);
        table.scrollRectToVisible(table.getCellRect(index, 0, true));
        table.requestFocusInWindow();
        Entry hit = currentList.get(index);
        statusLabel.setText("已定位：" + hit.getPlatform() + (hit.getAccount() == null ? "" : " / " + hit.getAccount()));
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

    /** 列表行右键菜单：复制账号/密码/邮箱（统一走 ClipboardSafe 30 秒自动清除） */
    private void showCopyMenu(java.awt.event.MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0 || row >= currentList.size()) {
            return;
        }
        table.setRowSelectionInterval(row, row);
        JPopupMenu menu = buildCopyMenu(currentList.get(row));
        menu.show(table, e.getX(), e.getY());
    }

    /** 构建右键复制菜单（独立方法便于测试） */
    JPopupMenu buildCopyMenu(Entry selected) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem copyAccount = new JMenuItem("复制账号");
        copyAccount.addActionListener(ev -> copyListValue(selected.getAccount(), "账号"));

        JMenuItem copyPassword = new JMenuItem("复制密码");
        copyPassword.addActionListener(ev -> {
            String pwd = service.decryptPassword(selected, key);
            if (pwd == null || pwd.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "该条目未设置密码", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            ClipboardSafe.copySecret(pwd.trim());
            statusLabel.setText("密码已复制，30 秒后自动清除剪贴板");
        });

        JMenuItem copyEmail = new JMenuItem("复制邮箱");
        copyEmail.addActionListener(ev -> copyListValue(selected.getEmail(), "邮箱"));

        menu.add(copyAccount);
        menu.add(copyPassword);
        menu.add(copyEmail);
        return menu;
    }

    private void copyListValue(String value, String fieldName) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            JOptionPane.showMessageDialog(this, "该条目未设置" + fieldName, "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ClipboardSafe.copySecret(v);
        statusLabel.setText(fieldName + "已复制，30 秒后自动清除剪贴板");
    }

    private void onExport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出备份");
        chooser.setSelectedFile(new java.io.File("MimaVault_backup_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".pmaster"));
        chooser.setFileFilter(new FileNameExtensionFilter("密匣备份 (*.pmaster)", "pmaster"));
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
            BackupUtil.export(all, key, service.getMasterSaltHex(), service.getMasterIterations(), target);
            JOptionPane.showMessageDialog(this, "导出成功，共 " + all.size() + " 条记录\n" + target, "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导出失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onImport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入备份");
        chooser.setFileFilter(new FileNameExtensionFilter("密匣备份 (*.pmaster)", "pmaster"));
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
        BackupUtil.BackupPackage pack;
        try {
            char[] importPwd = pwdField.getPassword();
            try {
                // 多通道解密：header PBKDF2 → 当前库密钥 → 旧版 SHA-256（见 BackupUtil.importBackup）
                SecretKey currentKey = service.deriveKey(importPwd);
                pack = BackupUtil.importBackup(source, importPwd, currentKey);
            } finally {
                java.util.Arrays.fill(importPwd, '\0'); // 明文用完即清
            }
            if (pack.items == null || pack.items.isEmpty()) {
                JOptionPane.showMessageDialog(this, "备份文件中没有数据", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            doImport(pack);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导入失败：主密码错误或文件损坏\n" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 导出为 CSV（明文文件，导出前红色风险警告） */
    private void onExportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出为 CSV");
        chooser.setSelectedFile(new java.io.File("MimaVault_export_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".csv"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV 文件 (*.csv)", "csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (!target.toString().toLowerCase().endsWith(".csv")) {
            target = Paths.get(target.toString() + ".csv");
        }
        // 明文风险红色警告
        JLabel warn = new JLabel("<html><font color='#C62828'><b>警告</b></font>：CSV 为<font color='#C62828'>明文文件</font>，包含全部密码明文。<br>请妥善保管，切勿公开传输或分享。</html>");
        warn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        int r = JOptionPane.showConfirmDialog(this, warn, "导出明文 CSV", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            List<Entry> all = service.listEntries();
            if (all.isEmpty()) {
                JOptionPane.showMessageDialog(this, "当前没有可导出的数据", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            CsvUtil.export(all, key, target);
            JOptionPane.showMessageDialog(this, "导出成功，共 " + all.size() + " 条记录\n" + target, "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导出失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 从 CSV 导入（通用格式，明文密码加密后入库，复用现有加密链路） */
    private void onImportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("从 CSV 导入");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV 文件 (*.csv)", "csv"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = chooser.getSelectedFile().toPath();
        try {
            List<CsvUtil.CsvRow> rows = CsvUtil.parse(source);
            if (rows.isEmpty()) {
                JOptionPane.showMessageDialog(this, "CSV 中没有可导入的数据", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            // 明文密码加密后转为条目，与既有 Entry 加密链路一致，不落明文
            List<Entry> entries = new ArrayList<>();
            for (CsvUtil.CsvRow row : rows) {
                Entry e = new Entry();
                e.setCategory(Entry.CATEGORY_WEBSITE);
                e.setPlatform(row.name);
                e.setAccount(row.username);
                e.setNote(row.notes);
                if (row.password != null && !row.password.isEmpty()) {
                    e.setPasswordEnc(AesUtil.encrypt(row.password, key));
                }
                entries.add(e);
            }
            doImportCsv(entries);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "CSV 解析失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** CSV 导入确认与入库：复用现有覆盖/追加确认流程（覆盖模式沿用自动备份保护） */
    private void doImportCsv(List<Entry> entries) {
        boolean hasExisting = !service.listEntries().isEmpty();
        String mode = "追加";
        if (hasExisting) {
            Object[] options = {"覆盖", "追加"};
            int choice = JOptionPane.showOptionDialog(this,
                    "当前已有数据，请选择导入方式：\n覆盖：清空现有数据后导入\n追加：直接追加全部条目（同名也追加，默认策略）",
                    "导入方式", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
            if (choice == JOptionPane.CLOSED_OPTION) {
                return;
            }
            mode = (choice == 0) ? "覆盖" : "追加";
        }

        // 覆盖导入保护：清空旧数据前自动备份（与 .pmaster 导入一致）
        Path autoBackup = backupBeforeOverwrite();
        if (autoBackup == IMPORT_CANCELLED) {
            return;
        }

        // 统计与现有条目同名（平台+账号）的数量，仅用于提示
        int sameName = 0;
        Set<String> existingKeys = new HashSet<>();
        for (Entry e : service.listEntries()) {
            existingKeys.add(keyOf(e));
        }
        for (Entry e : entries) {
            if (existingKeys.contains(keyOf(e))) {
                sameName++;
            } else {
                existingKeys.add(keyOf(e));
            }
        }

        try {
            if ("覆盖".equals(mode)) {
                service.clearAll();
            }
            service.insertAll(entries);
        } catch (Exception ex) {
            refreshTable(null, null);
            if (autoBackup != null) {
                JOptionPane.showMessageDialog(this,
                        "导入失败，已自动备份原数据到：\n" + autoBackup
                                + "\n\n请先恢复备份，或排查导入文件后重试。",
                        "导入失败", JOptionPane.ERROR_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                        "导入失败：" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
            return;
        }

        refreshTable(null, null);
        JOptionPane.showMessageDialog(this,
                "导入完成：成功 " + entries.size() + " 条"
                        + (sameName > 0 ? "（其中与现有条目同名 " + sameName + " 条，已按追加策略导入）" : ""),
                "导入完成", JOptionPane.INFORMATION_MESSAGE);
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

        // 覆盖导入保护：清空旧数据前自动备份到 data/backup/，防止导入失败导致原数据全丢
        Path autoBackup = backupBeforeOverwrite();
        if (autoBackup == IMPORT_CANCELLED) {
            return;
        }
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

        try {
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
        } catch (Exception ex) {
            refreshTable(null, null);
            if (autoBackup != null) {
                JOptionPane.showMessageDialog(this,
                        "导入失败，已自动备份原数据到：\n" + autoBackup
                                + "\n\n请先恢复备份，或排查导入文件后重试。",
                        "导入失败", JOptionPane.ERROR_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                        "导入失败：" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
            return;
        }

        refreshTable(null, null);
        JOptionPane.showMessageDialog(this,
                "导入完成：成功 " + restored + " 条" + (skipped > 0 ? "，重复跳过 " + skipped + " 条" : ""),
                "导入完成", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 覆盖导入取消哨兵 */
    private static final Path IMPORT_CANCELLED = Paths.get("__CANCELLED__");

    /**
     * 覆盖导入前的自动备份：导出当前全部条目为 data/backup/backup_时间戳_before_import.pmaster
     *
     * @return 备份文件路径；空库/用户放弃备份继续导入时返回 null；用户取消导入返回 IMPORT_CANCELLED
     */
    private Path backupBeforeOverwrite() {
        List<Entry> all = service.listEntries();
        if (all.isEmpty()) {
            return null;
        }
        try {
            Path dir = AppConfig.DATA_DIR.resolve("backup");
            Files.createDirectories(dir);
            Path target = dir.resolve("backup_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date())
                    + "_before_import.pmaster");
            BackupUtil.export(all, key, service.getMasterSaltHex(), service.getMasterIterations(), target);
            return target;
        } catch (Exception ex) {
            int r = JOptionPane.showConfirmDialog(this,
                    "覆盖前自动备份失败：" + ex.getMessage()
                            + "\n\n若继续覆盖导入，原数据可能无法恢复。是否仍要继续？",
                    "备份失败", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                return IMPORT_CANCELLED;
            }
            return null;
        }
    }

    private String keyOf(Entry e) {
        return nullToEmpty(e.getPlatform()) + "|" + nullToEmpty(e.getAccount());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
