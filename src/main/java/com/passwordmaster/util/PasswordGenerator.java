package com.passwordmaster.util;

import java.security.SecureRandom;

/**
 * 随机密码 / 邮箱地址生成器
 * - 密码：默认 16 位，大小写字母 + 数字 + 符号，排除易混字符（0/O/1/l/I）
 * - 邮箱：生成形似真实邮箱的字符串（如 zhangwei_8291@163.com），仅用于填表占位
 */
public final class PasswordGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 大写字母（排除易混 I/O） */
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    /** 小写字母（排除易混 l） */
    private static final char[] LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    /** 数字（排除易混 0/1） */
    private static final char[] DIGITS = "23456789".toCharArray();
    /** 常用符号 */
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

    /**
     * 生成随机密码（默认 16 位，四类字符均至少包含 1 个）。
     */
    public static String generatePassword() {
        return generatePassword(16);
    }

    /**
     * 生成指定长度的随机密码，保证大小写字母、数字、符号各至少 1 个。
     *
     * @param length 密码长度，至少 8；小于 8 时按 8 处理
     */
    public static String generatePassword(int length) {
        int len = Math.max(8, length);
        char[] pwd = new char[len];
        // 先各放一类，保证覆盖
        pwd[0] = randomChar(UPPER);
        pwd[1] = randomChar(LOWER);
        pwd[2] = randomChar(DIGITS);
        pwd[3] = randomChar(SYMBOLS);
        // 其余位从全集中随机
        char[] all = concat(UPPER, LOWER, DIGITS, SYMBOLS);
        for (int i = 4; i < len; i++) {
            pwd[i] = all[RANDOM.nextInt(all.length)];
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

    private static char randomChar(char[] pool) {
        return pool[RANDOM.nextInt(pool.length)];
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
