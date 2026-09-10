package com.mimavault.util;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 随机密码 / 口令短语 / 邮箱地址生成器
 * - 随机密码：可选字符类别（大写/小写/数字/符号）、可排除易混淆字符（0/O/1/l/I）、
 *             可使用自定义字符集（完全本地，无联网、无第三方依赖）
 * - 口令短语：内置本地词表（PassphraseWordList），可选单词数/分隔符/首字母大写/末尾数字/自定义词表
 * - 邮箱：生成形似真实邮箱的字符串（如 zhangwei_8291@163.com），仅用于填表占位
 * 参数与安卓端 PasswordGenerator 保持一致。
 */
public final class PasswordGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 易混淆字符：数字 0、字母 O、数字 1、小写 l、大写 I */
    public static final String AMBIGUOUS = "0O1lI";

    private static final char[] UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWER = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGITS = "0123456789".toCharArray();
    private static final char[] SYMBOLS = "!@#$%^&*-_=+?".toCharArray();

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

    private PasswordGenerator() {
    }

    // ==================== 随机密码参数 ====================

    /** 随机密码生成参数 */
    public static class Options {
        /** 密码长度（默认 16） */
        public int length = 16;
        /** 是否包含大写字母 */
        public boolean upper = true;
        /** 是否包含小写字母 */
        public boolean lower = true;
        /** 是否包含数字 */
        public boolean digits = true;
        /** 是否包含符号 */
        public boolean symbols = true;
        /** 是否排除易混淆字符（0/O/1/l/I），默认开启 */
        public boolean excludeAmbiguous = true;
        /** 自定义字符集（可空）；非空时这些字符一定会出现在候选池中 */
        public String customCharset = "";

        public Options() {
        }

        public Options(int length, boolean upper, boolean lower, boolean digits, boolean symbols,
                       boolean excludeAmbiguous, String customCharset) {
            this.length = length;
            this.upper = upper;
            this.lower = lower;
            this.digits = digits;
            this.symbols = symbols;
            this.excludeAmbiguous = excludeAmbiguous;
            this.customCharset = customCharset;
        }
    }

    /** 口令短语生成参数 */
    public static class PassphraseOptions {
        /** 单词个数（默认 4） */
        public int words = 4;
        /** 分隔符（默认 "-"） */
        public String separator = "-";
        /** 每个单词首字母大写 */
        public boolean capitalize = false;
        /** 末尾追加两位数字 */
        public boolean appendNumber = false;
        /** 自定义词表（英文逗号/空格/换行分隔，可空；非空时替代内置词表） */
        public String customWords = "";

        public PassphraseOptions() {
        }

        public PassphraseOptions(int words, String separator, boolean capitalize, boolean appendNumber, String customWords) {
            this.words = words;
            this.separator = separator;
            this.capitalize = capitalize;
            this.appendNumber = appendNumber;
            this.customWords = customWords;
        }
    }

    // ==================== 随机密码 ====================

    /**
     * 生成随机密码（默认 16 位，四类字符均包含，排除易混淆字符）。
     */
    public static String generatePassword() {
        return generate(new Options());
    }

    /**
     * 生成指定长度的随机密码（四类字符均至少包含 1 个，排除易混淆字符）。
     *
     * @param length 密码长度，至少 8；小于 8 时按 8 处理
     */
    public static String generatePassword(int length) {
        Options o = new Options();
        o.length = Math.max(8, length);
        return generate(o);
    }

    /**
     * 按参数生成随机密码。至少选择一类字符；若只勾选了自定义字符集，
     * 则完全按自定义字符集生成。生成结果保证每个选中的字符类别至少出现 1 次。
     */
    public static String generate(Options opt) {
        Options o = opt == null ? new Options() : opt;
        int len = Math.max(1, o.length);

        List<char[]> pools = new ArrayList<>();
        if (o.upper) {
            pools.add(filter(UPPER, o.excludeAmbiguous));
        }
        if (o.lower) {
            pools.add(filter(LOWER, o.excludeAmbiguous));
        }
        if (o.digits) {
            pools.add(filter(DIGITS, o.excludeAmbiguous));
        }
        if (o.symbols) {
            pools.add(SYMBOLS);
        }
        String custom = o.customCharset == null ? "" : o.customCharset;
        if (!custom.isEmpty()) {
            pools.add(filter(custom.toCharArray(), o.excludeAmbiguous));
        }

        // 兜底：未选任何类别时退回小写字母 + 数字
        if (pools.isEmpty()) {
            pools.add(filter(LOWER, o.excludeAmbiguous));
            pools.add(filter(DIGITS, o.excludeAmbiguous));
        }

        char[] all = concat(pools);
        if (all.length == 0) {
            return "";
        }

        char[] pwd = new char[len];
        int filled = 0;
        // 每类先各放一个，保证类别覆盖（长度不足时按顺序截断）
        for (char[] pool : pools) {
            if (filled >= len) {
                break;
            }
            if (pool.length > 0) {
                pwd[filled++] = randomChar(pool);
            }
        }
        for (int i = filled; i < len; i++) {
            pwd[i] = randomChar(all);
        }
        // 洗牌，避免固定前缀
        for (int i = len - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = pwd[i];
            pwd[i] = pwd[j];
            pwd[j] = tmp;
        }
        return new String(pwd);
    }

    // ==================== 口令短语 ====================

    /**
     * 生成口令短语，如 correct-horse-battery-staple 风格（默认 4 词，"-" 连接）。
     * 词表完全内置本地，不联网、无第三方依赖。
     */
    public static String generatePassphrase() {
        return generatePassphrase(new PassphraseOptions());
    }

    /** 按参数生成口令短语 */
    public static String generatePassphrase(PassphraseOptions opt) {
        PassphraseOptions o = opt == null ? new PassphraseOptions() : opt;
        String[] list = resolveWords(o.customWords);
        if (list.length == 0) {
            list = PassphraseWordList.words();
        }
        if (list.length == 0) {
            return "";
        }
        int count = Math.max(1, Math.min(o.words, 32));
        String sep = o.separator == null ? "" : o.separator;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String w = list[RANDOM.nextInt(list.length)];
            if (o.capitalize) {
                w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(w);
        }
        if (o.appendNumber) {
            sb.append(10 + RANDOM.nextInt(90));
        }
        return sb.toString();
    }

    /** 解析自定义词表（逗号/分号/空格/换行分隔）；为空返回空数组 */
    private static String[] resolveWords(String custom) {
        if (custom == null || custom.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = custom.split("[,;\\s]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }

    // ==================== 邮箱 ====================

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

    // ==================== 内部工具 ====================

    /** 从字符池中剔除易混淆字符 */
    private static char[] filter(char[] pool, boolean excludeAmbiguous) {
        if (!excludeAmbiguous || pool.length == 0) {
            return pool;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : pool) {
            if (AMBIGUOUS.indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? pool : sb.toString().toCharArray();
    }

    private static char randomChar(char[] pool) {
        return pool[RANDOM.nextInt(pool.length)];
    }

    private static char[] concat(List<char[]> arrays) {
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
