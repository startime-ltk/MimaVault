package com.passwordmaster.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 密码强度检测工具
 *
 * 评分规则（可解释，便于答辩讲解）：
 * 1. 空 / null 密码：直接判「弱」（是否计入弱密码统计由调用方决定，主界面统计会排除无密码条目）
 * 2. 命中常见弱密码黑名单（123456、password、qwerty 等）：直接判「弱」
 * 3. 计分（满分 6 分）：
 *    - 长度分：长度 ≥ 8 得 1 分；≥ 12 再得 1 分（最多 2 分）
 *    - 种类分：小写字母 / 大写字母 / 数字 / 特殊符号，每覆盖一类得 1 分（最多 4 分）
 * 4. 纯数字或纯字母：总分封顶 3 分（等级最多为「中」）
 * 5. 等级：总分 0~2 为弱，3~4 为中，5~6 为强
 */
public final class PasswordStrengthUtil {

    private PasswordStrengthUtil() {
    }

    /** 强度等级 */
    public enum Level {
        WEAK("弱"),
        MEDIUM("中"),
        STRONG("强");

        private final String text;

        Level(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    /** 常见弱密码黑名单 */
    public static final Set<String> COMMON_WEAK = new HashSet<>(Arrays.asList(
            "123456", "123456789", "12345678", "1234567", "123123", "111111",
            "password", "password1", "123qwe", "qwerty", "qwerty123", "abc123",
            "abc123456", "admin", "admin123", "root", "root123", "test", "test123",
            "iloveyou", "monkey", "dragon", "welcome", "sunshine", "666666",
            "88888888", "000000", "a123456", "1qaz2wsx", "aa123456", "a1b2c3"
    ));

    /** 强度评估结果 */
    public static final class Result {
        public final Level level;
        public final int score;
        public final List<String> reasons;

        Result(Level level, int score, List<String> reasons) {
            this.level = level;
            this.score = score;
            this.reasons = reasons;
        }

        @Override
        public String toString() {
            return level.getText() + "(得分 " + score + "/6)";
        }
    }

    /**
     * 评估明文密码强度
     *
     * @param password 明文密码（可能为 null 或空）
     * @return 评估结果
     */
    public static Result evaluate(String password) {
        List<String> reasons = new ArrayList<>();

        if (password == null || password.isEmpty()) {
            reasons.add("未设置密码");
            return new Result(Level.WEAK, 0, reasons);
        }

        // 1. 黑名单直接判弱
        if (COMMON_WEAK.contains(password)) {
            reasons.add("命中常见弱密码黑名单");
            return new Result(Level.WEAK, 0, reasons);
        }

        int score = 0;

        // 2. 长度分
        int len = password.length();
        if (len >= 8) {
            score += 1;
            reasons.add("长度≥8(+1)");
        }
        if (len >= 12) {
            score += 1;
            reasons.add("长度≥12(+1)");
        }

        // 3. 字符种类分
        boolean lower = password.matches(".*[a-z].*");
        boolean upper = password.matches(".*[A-Z].*");
        boolean digit = password.matches(".*\\d.*");
        boolean special = password.matches(".*[^a-zA-Z0-9].*");
        int kinds = 0;
        if (lower) {
            kinds++;
            reasons.add("含小写字母(+1)");
        }
        if (upper) {
            kinds++;
            reasons.add("含大写字母(+1)");
        }
        if (digit) {
            kinds++;
            reasons.add("含数字(+1)");
        }
        if (special) {
            kinds++;
            reasons.add("含特殊符号(+1)");
        }
        score += kinds;

        // 4. 纯数字 / 纯字母降级：总分封顶 3 分（最多「中」）
        boolean pureDigit = password.matches("\\d+");
        boolean pureLetter = password.matches("[a-zA-Z]+");
        if (pureDigit || pureLetter) {
            if (score > 3) {
                score = 3;
            }
            reasons.add("纯数字/纯字母，强度封顶为中");
        }

        // 5. 等级判定
        Level level;
        if (score <= 2) {
            level = Level.WEAK;
            reasons.add("总得分较低");
        } else if (score <= 4) {
            level = Level.MEDIUM;
        } else {
            level = Level.STRONG;
        }

        return new Result(level, score, reasons);
    }
}
