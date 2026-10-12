package com.mimavault.service;

import com.github.promeg.pinyinhelper.Pinyin;
import com.mimavault.model.Entry;
import com.mimavault.util.AesUtil;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 全局搜索内存索引（v8.35.0）
 *
 * 覆盖范围：回收站外的全部条目（网站/应用/其他/WiFi/银行卡/证件/API Key），
 * 检索字段：平台（标题）、账号、手机、邮箱、备注、分类、四分类专属字段明文。
 *
 * 匹配规则：
 *  1) 子序列模糊匹配（"wx" 可命中拼音全拼 weixin 从而命中「微信」）；
 *  2) 中文标题/账号额外生成拼音全拼与首字母索引，支持拼音缩写检索；
 *  3) 标题（平台）优先排序，其次账号，其余字段按更新时间的倒序兜底。
 */
public class GlobalSearchIndex {

    /** 单条命中结果 */
    public static final class Hit {
        public final Entry entry;
        public final String matchedField;
        public final int score;

        public Hit(Entry entry, String matchedField, int score) {
            this.entry = entry;
            this.matchedField = matchedField;
            this.score = score;
        }

        /** 命中摘要：用于搜索结果列表展示 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(nullToEmpty(entry.getPlatform()));
            if (entry.getAccount() != null && !entry.getAccount().trim().isEmpty()) {
                sb.append(" / ").append(entry.getAccount());
            }
            if (entry.getPhone() != null && !entry.getPhone().trim().isEmpty()) {
                sb.append(" / ").append(entry.getPhone());
            }
            return sb.toString();
        }
    }

    private static final class IndexedEntry {
        final Entry entry;
        final String haystack;    // 归一化全文（含拼音），用于子序列匹配
        final String platform;    // 归一化标题
        final String account;     // 归一化账号
        final String extraText;   // 归一化专属字段明文

        IndexedEntry(Entry entry, String haystack, String platform, String account, String extraText) {
            this.entry = entry;
            this.haystack = haystack;
            this.platform = platform;
            this.account = account;
            this.extraText = extraText;
        }
    }

    private final List<IndexedEntry> indexed = new ArrayList<>();

    /** 全量重建索引（打开搜索面板前调用，保证最新数据） */
    public void rebuild(PasswordService service, SecretKey key) {
        indexed.clear();
        List<Entry> all = service.listEntries();
        for (Entry e : all) {
            if (e.getDeletedAt() != null) {
                continue; // 回收站外全部条目
            }
            String platform = norm(e.getPlatform());
            String account = norm(e.getAccount());
            String extraText = decryptExtra(e, key);
            StringBuilder sb = new StringBuilder(128);
            sb.append(platform).append(' ');
            sb.append(account).append(' ');
            sb.append(norm(e.getPhone())).append(' ');
            sb.append(norm(e.getEmail())).append(' ');
            sb.append(norm(e.getNote())).append(' ');
            sb.append(norm(e.getCategory())).append(' ');
            sb.append(extraText).append(' ');
            sb.append(pinyinFull(platform)).append(' ');
            sb.append(pinyinInitial(platform)).append(' ');
            sb.append(pinyinFull(account)).append(' ');
            sb.append(pinyinInitial(account));
            indexed.add(new IndexedEntry(e, sb.toString().toLowerCase(), platform, account, extraText));
        }
    }

    public int size() {
        return indexed.size();
    }

    /** 搜索：子序列模糊匹配 + 标题优先排序；limit<=0 表示不限制 */
    public List<Hit> search(String rawQuery, int limit) {
        String q = norm(rawQuery);
        if (q.isEmpty()) {
            return Collections.emptyList();
        }
        List<Hit> hits = new ArrayList<>();
        for (IndexedEntry ie : indexed) {
            if (isSubsequence(q, ie.haystack)) {
                int score = score(ie, q);
                if (score > 0) {
                    hits.add(new Hit(ie.entry, matchedField(ie, q), score));
                }
            }
        }
        hits.sort((a, b) -> {
            if (a.score != b.score) {
                return Integer.compare(b.score, a.score);
            }
            long ta = a.entry.getUpdatedAt() == null ? 0 : a.entry.getUpdatedAt().getTime();
            long tb = b.entry.getUpdatedAt() == null ? 0 : b.entry.getUpdatedAt().getTime();
            return Long.compare(tb, ta);
        });
        if (limit > 0 && hits.size() > limit) {
            return new ArrayList<>(hits.subList(0, limit));
        }
        return hits;
    }

    /** 打分：标题 > 账号 > 手机/邮箱 > 备注/分类/专属字段；子序列命中按覆盖度加权 */
    private static int score(IndexedEntry ie, String q) {
        if (ie.platform.startsWith(q)) {
            return 1000;
        }
        if (ie.platform.contains(q)) {
            return 850;
        }
        if (isSubsequence(q, ie.platform)) {
            return 700;
        }
        if (ie.account.startsWith(q) || ie.account.contains(q)) {
            return 500;
        }
        if (isSubsequence(q, ie.account)) {
            return 400;
        }
        if (ie.extraText.contains(q)) {
            return 300;
        }
        return 200;
    }

    /** 返回命中的主要字段描述 */
    private static String matchedField(IndexedEntry ie, String q) {
        if (ie.platform.contains(q) || isSubsequence(q, ie.platform)) {
            return "标题";
        }
        if (ie.account.contains(q) || isSubsequence(q, ie.account)) {
            return "账号";
        }
        if (ie.extraText.contains(q)) {
            return "专属字段";
        }
        return "其他";
    }

    /** 解密分类专属字段并拼接为归一化文本；解不开不影响其他字段检索 */
    private static String decryptExtra(Entry e, SecretKey key) {
        if (e.getExtraEnc() == null || e.getExtraEnc().isEmpty() || key == null) {
            return "";
        }
        try {
            String json = AesUtil.decrypt(e.getExtraEnc(), key);
            if (json == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String v : Entry.decodeExtra(json).values()) {
                if (v != null && !v.trim().isEmpty()) {
                    sb.append(norm(v)).append(' ');
                }
            }
            return sb.toString();
        } catch (Exception ignore) {
            return "";
        }
    }

    /** 字符串 A 是否按序出现在 B 中（子序列匹配），A 为空视为命中 */
    private static boolean isSubsequence(String a, String b) {
        if (a == null || a.isEmpty()) {
            return true;
        }
        if (b == null || b.isEmpty()) {
            return false;
        }
        int i = 0;
        int len = a.length();
        for (int j = 0; j < b.length() && i < len; j++) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
            }
        }
        return i == len;
    }

    /** 归一化：去首尾空白、统一小写 */
    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** 中文全拼（非中文忽略） */
    private static String pinyinFull(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Pinyin.isChinese(c)) {
                sb.append(Pinyin.toPinyin(c));
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /** 中文拼音首字母（"微信" -> "wx"） */
    private static String pinyinInitial(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Pinyin.isChinese(c)) {
                String p = Pinyin.toPinyin(c);
                if (!p.isEmpty()) {
                    sb.append(Character.toLowerCase(p.charAt(0)));
                }
            }
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
