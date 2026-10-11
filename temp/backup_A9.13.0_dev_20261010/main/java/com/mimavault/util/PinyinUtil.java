package com.mimavault.util;

import com.github.promeg.pinyinhelper.Pinyin;

/**
 * 拼音/分组字母工具（离线，基于 TinyPinyin，Apache 2.0 许可）
 * 用途：条目列表按展示名（platform）首字符分组排序：
 *  - 英文 / 数字符号之外的名称取名称的首字母（A-Z）；
 *  - 中文按拼音首字母分组（多音字取 TinyPinyin 默认读音，简拼边界可接受）；
 *  - 无法归入 A-Z 的数字 / 符号 / 空名称统一归入 "#" 组。
 * 排序 key 为小写拼音全拼，保证组内顺序也接近拼音字典序。
 */
public final class PinyinUtil {

    /** 分组标签：A-Z + #（# 恒为最后一组） */
    public static final String HASH = "#";
    public static final int GROUP_COUNT = 27;

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
        if (Pinyin.isChinese(first)) {
            String py = Pinyin.toPinyin(first);
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
            } else if (Pinyin.isChinese(c)) {
                String py = Pinyin.toPinyin(c);
                sb.append(py == null ? c : py);
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
