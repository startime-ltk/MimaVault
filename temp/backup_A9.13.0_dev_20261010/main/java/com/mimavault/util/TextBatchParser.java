package com.mimavault.util;

import com.mimavault.model.Entry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 批量文本解析：把大段混合文本拆分为多条账号/密码记录草稿
 * <p>
 * 支持两种格式：
 * a. 标签式：平台：微信 账号：xxx 密码：xxx（可一行多组，也可多行）
 * b. 紧凑式：一行内多个字段用 空格 / 逗号 / 竖线 分隔（如 微信|xxx|password123|13800138000），按顺序映射 平台/账号/密码/手机/邮箱
 * <p>
 * 段落按空行、连续换行、常见分隔符（；、----、==== 等）拆分；
 * 同一段内若出现多个"账号"标签组，则按标签位置细拆为多条。
 */
public final class TextBatchParser {

    /** 解析结果草稿：Entry + 原始文本片段 + 来源类型（文字/图片/图文混合） */
    public static class RawRecord {
        public Entry entry;
        public String sourceText;   // 原始文本片段（用于预览）
        public String sourceType;   // 来源：文字 / 图片 / 图文混合

        public RawRecord(Entry entry, String sourceText, String sourceType) {
            this.entry = entry;
            this.sourceText = sourceText;
            this.sourceType = sourceType;
        }
    }

    private TextBatchParser() {
    }

    /**
     * 解析混合文本为多条草稿记录
     *
     * @param text       混合文本
     * @param sourceType 来源类型：文字 / 图片 / 图文混合
     */
    public static List<RawRecord> parseBatch(String text, String sourceType) {
        List<RawRecord> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        for (String seg : splitSegments(text)) {
            result.addAll(parseSegment(seg, sourceType));
        }
        return result;
    }

    /** 拆分段落：空行 / 连续换行 / 分隔符行（----、====、***、；;等） */
    static List<String> splitSegments(String text) {
        List<String> segs = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.matches("[-=_*;；]{3,}") || t.matches("[，,；;、]+")) {
                if (cur.length() > 0) {
                    segs.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            if (cur.length() > 0) {
                cur.append("\n");
            }
            cur.append(line);
        }
        if (cur.length() > 0) {
            segs.add(cur.toString());
        }
        return segs;
    }

    /** 解析单个段落，段内可能含多条（按账号标签切分） */
    static List<RawRecord> parseSegment(String seg, String sourceType) {
        List<RawRecord> records = new ArrayList<>();
        if (seg == null || seg.trim().isEmpty()) {
            return records;
        }
        String text = seg.trim();

        // 检测"账号"标签出现次数，>1 时按标签位置细拆为多个子段
        List<String> subs = splitByAccountLabels(text);
        if (subs.size() > 1) {
            for (String sub : subs) {
                Entry e = parseOne(sub);
                if (e != null) {
                    records.add(new RawRecord(e, sub, sourceType));
                }
            }
            return records;
        }

        Entry e = parseOne(text);
        if (e != null) {
            records.add(new RawRecord(e, text, sourceType));
        }
        return records;
    }

    /** 按"账号/帐号/用户名"标签出现位置切分子段（仅当关键词为独立标签：前后边界有效） */
    static List<String> splitByAccountLabels(String text) {
        List<Integer> positions = new ArrayList<>();
        for (String key : TextParser.ACCOUNT_KEYS) {
            int idx = 0;
            while ((idx = text.indexOf(key, idx)) >= 0) {
                if (isRealLabelAt(text, key, idx)) {
                    positions.add(idx);
                }
                idx += key.length();
            }
        }
        if (positions.size() <= 1) {
            List<String> one = new ArrayList<>();
            one.add(text);
            return one;
        }
        java.util.Collections.sort(positions);
        List<String> subs = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < positions.size(); i++) {
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : text.length();
            String sub = text.substring(positions.get(i), end).trim();
            if (!sub.isEmpty()) {
                subs.add(sub);
            }
            start = end;
        }
        return subs;
    }

    /** 判断 text 中 key 在 idx 处是否为"真标签"：前缀非拉丁字母，且后跟分隔符/紧贴值/结尾（与 TextParser 容错一致） */
    static boolean isRealLabelAt(String text, String key, int idx) {
        return TextParser.isTightLabelAt(text, key, idx);
    }

    /** 解析单条记录；解析不出任何字段返回 null */
    static Entry parseOne(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String[] lines = text.split("\\r?\\n");

        String platform = TextParser.findPlatform(text);
        String email = firstMatch(TextParser.EMAIL, text);
        String phone = firstMatch(TextParser.PHONE, text);

        // 密码：关键词行提取
        String password = TextParser.findValueByKeys(lines, TextParser.PASSWORD_KEYS, null);

        // 账号：关键词行提取（排除与密码相同的值）
        String account = TextParser.findValueByKeys(lines, TextParser.ACCOUNT_KEYS, password);

        // 若没有任何标签命中（无账号/密码关键词），尝试紧凑式解析
        boolean hasLabels = hasAnyLabel(lines);
        if (!hasLabels) {
            Map<String, String> compact = parseCompact(text);
            if (compact != null) {
                if (platform.isEmpty()) {
                    platform = compact.getOrDefault("platform", "");
                }
                if (account == null || account.isEmpty()) {
                    account = compact.getOrDefault("account", "");
                }
                if (password == null || password.isEmpty()) {
                    password = compact.getOrDefault("password", "");
                }
                if (phone == null || phone.isEmpty()) {
                    phone = compact.getOrDefault("phone", "");
                }
                if (email == null || email.isEmpty()) {
                    email = compact.getOrDefault("email", "");
                }
            }
        }

        // 账号兜底：邮箱前缀或手机号
        if (account == null || account.isEmpty()) {
            if (email != null && !email.isEmpty()) {
                account = email.split("@")[0];
            } else if (phone != null && !phone.isEmpty()) {
                account = phone;
            }
        }

        // 平台兜底：无平台则用账号前缀？保持空，由预览中补充
        boolean any = platform != null && !platform.isEmpty();
        any |= account != null && !account.isEmpty();
        any |= password != null && !password.isEmpty();
        any |= phone != null && !phone.isEmpty();
        any |= email != null && !email.isEmpty();
        if (!any) {
            return null;
        }

        Entry entry = new Entry();
        entry.setCategory(Entry.CATEGORY_OTHER);
        if (platform != null && !platform.isEmpty()) {
            entry.setPlatform(platform);
        }
        if (account != null && !account.isEmpty()) {
            entry.setAccount(account);
        }
        if (password != null && !password.isEmpty()) {
            // 明文密码暂存于 note 前置于字段？预览需要明文密码
            // 使用 passwordEnc 字段暂存明文，入库前统一加密
            entry.setPasswordEnc(password);
        }
        if (phone != null && !phone.isEmpty()) {
            entry.setPhone(phone);
        }
        if (email != null && !email.isEmpty()) {
            entry.setEmail(email);
        }
        return entry;
    }

    private static boolean hasAnyLabel(String[] lines) {
        for (String line : lines) {
            if (isLabelLine(line)) {
                return true;
            }
        }
        return false;
    }

    /** 判断单行是否包含"真标签"：关键词后跟冒号/等号；无紧凑分隔符时也接受空白分隔 */
    private static boolean isLabelLine(String line) {
        String l = line.trim();
        if (l.isEmpty()) {
            return false;
        }
        boolean compactSep = l.contains("|") || l.contains("；") || l.contains(";") || l.contains(",");
        for (String key : TextParser.ACCOUNT_KEYS) {
            if (hasLabelAt(l, key)) {
                return true;
            }
        }
        for (String key : TextParser.PASSWORD_KEYS) {
            if (hasLabelAt(l, key)) {
                return true;
            }
        }
        // 无紧凑分隔符的行，允许"关键词+空白"形式（如 "密码 abc123"）
        if (!compactSep) {
            for (String key : TextParser.ACCOUNT_KEYS) {
                int idx = 0;
                while ((idx = l.indexOf(key, idx)) >= 0) {
                    if (isRealLabelAt(l, key, idx)) {
                        return true;
                    }
                    idx += key.length();
                }
            }
            for (String key : TextParser.PASSWORD_KEYS) {
                int idx = 0;
                while ((idx = l.indexOf(key, idx)) >= 0) {
                    if (isRealLabelAt(l, key, idx)) {
                        return true;
                    }
                    idx += key.length();
                }
            }
        }
        return false;
    }

    /** 行内任意位置存在"关键词+冒号/等号"标签 */
    private static boolean hasLabelAt(String line, String key) {
        int idx = 0;
        while ((idx = line.indexOf(key, idx)) >= 0) {
            if (isRealLabelAt(line, key, idx)) {
                int end = idx + key.length();
                if (end < line.length() && (line.charAt(end) == ':' || line.charAt(end) == '：'
                        || line.charAt(end) == '=' || line.charAt(end) == '＝')) {
                    return true;
                }
            }
            idx += key.length();
        }
        return false;
    }

    /**
     * 紧凑式解析：一行内多个字段用 空格 / 逗号 / 竖线 分隔
     * 按顺序映射：平台 / 账号 / 密码 / 手机 / 邮箱
     */
    static Map<String, String> parseCompact(String text) {
        String line = text.trim().replace('\t', ' ');
        // 以 竖线 优先，其次 分号，再次 逗号，最后 空白
        String[] parts;
        if (line.contains("|")) {
            parts = line.split("\\|");
        } else if (line.contains("；")) {
            parts = line.split("；");
        } else if (line.contains(";")) {
            parts = line.split(";");
        } else if (line.contains(",")) {
            parts = line.split(",");
        } else {
            parts = line.split("\\s+");
        }

        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        if (tokens.size() < 2) {
            return null;
        }
        // 过滤纯关键词 token（如"账号""密码"等提示词）
        List<String> vals = new ArrayList<>();
        for (String t : tokens) {
            if (isKeyWordOnly(t)) {
                continue;
            }
            vals.add(t);
        }
        if (vals.size() < 2) {
            return null;
        }

        Map<String, String> map = new LinkedHashMap<>();
        int i = 0;
        String platform = TextParser.findPlatform(vals.get(0));
        if (!platform.isEmpty()) {
            map.put("platform", platform);
            i = 1;
        } else {
            // 第一个 token 视作平台
            map.put("platform", vals.get(0));
            i = 1;
        }
        if (i < vals.size()) {
            map.put("account", vals.get(i++));
        }
        if (i < vals.size()) {
            // 密码：可能是纯数字（如123456）或含字母的字符串
            map.put("password", vals.get(i++));
        }
        if (i < vals.size()) {
            String v = vals.get(i);
            if (v.matches("1[3-9]\\d{9}")) {
                map.put("phone", v);
                i++;
            }
        }
        if (i < vals.size()) {
            String v = vals.get(i);
            if (v.matches("\\b[\\w.+-]+@[\\w-]+\\.[\\w.]+\\b")) {
                map.put("email", v);
                i++;
            }
        }
        return map;
    }

    /** 仅过滤"纯提示词"token（如单独的"账号""密码"），平台名等实际值不过滤 */
    private static boolean isKeyWordOnly(String t) {
        for (String key : TextParser.ACCOUNT_KEYS) {
            if (t.equalsIgnoreCase(key)) {
                return true;
            }
        }
        for (String key : TextParser.PASSWORD_KEYS) {
            if (t.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static String firstMatch(java.util.regex.Pattern pattern, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return m.group();
        }
        return null;
    }
}
