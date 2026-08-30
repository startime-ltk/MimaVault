package com.passwordmaster.service;

import com.passwordmaster.model.Entry;
import com.passwordmaster.util.BreachedPasswordHashes;
import com.passwordmaster.util.CommonPasswords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 密码健康检测器（离线、纯内存检测）
 *
 * <p>对主窗口内存中已解密的条目明文执行三项检测：
 * <ul>
 *   <li>弱口令：长度 &lt; 8、纯数字、或命中约 1100 条内置常见弱密码表；</li>
 *   <li>重复口令：同一明文密码被多个条目使用；</li>
 *   <li>已泄露密码：SHA-256 离线比对约 90 条高频泄露密码哈希。</li>
 * </ul>
 * 全程不读库、不落盘、不联网；明文仅存活于内存，不打印、不写文件。
 *
 * <p>评分规则（百分制，满分 100，逐条累计扣分，下限 0）：
 * <pre>
 *   未设置密码                     -5 分 / 条  （警告）
 *   弱口令（长度&lt;8 / 纯数字 / 命中弱密码表）  -10 分 / 条（警告；命中常见弱密码表升级为高危/严重）
 *   重复口令                     -5 分 / 条  （警告；同一密码组内每个涉及条目各扣一次）
 *   已泄露密码                   -20 分 / 条  （严重）
 * </pre>
 * 同一条目同时命中多项时逐项累计扣分（弱口令的多个原因按一条 -10 计，避免重复叠加）。
 * 等级：总分 ≥ 90 安全（绿）；70 ~ 89 警告（橙）；&lt; 70 严重（红）。
 */
public final class PasswordHealthChecker {

    private PasswordHealthChecker() {
    }

    /** 严重程度 */
    public enum Severity {
        CRITICAL("严重"),
        WARNING("警告"),
        OK("安全");

        private final String text;

        Severity(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    /** 单项问题 */
    public static final class HealthIssue {
        public final long entryId;
        public final String platform;
        public final String account;
        public final String typeText;        // 问题类型：已泄露 / 高危弱口令 / 弱口令 / 重复口令 / 未设置密码
        public final String message;         // 详细说明
        public final String passwordPreview; // 密码脱敏预览，如 123***
        public final Severity severity;

        public HealthIssue(long entryId, String platform, String account,
                           String typeText, String message, String passwordPreview, Severity severity) {
            this.entryId = entryId;
            this.platform = platform;
            this.account = account;
            this.typeText = typeText;
            this.message = message;
            this.passwordPreview = passwordPreview;
            this.severity = severity;
        }
    }

    /** 检测报告 */
    public static final class ReportResult {
        public final int score;
        public final Severity level;
        public final int totalCount;      // 检测条目总数
        public final int weakCount;       // 弱口令条数（含未设置密码）
        public final int duplicateCount;  // 重复口令涉及条数
        public final int breachedCount;   // 已泄露条数
        public final List<HealthIssue> issues;

        public ReportResult(int score, Severity level, int totalCount,
                            int weakCount, int duplicateCount, int breachedCount,
                            List<HealthIssue> issues) {
            this.score = score;
            this.level = level;
            this.totalCount = totalCount;
            this.weakCount = weakCount;
            this.duplicateCount = duplicateCount;
            this.breachedCount = breachedCount;
            this.issues = issues;
        }
    }

    /**
     * 执行健康检测
     *
     * @param entries   内存中的条目列表（勿重新读库）
     * @param decryptor 条目明文解密函数（返回 null/空 视为未设置密码）
     */
    public static ReportResult check(List<Entry> entries, Function<Entry, String> decryptor) {
        List<HealthIssue> issues = new ArrayList<>();
        int total = entries == null ? 0 : entries.size();
        int weak = 0;
        int dup = 0;
        int br = 0;
        int deduction = 0;

        if (entries == null || entries.isEmpty()) {
            return new ReportResult(100, Severity.OK, 0, 0, 0, 0, issues);
        }

        // 第一遍：逐条弱口令 / 泄露检测
        for (Entry e : entries) {
            String plain = decryptor == null ? "" : decryptor.apply(e);
            if (plain == null) {
                plain = "";
            }
            if (plain.isEmpty()) {
                issues.add(new HealthIssue(e.getId(), e.getPlatform(), e.getAccount(),
                        "未设置密码", "该条目未设置密码，建议尽快补全", "（未设置）", Severity.WARNING));
                weak++;
                deduction += 5;
                continue;
            }

            boolean breached = BreachedPasswordHashes.contains(plain);
            if (breached) {
                issues.add(new HealthIssue(e.getId(), e.getPlatform(), e.getAccount(),
                        "已泄露密码", "该密码命中离线泄露哈希库，风险极高", preview(plain), Severity.CRITICAL));
                br++;
                deduction += 20;
            }

            boolean hitCommon = CommonPasswords.contains(plain);
            boolean tooShort = plain.length() < 8;
            boolean pureDigit = isAllDigits(plain);
            if (hitCommon || tooShort || pureDigit) {
                if (hitCommon) {
                    issues.add(new HealthIssue(e.getId(), e.getPlatform(), e.getAccount(),
                            "高危弱口令", "命中常见弱密码表，极易被暴力破解", preview(plain), Severity.CRITICAL));
                } else {
                    String why = tooShort && pureDigit
                            ? "长度不足 8 位且为纯数字"
                            : tooShort ? "长度不足 8 位" : "纯数字密码";
                    issues.add(new HealthIssue(e.getId(), e.getPlatform(), e.getAccount(),
                            "弱口令", why + "，建议加强复杂度", preview(plain), Severity.WARNING));
                }
                weak++;
                deduction += 10;
            }
        }

        // 第二遍：重复口令检测（空密码不计入重复组）
        Map<String, List<Entry>> byPlain = new HashMap<>();
        for (Entry e : entries) {
            String plain = decryptor == null ? "" : decryptor.apply(e);
            if (plain == null || plain.isEmpty()) {
                continue;
            }
            byPlain.computeIfAbsent(plain, k -> new ArrayList<>()).add(e);
        }
        Set<Long> dupMarked = new HashSet<>();
        for (Map.Entry<String, List<Entry>> me : byPlain.entrySet()) {
            List<Entry> group = me.getValue();
            if (group.size() >= 2) {
                for (Entry e : group) {
                    if (!dupMarked.add(e.getId())) {
                        continue;
                    }
                    issues.add(new HealthIssue(e.getId(), e.getPlatform(), e.getAccount(),
                            "重复口令", "同一密码被 " + group.size() + " 个条目使用",
                            preview(me.getKey()), Severity.WARNING));
                    dup++;
                    deduction += 5;
                }
            }
        }

        int score = Math.max(0, 100 - deduction);
        Severity level;
        if (score >= 90) {
            level = Severity.OK;
        } else if (score >= 70) {
            level = Severity.WARNING;
        } else {
            level = Severity.CRITICAL;
        }

        // 严重问题优先展示
        issues.sort((a, b) -> {
            int bySev = a.severity.compareTo(b.severity);
            return bySev != 0 ? bySev : Long.compare(a.entryId, b.entryId);
        });

        return new ReportResult(score, level, total, weak, dup, br, issues);
    }

    /** 密码脱敏预览：非空密码取前 3 位 + ***（不足 3 位整体脱敏） */
    static String preview(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "（未设置）";
        }
        if (plain.length() <= 3) {
            return "***";
        }
        return plain.substring(0, 3) + "***";
    }

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
