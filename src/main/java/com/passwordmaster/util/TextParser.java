package com.passwordmaster.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR 文本解析：从识别出的文字中提取 平台 / 账号 / 密码 / 手机 / 邮箱
 * 采用正则 + 关键词行匹配策略
 */
public final class TextParser {

    /** 邮箱正则（取第一个） */
    public static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.]+\\b");
    /** 手机号正则（大陆 1[3-9] 开头 11 位，取第一个） */
    public static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");

    public static final String[] PASSWORD_KEYS = {"密码", "password", "pwd", "口令"};
    public static final String[] ACCOUNT_KEYS = {"账号", "帐号", "账户", "用户名", "学号", "user name", "username", "account"};

    /** 用于单行多标签场景的值截断：遇到下一个标签关键词即停止 */
    static final String[] LABEL_KEYS = {
            "平台", "网站", "类型", "账号", "帐号", "账户", "用户名", "学号", "user name", "username", "account",
            "密码", "password", "pwd", "口令", "手机", "电话", "邮箱", "email"
    };

    /** 平台关键词：[识别词, 规范化平台名] */
    public static final String[][] PLATFORM_KEYS = {
            {"哔哩哔哩", "哔哩哔哩"}, {"bilibili", "哔哩哔哩"}, {"B站", "哔哩哔哩"},
            {"拼多多", "拼多多"}, {"支付宝", "支付宝"},
            {"微信", "微信"}, {"QQ", "QQ"}, {"淘宝", "淘宝"}, {"京东", "京东"},
            {"抖音", "抖音"}, {"微博", "微博"}, {"网易", "网易"}, {"邮箱", "邮箱"},
            {"百度", "百度"}, {"腾讯", "腾讯"}, {"知乎", "知乎"}, {"美团", "美团"},
            {"工商银行", "工商银行"}, {"建设银行", "建设银行"}, {"招商银行", "招商银行"},
            {"工行", "工商银行"}, {"建行", "建设银行"}, {"招行", "招商银行"}, {"银行", "银行"},
            {"校园网", "校园网"}
    };

    private TextParser() {
    }

    /**
     * 解析 OCR 文本，返回 Map（键：platform / account / password / phone / email）
     * 只包含识别到的字段
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        String[] lines = text.split("\\r?\\n");

        // 平台：全文关键词命中
        String platform = findPlatform(text);
        if (!platform.isEmpty()) {
            result.put("platform", platform);
        }

        // 邮箱
        Matcher em = EMAIL.matcher(text);
        if (em.find()) {
            result.put("email", em.group());
        }

        // 手机号
        Matcher ph = PHONE.matcher(text);
        if (ph.find()) {
            result.put("phone", ph.group());
        }

        // 密码：关键词行匹配
        String password = findValueByKeys(lines, PASSWORD_KEYS, null);
        if (password != null && !password.isEmpty()) {
            result.put("password", password);
        }

        // 账号：关键词行匹配（排除与密码相同的值）
        String account = findValueByKeys(lines, ACCOUNT_KEYS, password);
        if (account != null && !account.isEmpty()) {
            result.put("account", account);
        } else if (result.containsKey("email")) {
            // 兜底：用邮箱前缀
            String pre = result.get("email").split("@")[0];
            if (!pre.isEmpty()) {
                result.put("account", pre);
            }
        } else if (result.containsKey("phone")) {
            // 兜底：用手机号
            result.put("account", result.get("phone"));
        }

        return result;
    }

    /** 按关键词行提取冒号/等号/空格后的值 */
    public static String findValueByKeys(String[] lines, String[] keys, String exclude) {
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                if (isKeyLabel(l, key)) {
                    String val = extractAfterKey(l, key);
                    if (isValidValue(val) && (exclude == null || !val.equals(exclude))) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    /** 判断 line 中是否存在 key 作为"真标签"：前缀非拉丁字母，且后跟分隔符/紧贴值/结尾 */
    public static boolean isKeyLabel(String line, String key) {
        int idx = 0;
        while ((idx = line.indexOf(key, idx)) >= 0) {
            if (isTightLabelAt(line, key, idx)) {
                return true;
            }
            idx += key.length();
        }
        return false;
    }

    private static boolean isLabelSeparator(char c) {
        return c == ':' || c == '：' || c == '=' || c == '＝' || Character.isWhitespace(c);
    }

    /**
     * 紧贴容错标签判定：关键词前缀非拉丁字母（行首 / 中文 / 数字 / 标点均可），
     * 后跟分隔符、任意非空白值字符或行尾时视为标签。
     * 例如 "账号202501701023" "密码@Alltk061122" "密码是abc123" 均能命中；
     * 而 "mypassword" 中 password 前缀为拉丁字母，不会被误判。
     */
    public static boolean isTightLabelAt(String text, String key, int idx) {
        boolean beforeOk = idx == 0 || !isLatinLetter(text.charAt(idx - 1));
        if (!beforeOk) {
            return false;
        }
        int end = idx + key.length();
        if (end >= text.length()) {
            return true;
        }
        char c = text.charAt(end);
        return isLabelSeparator(c) || !Character.isWhitespace(c);
    }

    private static boolean isLatinLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /** 提取关键词后面的内容（截断到下一个标签关键词前） */
    private static String extractAfterKey(String line, String key) {
        int idx = -1;
        int i = 0;
        while ((i = line.indexOf(key, i)) >= 0) {
            if (isTightLabelAt(line, key, i)) {
                idx = i;
                break;
            }
            i += key.length();
        }
        if (idx < 0) {
            return "";
        }
        String rest = line.substring(idx + key.length());
        // 单行多标签：截断到下一个标签关键词位置（避免把后续标签吞进值里）
        int next = findNextLabel(rest);
        if (next >= 0) {
            rest = rest.substring(0, next);
        }
        // 去掉分隔符：冒号、等号、横线、空格，以及"是/为/叫"等口语虚词
        rest = rest.replaceAll("^[\\s:：=＝\\-—]+", "");
        rest = rest.replaceAll("^(是|为|叫)", "");
        rest = rest.trim();
        // 去掉行尾干扰字符
        rest = rest.replaceAll("[，,。.;；|\\s]+$", "");
        return rest;
    }

    /** 查找 rest 中下一个"真标签"位置（前缀非拉丁字母，且后跟分隔符/紧贴值/结尾） */
    private static int findNextLabel(String s) {
        int best = -1;
        for (String k : LABEL_KEYS) {
            int i = 0;
            while ((i = s.indexOf(k, i)) >= 0) {
                if (isTightLabelAt(s, k, i)) {
                    if (best < 0 || i < best) {
                        best = i;
                    }
                    break;
                }
                i += k.length();
            }
        }
        return best;
    }

    /** 值需包含至少一个数字或字母（排除"请输入"等纯提示文字），且不能是关键词本身 */
    public static boolean isValidValue(String val) {
        if (val == null || val.isEmpty()) {
            return false;
        }
        if (!val.matches(".*[0-9A-Za-z].*")) {
            return false;
        }
        for (String[] pair : PLATFORM_KEYS) {
            if (val.equalsIgnoreCase(pair[0]) || val.contains(pair[0])) {
                return false;
            }
        }
        return true;
    }

    public static String findPlatform(String text) {
        for (String[] pair : PLATFORM_KEYS) {
            if (text.contains(pair[0])) {
                return pair[1];
            }
        }
        return "";
    }
}
