package com.mimavault.ui;

import com.mimavault.config.AppConfig;
import com.mimavault.model.Entry;
import com.mimavault.service.PasswordHealthChecker;
import com.mimavault.service.PasswordService;
import com.mimavault.util.AesUtil;
import com.mimavault.util.BackupUtil;
import com.mimavault.util.ClipboardSafe;
import com.mimavault.util.CsvUtil;
import com.mimavault.util.DragDropUtil;
import com.mimavault.util.ImageUtil;
import com.mimavault.util.OcrUtil;
import com.mimavault.util.PasswordGenerator;
import com.mimavault.util.PasswordStrengthUtil;
import com.mimavault.util.TextBatchParser;
import com.mimavault.util.TotpUtil;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

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
    private SecretKey key;

    private DefaultTableModel tableModel;
    private JTable table;
    private final JTextField searchField = new JTextField(18);
    private final JComboBox<String> categoryFilter = new JComboBox<>(new String[]{"全部", "网站", "应用", "其他"});
    private final JPanel centerBox = new JPanel(new BorderLayout(0, 8));
    private final JLabel statusLabel = new JLabel("支持将图片拖入窗口，自动识别并导入");

    private List<Entry> currentList;

    // ---------- 系统托盘 ----------
    private java.awt.SystemTray systemTray;
    private TrayIcon trayIcon;

    // ---------- 全局快捷键 ----------
    /** 显示/隐藏主界面：Ctrl+Shift+M */
    private static final int HOTKEY_TOGGLE = NativeKeyEvent.VC_M;
    /** 锁定（回登录界面）：Ctrl+Shift+L */
    private static final int HOTKEY_LOCK = NativeKeyEvent.VC_L;
    private NativeKeyListener hotkeyListener;
    private boolean hotkeyOk = false;

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

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                handleCloseRequest();
            }
        });

        buildHeader();
        centerBox.setOpaque(false);
        centerBox.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        add(centerBox, BorderLayout.CENTER);
        buildToolbar();
        buildTable();
        buildStatusBar();
        installDropSupport();

        refreshTable(null, null);

        setSize(740, 520);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(660, 420));
        setIconImage(UiTheme.getAppIcon());

        // 主界面显示即视为已登录：启动空闲自动登出监控
        startIdleMonitor();

        // 登录成功后自动备份全部数据到 data/backup/
        startAutoBackup();

        // 系统托盘与全局快捷键（注册失败不阻塞主流程）
        installSystemTray();
        installGlobalHotkey();
    }

    // ---------- 系统托盘 ----------

    /** 将程序最小化到系统托盘：关闭窗口不再退出，双击/托盘菜单可恢复 */
    private void installSystemTray() {
        if (!java.awt.SystemTray.isSupported()) {
            return;
        }
        try {
            systemTray = java.awt.SystemTray.getSystemTray();
            java.awt.PopupMenu menu = new java.awt.PopupMenu();
            // Windows 原生托盘菜单对中文渲染为空白方框（AWT 已知 bug），
            // 中文字体设置无效，菜单文字使用英文保证可读
            java.awt.MenuItem openItem = new java.awt.MenuItem("Open");
            openItem.addActionListener(e -> showMainWindow());
            java.awt.MenuItem lockItem = new java.awt.MenuItem("Lock");
            lockItem.addActionListener(e -> autoLogout());
            java.awt.MenuItem closeBehaviorItem = new java.awt.MenuItem("Close Action...");
            closeBehaviorItem.addActionListener(e -> showCloseChoiceDialog());
            java.awt.MenuItem dataDirItem = new java.awt.MenuItem("Migrate Data...");
            dataDirItem.addActionListener(e -> openDataMigrationDialog());
            java.awt.MenuItem exitItem = new java.awt.MenuItem("Exit");
            exitItem.addActionListener(e -> quitApp());
            menu.add(openItem);
            menu.add(lockItem);
            menu.add(closeBehaviorItem);
            menu.add(dataDirItem);
            menu.addSeparator();
            menu.add(exitItem);

            trayIcon = new TrayIcon(UiTheme.getAppIcon(), "密匣 MimaVault", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> showMainWindow());
            systemTray.add(trayIcon);
        } catch (Exception ex) {
            trayIcon = null;
            systemTray = null;
        }
    }

    /**
     * 点击关闭按钮：按用户记忆的选择执行（关闭程序 / 最小化到托盘）。
     * 未设置过则弹出选择对话框，选择后永久生效。
     */
    private void handleCloseRequest() {
        AppConfig cfg = AppConfig.load();
        if ("exit".equals(cfg.closeAction)) {
            quitApp();
        } else if ("minimize".equals(cfg.closeAction)) {
            hideToTray();
        } else {
            showCloseChoiceDialog();
        }
    }

    /** 弹出关闭行为选择：关闭程序 / 最小化到托盘 / 取消，选择后写入配置永久生效 */
    private void showCloseChoiceDialog() {
        Object[] options = {"关闭程序", "最小化到托盘", "取消"};
        int c = JOptionPane.showOptionDialog(this,
                "点击关闭按钮时希望执行什么操作？\n选择后将永久生效，可随时在托盘菜单「关闭窗口行为」中修改。",
                "关闭窗口行为", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (c == 0) {
            AppConfig cfg = AppConfig.load();
            cfg.closeAction = "exit";
            cfg.save();
            quitApp();
        } else if (c == 1) {
            AppConfig cfg = AppConfig.load();
            cfg.closeAction = "minimize";
            cfg.save();
            hideToTray();
        }
    }

    /** 弹出数据迁移对话框：把全部数据迁移/转移到电脑的另一个磁盘或文件夹（v8.30.1） */
    private void openDataMigrationDialog() {
        new DataDirDialog(this).setVisible(true);
    }

    /** 关闭窗口：最小化到托盘（静默，不弹系统通知） */
    private void hideToTray() {
        if (trayIcon == null) {
            quitApp();
            return;
        }
        setVisible(false);
    }

    /** 从托盘恢复主界面 */
    private void showMainWindow() {
        setVisible(true);
        setState(Frame.NORMAL);
        toFront();
        requestFocus();
    }

    /** 真正退出程序：先卸载托盘与热键，再结束进程 */
    private void quitApp() {
        uninstallSystemTray();
        uninstallGlobalHotkey();
        stopIdleMonitor();
        dispose();
        System.exit(0);
    }

    /** 卸载托盘图标（登出/退出时调用，避免重复注册） */
    private void uninstallSystemTray() {
        if (trayIcon != null && systemTray != null) {
            try {
                systemTray.remove(trayIcon);
            } catch (Exception ignored) {
            }
        }
        trayIcon = null;
        systemTray = null;
    }

    // ---------- 全局快捷键 ----------

    /** 注册全局快捷键：Ctrl+Shift+M 显示/隐藏主界面，Ctrl+Shift+L 锁定 */
    private void installGlobalHotkey() {
        // 静音 jnativehook 自带日志，避免控制台刷屏
        try {
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(java.util.logging.Level.OFF);
            logger.setUseParentHandlers(false);
        } catch (Exception ignored) {
        }
        try {
            GlobalScreen.registerNativeHook();
        } catch (Exception ex) {
            hotkeyOk = false;
            return;
        }
        hotkeyListener = new NativeKeyListener() {
            @Override
            public void nativeKeyPressed(NativeKeyEvent e) {
                boolean ctrl = (e.getModifiers() & NativeKeyEvent.CTRL_MASK) != 0;
                boolean shift = (e.getModifiers() & NativeKeyEvent.SHIFT_MASK) != 0;
                if (ctrl && shift && e.getKeyCode() == HOTKEY_TOGGLE) {
                    SwingUtilities.invokeLater(() -> toggleMainWindow());
                } else if (ctrl && shift && e.getKeyCode() == HOTKEY_LOCK) {
                    SwingUtilities.invokeLater(() -> autoLogout());
                }
            }

            @Override
            public void nativeKeyReleased(NativeKeyEvent e) {
            }

            @Override
            public void nativeKeyTyped(NativeKeyEvent e) {
            }
        };
        GlobalScreen.addNativeKeyListener(hotkeyListener);
        hotkeyOk = true;
    }

    /** 卸载全局快捷键监听（登出/退出时调用，避免重复注册触发两次） */
    private void uninstallGlobalHotkey() {
        if (hotkeyListener != null) {
            try {
                GlobalScreen.removeNativeKeyListener(hotkeyListener);
            } catch (Exception ignored) {
            }
            hotkeyListener = null;
        }
        if (hotkeyOk) {
            try {
                GlobalScreen.unregisterNativeHook();
            } catch (Exception ignored) {
            }
            hotkeyOk = false;
        }
    }

    /** Ctrl+Shift+M：主界面可见且激活则隐藏到托盘，否则恢复 */
    private void toggleMainWindow() {
        if (isVisible() && isActive()) {
            setVisible(false);
        } else {
            showMainWindow();
        }
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
        uninstallSystemTray();
        uninstallGlobalHotkey();
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

    /** 顶部渐变标题栏：艺术字标题 + 副标题 + 安全报告入口 */
    private void buildHeader() {
        GradientPanel header = new GradientPanel(UiTheme.PRIMARY, UiTheme.PURPLE);
        header.setLayout(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));

        ArtTextLabel title = new ArtTextLabel("密匣 MimaVault", 24);
        title.setGradient(Color.WHITE, new Color(0xE8E4FF));
        title.setStroke(new Color(0x4A3A7A), 1.2f);
        title.setShadow(new Color(0, 0, 0, 70), 2);
        title.setPreferredSize(new Dimension(280, 38));

        // 安全报告入口：位于 logo 右侧
        GradientButton healthBtn = new GradientButton("安全报告",
                new Color(0x7ED321), new Color(0x4CAF50), Color.WHITE, new Color(0x5DAF1E));
        healthBtn.setPreferredSize(new Dimension(110, 32));
        healthBtn.addActionListener(e -> onSecurityReport());

        // 数据迁移入口：把全部数据迁移/转移到电脑的另一个磁盘或文件夹（v8.30.1）
        GradientButton dataDirBtn = new GradientButton("数据迁移",
                new Color(0x667EEA), new Color(0x764BA2), Color.WHITE, null);
        dataDirBtn.setPreferredSize(new Dimension(110, 32));
        dataDirBtn.setToolTipText("将密码库等全部数据迁移到其他磁盘/文件夹");
        dataDirBtn.addActionListener(e -> openDataMigrationDialog());

        JPanel leftBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftBox.setOpaque(false);
        leftBox.add(title);
        leftBox.add(healthBtn);
        leftBox.add(dataDirBtn);
        header.add(leftBox, BorderLayout.WEST);

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
        searchField.setPreferredSize(new Dimension(200, 30));
        row1.add(searchField);
        row1.add(new JLabel("类型："));
        categoryFilter.setPreferredSize(new Dimension(80, 30));
        row1.add(categoryFilter);

        // 生成器入口：打开密码生成器（随机密码 / 口令短语）与邮箱地址生成，结果复制到剪贴板并弹提示
        GradientButton genPwdBtn = createToolButton("生成密码", e -> {
            String pwd = PasswordGeneratorDialog.showDialog(MainFrame.this, "密码生成器");
            if (pwd == null || pwd.isEmpty()) {
                return;
            }
            ClipboardSafe.copySecret(pwd);
            Toast.show(MainFrame.this, "已生成密码并复制到剪贴板");
        });
        genPwdBtn.setToolTipText("打开密码生成器：随机密码（可排除易混淆字符、自定义字符集）/ 口令短语（本地词表）");
        GradientButton genEmailBtn = createToolButton("生成邮箱", e -> {
            String email = PasswordGenerator.generateEmail();
            ClipboardSafe.copy(email);
            Toast.show(MainFrame.this, "已生成邮箱并复制到剪贴板：" + email);
        });
        genEmailBtn.setToolTipText("生成一个形似真实邮箱的字符串并复制到剪贴板，用于填写表单");
        row1.add(genPwdBtn);
        row1.add(genEmailBtn);
        top.add(row1);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.setOpaque(false);
        row2.add(createToolButton("新增", e -> onAdd()));
        row2.add(createToolButton("智能导入", e -> onSmartImport()));
        row2.add(createToolButton("删除", e -> onDelete()));
        row2.add(createToolButton("批量操作", e -> onBatchOps()));
        row2.add(createToolButton("回收站", e -> onTrash()));
        row2.add(createToolButton("导出", e -> onExport()));
        row2.add(createToolButton("导入", e -> onImport()));
        // 修改主密码入口：验证当前密码 → 全库重加密 → 更新内存密钥
        row2.add(createToolButton("修改主密码", e -> onChangeMaster()));
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
        tableModel = new DefaultTableModel(new String[]{"选择", "类型", "平台", "账号", "手机", "邮箱", "备注", "强度"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0; // 仅"选择"列可编辑（复选框勾选）
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : Object.class; // 首列渲染为复选框
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
        table.getColumnModel().getColumn(0).setPreferredWidth(44);
        table.getColumnModel().getColumn(1).setPreferredWidth(50);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        table.getColumnModel().getColumn(4).setPreferredWidth(100);
        table.getColumnModel().getColumn(5).setPreferredWidth(130);
        table.getColumnModel().getColumn(6).setPreferredWidth(150);
        table.getColumnModel().getColumn(7).setPreferredWidth(60);

        // 强度列着色：弱=红 / 中=橙 / 强=绿 / 未设置=灰
        table.getColumnModel().getColumn(7).setCellRenderer(new DefaultTableCellRenderer() {
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
                    List<Path> imgPaths = new ArrayList<>();
                    for (File f : images) {
                        imgPaths.add(f.toPath());
                    }
                    new ImportPreviewDialog(MainFrame.this,
                            new SmartImportDialog.Result(records, raw, imgPaths), service, key).setVisible(true);
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

        tableModel.setRowCount(0);
        for (Entry e : currentList) {
            tableModel.addRow(new Object[]{
                    Boolean.FALSE,
                    nullToEmpty(e.getCategory()),
                    nullToEmpty(e.getPlatform()),
                    nullToEmpty(e.getAccount()),
                    nullToEmpty(e.getPhone()),
                    nullToEmpty(e.getEmail()),
                    nullToEmpty(e.getNote()),
                    strengthText(e)
            });
        }
    }

    /** 解密并计算条目密码强度；未设置密码显示「—」 */
    private String strengthText(Entry e) {
        String pwd = service.decryptPassword(e, key);
        if (pwd == null || pwd.isEmpty()) {
            return "—";
        }
        return PasswordStrengthUtil.evaluate(pwd).level.getText();
    }

    /**
     * 修改主密码：弹出对话框验证当前密码 → 全库重加密 → 用返回的新密钥替换内存密钥，
     * 刷新表格（强度列按新密钥重新解密计算）。
     */
    private void onChangeMaster() {
        ChangeMasterDialog dlg = new ChangeMasterDialog(this, service);
        dlg.setVisible(true);
        SecretKey newKey = dlg.getNewKey();
        if (newKey != null) {
            this.key = newKey;
            refreshTable(null, null);
            statusLabel.setText("主密码已修改，全部数据已用新主密码重新加密");
            JOptionPane.showMessageDialog(this,
                    "主密码修改成功，全部数据已重新加密。\n下次启动请使用新主密码登录。",
                    "修改完成", JOptionPane.INFORMATION_MESSAGE);
        }
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
     * 若条目不在当前列表（处于搜索/分类筛选状态），先恢复全部显示再定位，
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
        EntryEditDialog dlg = new EntryEditDialog(this, null, key);
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
                "确定将【" + nullToEmpty(selected.getPlatform()) + " / " + nullToEmpty(selected.getAccount()) + "】移入回收站？\n可在回收站中恢复或彻底删除。",
                "移入回收站", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            service.trash(selected.getId());
            refreshTable(null, null);
            statusLabel.setText("已移入回收站");
        }
    }

    /** 打开回收站：恢复 / 彻底删除 / 清空 */
    private void onTrash() {
        TrashDialog dlg = new TrashDialog(this, service);
        dlg.setVisible(true);
        refreshTable(null, null);
    }

    /** 批量操作：将勾选的条目移入回收站，或按格式批量导出 */
    private void onBatchOps() {
        List<Entry> selected = collectChecked();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先在表格左侧勾选要操作的记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object[] options = {"移入回收站", "导出备份 (.pmaster)", "导出 CSV"};
        int c = JOptionPane.showOptionDialog(this,
                "已勾选 " + selected.size() + " 条记录，请选择批量操作：",
                "批量操作", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (c == JOptionPane.CLOSED_OPTION) {
            return;
        }
        if (c == 0) {
            doBatchTrash(selected);
        } else if (c == 1) {
            exportPmaster(selected);
        } else {
            exportCsv(selected);
        }
    }

    /** 收集表格第 0 列（选择列）勾选的条目 */
    private List<Entry> collectChecked() {
        List<Entry> selected = new java.util.ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 0))) {
                selected.add(currentList.get(i));
            }
        }
        return selected;
    }

    /** 批量移入回收站 */
    private void doBatchTrash(List<Entry> selected) {
        int r = JOptionPane.showConfirmDialog(this,
                "确定将选中的 " + selected.size() + " 条记录移入回收站？\n可在回收站中恢复或彻底删除。",
                "批量移入回收站", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            for (Entry e : selected) {
                service.trash(e.getId());
            }
            refreshTable(null, null);
            statusLabel.setText("已移入回收站 " + selected.size() + " 条");
        }
    }

    private void onDetail() {
        Entry selected = selectedEntry();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "请先选中一条记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        EntryDetailDialog detail = new EntryDetailDialog(this, selected, service, key);
        detail.setVisible(true);
        if (detail.isChanged()) {
            // 详情中编辑保存或恢复了历史密码：重新载入列表（强度列按新密码重算）
            refreshTable(null, null);
        }
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

    /**
     * 导出：先选择导出范围（有勾选时可只导勾选项），再选择格式（密匣备份 .pmaster / 明文 CSV）
     */
    private void onExport() {
        List<Entry> checked = collectChecked();
        List<Entry> scope;
        if (checked.isEmpty()) {
            scope = service.listEntries();
        } else {
            Object[] scopeOptions = {"全部记录", "仅导出勾选的 " + checked.size() + " 条"};
            int sc = JOptionPane.showOptionDialog(this,
                    "表格中已勾选 " + checked.size() + " 条记录，请选择导出范围：",
                    "导出范围", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, scopeOptions, scopeOptions[1]);
            if (sc == JOptionPane.CLOSED_OPTION) {
                return;
            }
            scope = (sc == 0) ? service.listEntries() : checked;
        }
        if (scope.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前没有可导出的数据", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object[] options = {"密匣备份 (.pmaster)", "CSV 文件 (.csv)"};
        int choice = JOptionPane.showOptionDialog(this,
                "请选择导出格式：\n密匣备份：加密格式，导入时需主密码还原\nCSV：通用明文格式，兼容其它密码管理器",
                "导出", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == JOptionPane.CLOSED_OPTION) {
            return;
        }
        if (choice == 0) {
            exportPmaster(scope);
        } else {
            exportCsv(scope);
        }
    }

    /** 导出为密匣备份（.pmaster，加密格式），导出指定条目集合 */
    private void exportPmaster(List<Entry> entries) {
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
            if (entries.isEmpty()) {
                JOptionPane.showMessageDialog(this, "当前没有可导出的数据", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            BackupUtil.export(entries, key, service.getMasterSaltHex(), service.getMasterIterations(), target);
            JOptionPane.showMessageDialog(this, "导出成功，共 " + entries.size() + " 条记录\n" + target, "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导出失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 导入：文件选择对话框同时支持 .pmaster 与 .csv，按扩展名自动调用对应导入逻辑
     */
    private void onImport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("密匣备份 / CSV (*.pmaster; *.csv)", "pmaster", "csv"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("密匣备份 (*.pmaster)", "pmaster"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("CSV 文件 (*.csv)", "csv"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = chooser.getSelectedFile().toPath();
        if (source.toString().toLowerCase().endsWith(".csv")) {
            importCsv(source);
        } else {
            importPmaster(source);
        }
    }

    /** 导入密匣备份（.pmaster）：输入备份文件主密码解密后进入导入流程 */
    private void importPmaster(Path source) {
        // 输入备份文件自身的主密码（跨密码导入：可不等于当前库主密码）
        JLabel tip = new JLabel("<html>请输入<font color='#C62828'><b>备份文件</b></font>的主密码解密该备份：</html>");
        tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        JPasswordField pwdField = new JPasswordField(16);
        Object[] msg = {tip, pwdField};
        int r = JOptionPane.showConfirmDialog(this, msg, "备份文件主密码", JOptionPane.OK_CANCEL_OPTION);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        BackupUtil.BackupPackage pack;
        SecretKey backupKey;
        try {
            char[] importPwd = pwdField.getPassword();
            try {
                // 多通道解密：header PBKDF2（备份主密码）→ 当前库密钥 → 旧版 SHA-256
                // 返回 backupKey 供条目密码「备份密钥解密 → 当前库密钥重加密」
                BackupUtil.ImportResult result = BackupUtil.importBackupDetailed(source, importPwd, key);
                pack = result.pack;
                backupKey = result.backupKey;
            } finally {
                java.util.Arrays.fill(importPwd, '\0'); // 明文用完即清
            }
            if (pack.items == null || pack.items.isEmpty()) {
                JOptionPane.showMessageDialog(this, "备份文件中没有数据", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            doImport(pack, backupKey);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导入失败：主密码错误或文件损坏\n" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 导出为 CSV：默认脱敏（密码列打码、不解密），选完整明文导出需再次确认 */
    private void exportCsv(List<Entry> entries) {
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前没有可导出的数据", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // 第一步：选择导出方式（默认脱敏）
        JLabel tip = new JLabel("<html>CSV 是<font color='#C62828'><b>不加密的明文文件</b></font>，Excel 等程序可直接打开：<br>"
                + "· <font color='#2E7D32'><b>脱敏导出（推荐）</b></font>：密码列写 " + CsvUtil.MASKED_PASSWORD + "，文件不含任何明文密码；<br>"
                + "· <font color='#C62828'><b>导出完整明文</b></font>：密码列写入真实密码，泄露风险最高，需再次确认。</html>");
        tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        Object[] options = {"脱敏导出（推荐）", "导出完整明文", "取消"};
        int choice = JOptionPane.showOptionDialog(this, tip, "导出 CSV - 选择导出方式",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
            return;
        }
        boolean maskPassword = choice == 0;

        // 第二步：完整明文导出二次确认
        if (!maskPassword) {
            JLabel risk = new JLabel("<html><font color='#C62828'><b>高风险确认</b></font>：即将导出 <font color='#C62828'><b>"
                    + entries.size() + " 条</b></font>记录的<font color='#C62828'><b>完整明文密码</b></font>。<br>"
                    + "导出后文件可被任何程序读取，请勿通过聊天工具、邮件或网盘传输。<br>确定继续导出吗？</html>");
            risk.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            int r = JOptionPane.showConfirmDialog(this, risk, "再次确认：导出完整明文密码",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return;
            }
        }

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
        try {
            CsvUtil.export(entries, key, target, maskPassword);
            JOptionPane.showMessageDialog(this, "导出成功（"
                    + (maskPassword ? "密码列已脱敏" : "密码列为完整明文") + "），共 " + entries.size() + " 条记录\n" + target,
                    "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导出失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 从 CSV 导入（通用格式，明文密码加密后入库，复用现有加密链路） */
    private void importCsv(Path source) {
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
                // CSV 中的动态验证码列：规范化为 otpauth 链接后加密存库（格式不合法则跳过，不阻塞导入）
                if (row.totp != null && !row.totp.trim().isEmpty()) {
                    try {
                        e.setTotpSecretEnc(AesUtil.encrypt(TotpUtil.parse(row.totp).toUri(), key));
                    } catch (Exception ignore) {
                        // 无法识别的 2FA 字段：忽略
                    }
                }
                entries.add(e);
            }
            // 用户确认导入内容后才入库
            ImportConfirmDialog confirm = new ImportConfirmDialog(this, "CSV 导入确认", entries, key);
            confirm.setVisible(true);
            if (!confirm.isConfirmed()) {
                return;
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
        SmartImportDialog.Result result = SmartImportDialog.collect(this);
        if (result == null || result.records == null || result.records.isEmpty()) {
            return;
        }
        new ImportPreviewDialog(this, result, service, key).setVisible(true);
        refreshTable(null, null);
    }

    private void doImport(BackupUtil.BackupPackage pack, SecretKey backupKey) {
        // 跨密码导入：条目密码从「备份密钥」解密 → 「当前库密钥」重加密（解不开保留原密文并计数告警）
        int reencryptFailed = 0;
        if (backupKey != null) {
            for (BackupUtil.BackupItem item : pack.items) {
                if (item.password != null && item.password.startsWith("encrypted:")) {
                    String enc = item.password.substring("encrypted:".length());
                    try {
                        String plain = AesUtil.decrypt(enc, backupKey);
                        item.password = "encrypted:" + AesUtil.encrypt(plain, key);
                    } catch (Exception ex) {
                        reencryptFailed++; // 保留原密文，导入后该条密码可能无法查看
                    }
                }
                // TOTP 密钥同样做「备份密钥解密 → 当前库密钥重加密」，保证导入后动态验证码可正常生成
                if (item.totpSecret != null && item.totpSecret.startsWith("encrypted:")) {
                    String enc = item.totpSecret.substring("encrypted:".length());
                    try {
                        String plain = AesUtil.decrypt(enc, backupKey);
                        item.totpSecret = "encrypted:" + AesUtil.encrypt(plain, key);
                    } catch (Exception ex) {
                        reencryptFailed++;
                    }
                }
            }
        }

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

        // 用户确认导入内容后才继续（覆盖模式下先确认再清空，取消不产生任何副作用）
        java.util.List<Entry> previewEntries = new ArrayList<>();
        for (BackupUtil.BackupItem item : pack.items) {
            previewEntries.add(item.toEntry());
        }
        ImportConfirmDialog confirm = new ImportConfirmDialog(this, "备份导入确认", previewEntries, key);
        confirm.setVisible(true);
        if (!confirm.isConfirmed()) {
            return;
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
        String doneMsg = "导入完成：成功 " + restored + " 条" + (skipped > 0 ? "，重复跳过 " + skipped + " 条" : "");
        if (reencryptFailed > 0) {
            doneMsg += "\n\n注意：" + reencryptFailed + " 条密码无法用备份密钥解开，已保留原密文，可能无法查看";
            JOptionPane.showMessageDialog(this, doneMsg, "导入完成（部分密码未迁移）", JOptionPane.WARNING_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, doneMsg, "导入完成", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** 覆盖导入取消哨兵 */
    private static final Path IMPORT_CANCELLED = Paths.get("__CANCELLED__");

    /**
     * 登录成功后自动备份：后台线程将全部条目导出为 data/backup/backup_时间戳_auto.pmaster。
     * 空库跳过；失败不打断使用，仅状态栏提示。
     */
    private void startAutoBackup() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                autoBackup();
                return null;
            }

            @Override
            protected void done() {
                // 结果已在 doInBackground 内通过状态栏反馈
            }
        }.execute();
    }

    private void autoBackup() {
        try {
            List<Entry> all = service.listEntries();
            if (all.isEmpty()) {
                return;
            }
            Path dir = AppConfig.DATA_DIR.resolve("backup");
            Files.createDirectories(dir);
            Path target = dir.resolve("backup_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date())
                    + "_auto.pmaster");
            BackupUtil.export(all, key, service.getMasterSaltHex(), service.getMasterIterations(), target);
            SwingUtilities.invokeLater(() -> statusLabel.setText("自动备份完成：" + target.getFileName()));
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> statusLabel.setText("自动备份失败：" + ex.getMessage()));
        }
    }

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
