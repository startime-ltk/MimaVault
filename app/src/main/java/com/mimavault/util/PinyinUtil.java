package com.mimavault.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

/**
 * 拼音/分组字母工具（离线，基于 pinyin4j 本地 jar）
 * 用途：条目列表按展示名（platform）首字符分组排序：
 *  - 英文 / 数字符号之外的名称取名称的首字母（A-Z）；
 *  - 中文按拼音首字母分组（多音字取 pinyin4j 默认读音，简拼边界可接受）；
 *  - 无法归入 A-Z 的数字 / 符号 / 空名称统一归入 "#" 组。
 * 排序 key 为小写拼音全拼，保证组内顺序也接近拼音字典序。
 */
public final class PinyinUtil {

    /** 分组标签：A-Z + #（# 恒为最后一组） */
    public static final String HASH = "#";
    public static final int GROUP_COUNT = 27;

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinUtil() {
    }

    /** A=0..Z=25，#=26（供排序使用） */
    public static int rankOf(String letter) {
        if (letter == null || letter.isEmpty()) {
            return GROUP_COUNT - 1;
        }
        char c = letter.charAt(0);
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a';
        }
        return GROUP_COUNT - 1;
    }

    /**
     * 计算条目分组字母：A-Z 或 #。
     *
     * @param name 展示名（platform），允许为 null / 空
     */
    public static String groupOf(String name) {
        String text = name == null ? "" : name.trim();
        if (text.isEmpty()) {
            return HASH;
        }
        char first = text.charAt(0);
        if (first >= 'A' && first <= 'Z') {
            return String.valueOf(first);
        }
        if (first >= 'a' && first <= 'z') {
            return String.valueOf((char) (first - 'a' + 'A'));
        }
        if (isCjk(first)) {
            String py = pinyinOf(first);
            if (py != null && !py.isEmpty()) {
                char c = py.charAt(0);
                if (c >= 'a' && c <= 'z') {
                    return String.valueOf((char) (c - 'a' + 'A'));
                }
            }
        }
        return HASH;
    }

    /**
     * 计算展示名的拼音排序 key（小写，无音调）。
     * 中文转全拼，英文/数字原样小写保留，其余符号按原字符追加。
     */
    public static String sortKey(String name) {
        String text = name == null ? "" : name.trim();
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c - 'A' + 'a'));
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (isCjk(c)) {
                String py = pinyinOf(c);
                sb.append(py == null ? c : py);
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static String pinyinOf(char c) {
        try {
            String[] arr = PinyinHelper.toHanyuPinyinStringArray(c);
            if (arr != null && arr.length > 0 && arr[0] != null) {
                return arr[0];
            }
        } catch (Exception ignored) {
            // 个别生僻字抛异常时按无法归组处理
        }
        return null;
    }
}
