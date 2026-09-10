package com.mimavault.util;

import com.mimavault.model.Entry;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 导入导出（通用格式，兼容 Bitwarden / LastPass / Chrome / NordPass / Dashlane）
 * 导出：name,url,username,password,notes 五列英文表头，UTF-8 带 BOM，
 *       标准转义（含逗号/引号/换行的值用双引号包裹、内部引号双写）
 * 导入：UTF-8（含 BOM）/ GBK 编码探测，表头按名称映射（大小写不敏感，忽略未知列），
 *       标准引号转义解析，空用户名/空密码容错
 */
public final class CsvUtil {

    /** 导出表头（英文，兼容主流密码管理器） */
    private static final String[] HEADERS = {"name", "url", "username", "password", "notes"};

    /** 脱敏导出时密码列的占位内容（不写入任何明文，也不做解密） */
    public static final String MASKED_PASSWORD = "********";

    private CsvUtil() {
    }

    // ==================== 导出 ====================

    /**
     * 导出全部条目为通用 CSV（兼容旧调用，密码列为完整明文）
     *
     * @param entries    密码字段为密文的条目列表（调用方从库中读取，此处解密）
     * @param key        主密码派生密钥
     * @param targetPath 目标 .csv 文件
     */
    public static void export(List<Entry> entries, SecretKey key, Path targetPath) throws IOException {
        export(entries, key, targetPath, false);
    }

    /**
     * 导出全部条目为通用 CSV（UTF-8 带 BOM）
     *
     * @param entries      密码字段为密文的条目列表
     * @param key          主密码派生密钥
     * @param targetPath   目标 .csv 文件
     * @param maskPassword true = 密码列脱敏（写占位符，完全不解密，适合分享/备份）；
     *                     false = 写入完整明文密码（高危，调用方必须先做风险二次确认）
     */
    public static void export(List<Entry> entries, SecretKey key, Path targetPath, boolean maskPassword)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADERS)).append("\r\n");
        for (Entry e : entries) {
            List<String> row = new ArrayList<>(HEADERS.length);
            row.add(nz(e.getPlatform()));       // name
            row.add("");                        // url：密匣无独立网址字段
            row.add(nz(e.getAccount()));        // username
            // password：脱敏时直接写占位符，不触碰密文，杜绝明文进入内存与文件
            row.add(maskPassword ? MASKED_PASSWORD : decryptOrEmpty(e, key));
            row.add(nz(e.getNote()));           // notes
            sb.append(joinRow(row)).append("\r\n");
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(targetPath)) {
            out.write(bom);
            out.write(body);
        }
    }

    private static String decryptOrEmpty(Entry e, SecretKey key) {
        if (e.getPasswordEnc() == null || e.getPasswordEnc().isEmpty()) {
            return "";
        }
        try {
            String plain = AesUtil.decrypt(e.getPasswordEnc(), key);
            return plain == null ? "" : plain;
        } catch (Exception ex) {
            return "";
        }
    }

    /** 将一行字段拼接为 CSV 行（含转义） */
    private static String joinRow(List<String> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(row.get(i)));
        }
        return sb.toString();
    }

    /** 标准 CSV 转义：含逗号/引号/换行的值用双引号包裹，内部引号双写 */
    private static String escape(String v) {
        String s = v == null ? "" : v;
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ==================== 导入 ====================

    /** CSV 解析后的单行数据（表头映射后） */
    public static class CsvRow {
        public String name = "";
        public String url = "";
        public String username = "";
        public String password = "";
        public String notes = "";
        public String totp = "";
    }

    /**
     * 解析 CSV 文件：编码探测（UTF-8 BOM / UTF-8 严格 / GBK 回退）→ 表头映射 → 行解析
     *
     * @return 数据行列表（已跳过表头与空行）
     * @throws IOException 文件无法读取或表头无有效列
     */
    public static List<CsvRow> parse(Path source) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        String text = decode(bytes);
        List<List<String>> rows = parseCsv(text);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }

        // 表头：归一化列名（小写去空格）→ 列索引；大小写不敏感、忽略未知列
        Map<String, Integer> headerIdx = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            String col = normalize(header.get(i));
            if (!col.isEmpty()) {
                headerIdx.putIfAbsent(col, i);
            }
        }
        if (headerIdx.isEmpty()) {
            throw new IOException("CSV 缺少可识别的表头行");
        }
        // 常见变体映射：Bitwarden/LastPass/Chrome/NordPass/Dashlane + 中文常见列名
        int nameIdx = firstIdx(headerIdx, "name", "title", "标题", "名称", "平台", "平台名称");
        int urlIdx = firstIdx(headerIdx, "url", "website", "web site", "网址", "地址");
        int userIdx = firstIdx(headerIdx, "username", "login", "登录名", "账号", "用户名", "用户", "帐号");
        int pwdIdx = firstIdx(headerIdx, "password", "密码");
        int noteIdx = firstIdx(headerIdx, "notes", "note", "备注", "注释");
        int totpIdx = firstIdx(headerIdx, "totp", "otpauth", "2fa", "验证码");
        if (nameIdx < 0 && userIdx < 0 && pwdIdx < 0) {
            throw new IOException("CSV 表头未找到可识别的名称/账号/密码列");
        }

        List<CsvRow> result = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (isEmptyRow(row)) {
                continue;
            }
            CsvRow cr = new CsvRow();
            cr.name = cell(row, nameIdx);
            cr.url = cell(row, urlIdx);
            cr.username = cell(row, userIdx);
            cr.password = cell(row, pwdIdx);
            cr.notes = cell(row, noteIdx);
            cr.totp = cell(row, totpIdx);
            // 全空行容错
            if (cr.name.isEmpty() && cr.username.isEmpty() && cr.password.isEmpty()
                    && cr.url.isEmpty() && cr.notes.isEmpty()) {
                continue;
            }
            result.add(cr);
        }
        return result;
    }

    /** 编码探测：UTF-8 BOM / UTF-16 BOM / UTF-8 严格解码 / GBK 回退 */
    private static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE
                || (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF)) {
            return new String(bytes, StandardCharsets.UTF_16);
        }
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // UTF-8 非法 → 按 GBK 解析（国内导出文件的常见编码）
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /** 标准 CSV 状态机解析：双引号包裹、内部引号双写、字段内换行、\r\n 与 \n */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
            } else {
                if (c == '"' && field.length() == 0) {
                    inQuotes = true;
                    i++;
                } else if (c == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static int firstIdx(Map<String, Integer> map, String... aliases) {
        for (String a : aliases) {
            Integer idx = map.get(normalize(a));
            if (idx != null) {
                return idx;
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) {
            return "";
        }
        return row.get(idx) == null ? "" : row.get(idx).trim();
    }

    private static boolean isEmptyRow(List<String> row) {
        for (String s : row) {
            if (s != null && !s.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
