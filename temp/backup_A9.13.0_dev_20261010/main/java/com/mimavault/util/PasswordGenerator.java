package com.mimavault.util;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 随机密码 / 口令短语 / 邮箱地址生成器（纯本地实现，无联网、无第三方依赖）
 * <p>
 * - 随机密码：可设长度、字符类型开关、是否排除易混淆字符（0 O 1 l I）、自定义字符集
 * - 口令短语(passphrase)：从内置本地词表取词，支持分隔符、首字母大写、末尾附加数字
 * - 邮箱：生成形似真实邮箱的字符串（如 zhangwei_8291@163.com），仅用于填表占位
 */
public final class PasswordGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 易混淆字符：数字 0、大写 O、数字 1、小写 l、大写 I */
    public static final String AMBIGUOUS = "0O1lI";

    /** 大写字母（排除易混 I/O） */
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    /** 小写字母（排除易混 l） */
    private static final char[] LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    /** 数字（排除易混 0/1） */
    private static final char[] DIGITS = "23456789".toCharArray();
    /** 常用符号 */
    private static final char[] SYMBOLS = "!@#$%^&*-_=+?".toCharArray();

    /** 大写字母全集（含易混 I/O） */
    private static final char[] UPPER_FULL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    /** 小写字母全集（含易混 l） */
    private static final char[] LOWER_FULL = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    /** 数字全集（含易混 0/1） */
    private static final char[] DIGITS_FULL = "0123456789".toCharArray();

    private static final char[] FALLBACK = (new String(UPPER_FULL) + new String(LOWER_FULL)
            + new String(DIGITS_FULL) + new String(SYMBOLS)).toCharArray();

    private static final String[] NAMES = {
            "alice", "bob", "carol", "david", "emma", "frank", "grace", "henry",
            "iris", "jack", "kate", "leo", "mary", "nick", "olivia", "peter",
            "rose", "sam", "tina", "victor", "wendy", "yuki", "zoe",
            "chen", "li", "wang", "zhang", "liu", "zhao", "sun", "zhou", "wu", "hu", "zhu", "gao", "lin"
    };

    private static final String[] SEPARATORS = {"_", ".", "-", ""};

    private static final String[] DOMAINS = {
            "gmail.com", "qq.com", "163.com", "126.com", "outlook.com",
            "foxmail.com", "protonmail.com", "hotmail.com", "example.com"
    };

    /** 随机密码生成选项 */
    public static class Options {
        /** 密码长度，最小 4，默认 16 */
        public int length = 16;
        /** 是否排除易混淆字符（0 O 1 l I），默认排除 */
        public boolean excludeAmbiguous = true;
        /** 是否使用大写字母 */
        public boolean useUpper = true;
        /** 是否使用小写字母 */
        public boolean useLower = true;
        /** 是否使用数字 */
        public boolean useDigits = true;
        /** 是否使用符号 */
        public boolean useSymbols = true;
        /** 自定义字符集（非空时并入候选字符池） */
        public String customCharset = "";
        /** true = 仅使用自定义字符集（忽略上面四类开关） */
        public boolean customOnly = false;
    }

    /** 口令短语生成选项 */
    public static class PassphraseOptions {
        /** 单词个数，2~10，默认 4 */
        public int words = 4;
        /** 单词间分隔符，默认 "-" */
        public String separator = "-";
        /** 每个单词首字母是否大写 */
        public boolean capitalize = false;
        /** 末尾是否附加两位随机数字 */
        public boolean appendNumber = false;
        /** 自定义词表（逗号/空格/换行分隔），非空且有效时优先于内置词表 */
        public String customWords = "";
    }

    private PasswordGenerator() {
    }

    /**
     * 生成随机密码（默认 16 位，四类字符均至少包含 1 个，排除易混淆字符）。
     */
    public static String generatePassword() {
        return generatePassword(16);
    }

    /**
     * 生成指定长度的随机密码（兼容旧调用）：四类字符各至少 1 个，排除易混淆字符。
     *
     * @param length 密码长度，至少 8；小于 8 时按 8 处理
     */
    public static String generatePassword(int length) {
        Options o = new Options();
        o.length = Math.max(8, length);
        return generate(o);
    }

    /**
     * 按选项生成随机密码：每个选中的字符类别至少出现 1 次（长度允许时），最后整体洗牌。
     */
    public static String generate(Options options) {
        Options o = options == null ? new Options() : options;
        int len = Math.max(4, o.length);

        List<char[]> pools = new ArrayList<>();
        if (o.customOnly) {
            char[] custom = distinctChars(o.customCharset);
            if (custom.length > 0) {
                pools.add(custom);
            }
        } else {
            if (o.useUpper) {
                pools.add(o.excludeAmbiguous ? UPPER : UPPER_FULL);
            }
            if (o.useLower) {
                pools.add(o.excludeAmbiguous ? LOWER : LOWER_FULL);
            }
            if (o.useDigits) {
                pools.add(o.excludeAmbiguous ? DIGITS : DIGITS_FULL);
            }
            if (o.useSymbols) {
                pools.add(SYMBOLS);
            }
            char[] custom = distinctChars(o.customCharset);
            if (custom.length > 0) {
                pools.add(custom);
            }
        }

        char[] all;
        if (pools.isEmpty()) {
            // 兜底：未选任何字符类型时，退回全字符集，避免生成空密码
            all = FALLBACK;
        } else {
            all = concat(pools.toArray(new char[0][]));
        }

        char[] pwd = new char[len];
        int idx = 0;
        for (char[] pool : pools) {
            if (idx >= len) {
                break;
            }
            pwd[idx++] = pool[RANDOM.nextInt(pool.length)];
        }
        for (int i = idx; i < len; i++) {
            pwd[i] = all[RANDOM.nextInt(all.length)];
        }
        shuffle(pwd);
        return new String(pwd);
    }

    /**
     * 生成口令短语，如 "harbor-lantern-quartz-otter"。
     * 词表来自内置本地词表（PassphraseWordList），或用户自定义词表。
     */
    public static String generatePassphrase(PassphraseOptions options) {
        PassphraseOptions o = options == null ? new PassphraseOptions() : options;
        int count = Math.max(2, Math.min(10, o.words));

        String[] pool = resolveWords(o.customWords);
        String sep = (o.separator == null || o.separator.isEmpty()) ? "-" : o.separator;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(sep);
            }
            String word = pool[RANDOM.nextInt(pool.length)];
            if (o.capitalize && !word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            } else {
                sb.append(word);
            }
        }
        if (o.appendNumber) {
            sb.append(sep).append(10 + RANDOM.nextInt(90));
        }
        return sb.toString();
    }

    /** 解析自定义词表；无效（少于 2 个词）时回退内置词表 */
    private static String[] resolveWords(String customWords) {
        if (customWords != null && !customWords.trim().isEmpty()) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String w : customWords.split("[\\s,;，、]+")) {
                String t = w.trim();
                if (!t.isEmpty()) {
                    set.add(t);
                }
            }
            if (set.size() >= 2) {
                return set.toArray(new String[0]);
            }
        }
        return PassphraseWordList.words();
    }

    /**
     * 生成一个形似真实邮箱的字符串，如 zhangwei_8291@163.com。
     * 仅用于填写表单占位，不代表真实可用的邮箱。
     */
    public static String generateEmail() {
        String name = NAMES[RANDOM.nextInt(NAMES.length)];
        String sep = SEPARATORS[RANDOM.nextInt(SEPARATORS.length)];
        int num = 100 + RANDOM.nextInt(900);
        String domain = DOMAINS[RANDOM.nextInt(DOMAINS.length)];
        return name + sep + num + "@" + domain;
    }

    /** 去重后的字符数组（保持输入顺序） */
    private static char[] distinctChars(String src) {
        if (src == null || src.isEmpty()) {
            return new char[0];
        }
        LinkedHashSet<Character> set = new LinkedHashSet<>();
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (!Character.isWhitespace(c)) {
                set.add(c);
            }
        }
        char[] arr = new char[set.size()];
        int i = 0;
        for (Character c : set) {
            arr[i++] = c;
        }
        return arr;
    }

    private static void shuffle(char[] arr) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }

    private static char[] concat(char[]... arrays) {
        int len = 0;
        for (char[] a : arrays) {
            len += a.length;
        }
        char[] result = new char[len];
        int pos = 0;
        for (char[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }
}
