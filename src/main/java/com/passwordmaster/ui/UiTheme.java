package com.passwordmaster.ui;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.InputStream;

/**
 * 全局 UI 主题（仅表现层）
 * - 鲜艳蓝紫渐变主色 #5B7FFF -> #9B59B6，暖橙色点缀
 * - 统一配置 FlatLaf 全局键：圆角、配色、表格、字体
 * - 启动时由 Main 调用 install()
 */
public final class UiTheme {

    private UiTheme() {
    }

    // ---------- 全局配色 ----------
    /** 主色（蓝） */
    public static final Color PRIMARY = new Color(0x5B7FFF);
    /** 主色深 */
    public static final Color PRIMARY_DARK = new Color(0x4467E8);
    /** 渐变副色（紫） */
    public static final Color PURPLE = new Color(0x9B59B6);
    /** 点缀色（暖橙） */
    public static final Color ACCENT = new Color(0xFF9F43);
    /** 点缀色（珊瑚红） */
    public static final Color CORAL = new Color(0xFF6B6B);
    /** 危险色 */
    public static final Color DANGER = new Color(0xE74C3C);

    /** 窗口背景渐变顶部（浅蓝） */
    public static final Color BG_TOP = new Color(0xEEF1FB);
    /** 窗口背景渐变底部（浅紫） */
    public static final Color BG_BOTTOM = new Color(0xF7EFFB);
    /** 卡片白 */
    public static final Color CARD_BG = Color.WHITE;
    /** 卡片边框 */
    public static final Color CARD_BORDER = new Color(0xE3E8F8);

    /** 主文字 */
    public static final Color TEXT_MAIN = new Color(0x2D3A5E);
    /** 次要文字 */
    public static final Color TEXT_SUB = new Color(0x7A86A8);
    /** 输入框背景 */
    public static final Color INPUT_BG = new Color(0xF6F8FF);
    /** 表格隔行色 */
    public static final Color TABLE_ALT = new Color(0xF4F6FE);
    /** 表格选中高亮 */
    public static final Color TABLE_SELECT = new Color(0x5B7FFF);

    /** 应用主题：先注册默认字体，再安装 FlatLaf，再配置全局键 */
    public static void install() {
        UIManager.put("defaultFont", new Font("Microsoft YaHei", Font.PLAIN, 13));

        FlatLightLaf.setup();

        // 全局圆角
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextField.arc", 14);
        UIManager.put("PasswordField.arc", 14);
        UIManager.put("TextArea.arc", 14);
        UIManager.put("ComboBox.arc", 14);
        UIManager.put("Spinner.arc", 14);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("TabbedPane.selectedBackground", CARD_BG);
        UIManager.put("CheckBox.arc", 6);

        // 基础配色
        UIManager.put("Panel.background", BG_TOP);
        UIManager.put("Label.foreground", TEXT_MAIN);
        UIManager.put("Component.focusColor", new Color(0x5B7FFF));
        UIManager.put("Component.borderColor", new Color(0xC9D4F5));
        UIManager.put("Component.focusedBorderColor", PRIMARY);

        // 输入控件
        UIManager.put("TextField.background", INPUT_BG);
        UIManager.put("TextField.foreground", TEXT_MAIN);
        UIManager.put("TextField.caretForeground", PRIMARY_DARK);
        UIManager.put("TextField.selectionBackground", new Color(0xC4D0FF));
        UIManager.put("PasswordField.background", INPUT_BG);
        UIManager.put("PasswordField.foreground", TEXT_MAIN);
        UIManager.put("PasswordField.caretForeground", PRIMARY_DARK);
        UIManager.put("TextArea.background", INPUT_BG);
        UIManager.put("TextArea.foreground", TEXT_MAIN);
        UIManager.put("TextArea.caretForeground", PRIMARY_DARK);
        UIManager.put("ComboBox.background", INPUT_BG);
        UIManager.put("ComboBox.foreground", TEXT_MAIN);
        UIManager.put("ComboBox.selectionBackground", new Color(0xE4E9FF));
        UIManager.put("ComboBox.selectionForeground", TEXT_MAIN);

        // 表格
        UIManager.put("Table.background", CARD_BG);
        UIManager.put("Table.foreground", TEXT_MAIN);
        UIManager.put("Table.alternateRowBackground", TABLE_ALT);
        UIManager.put("Table.selectionBackground", TABLE_SELECT);
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("Table.gridColor", new Color(0xE9EDF8));
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("TableHeader.background", PRIMARY);
        UIManager.put("TableHeader.foreground", Color.WHITE);
        UIManager.put("TableHeader.separatorColor", new Color(0x4A6BEE));

        // 滚动条
        UIManager.put("ScrollBar.background", new Color(0xEEF1FB));
        UIManager.put("ScrollBar.thumb", new Color(0x9FB0E8));
        UIManager.put("ScrollBar.thumbHover", new Color(0x8AA0E0));
        UIManager.put("ScrollBar.thumbPressed", PRIMARY);
        UIManager.put("ScrollBar.track", new Color(0xEEF1FB));

        // 弹窗与提示
        UIManager.put("OptionPane.background", CARD_BG);
        UIManager.put("OptionPane.messageForeground", TEXT_MAIN);
        UIManager.put("OptionPane.buttonBackground", new Color(0xE4E9FF));
        UIManager.put("OptionPane.buttonForeground", TEXT_MAIN);
        UIManager.put("OptionPane.buttonHoverBackground", new Color(0xD4DCFF));
        UIManager.put("OptionPane.buttonPressedBackground", new Color(0xC4D0FF));

        // 对话框
        UIManager.put("Dialog.background", BG_TOP);
        UIManager.put("InternalFrame.activeTitleBackground", PRIMARY);

        FlatLaf.updateUI();
    }

    /** 应用品牌图标：从 jar 内 /logo.png 读取；资源缺失或读取失败时返回 null */
    public static Image getAppIcon() {
        try (InputStream in = UiTheme.class.getResourceAsStream("/logo.png")) {
            if (in == null) {
                return null;
            }
            return ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }
}
