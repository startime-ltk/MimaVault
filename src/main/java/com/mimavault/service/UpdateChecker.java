package com.mimavault.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查更新（对齐安卓端 A9.18.0 UpdateChecker）：多源策略，国内可快速访问。
 * 仅更新通道联网，密码数据不上传。
 *
 *  - 更新源（依次尝试，每源超时约 8 秒，失败自动切换下一源）：
 *      1) jsDelivr CDN：https://cdn.jsdelivr.net/gh/startime-ltk/MimaVault@main/output/version.txt
 *      2) GitHub 代理镜像：https://ghfast.top/https://raw.githubusercontent.com/startime-ltk/MimaVault/main/output/version.txt
 *      3) GitHub 直连：https://raw.githubusercontent.com/startime-ltk/MimaVault/main/output/version.txt
 *    内容格式：`版本号|安装包文件名或完整URL[|sha256]`，
 *    如 "8.35.1|https://ghfast.top/https://github.com/startime-ltk/MimaVault/releases/download/v8.35.1/MimaVault-Setup-v8.35.1.exe"
 *    第三段选填：安装包 SHA-256（64 位 hex），用于下载后校验完整性；缺省则不校验。
 *  - 下载：由 {@link UpdateDownloader} 下载到本地并校验，不再由系统浏览器打开下载页。
 *  - 版本比较：按 . - _ 分段，每段取首个连续数字块比较，容错非数字段（v8.35.0 == 8.35.0）
 */
public final class UpdateChecker {

    /** 每个更新源的连接 / 读取超时（毫秒） */
    public static final int TIMEOUT_MS = 8_000;

    /** 更新源定义：{显示名, 版本信息URL, 安装包下载基础URL}，按优先级排列 */
    private static final String[][] SOURCES = {
            {"CDN",
                    "https://cdn.jsdelivr.net/gh/startime-ltk/MimaVault@main/output/version.txt",
                    "https://cdn.jsdelivr.net/gh/startime-ltk/MimaVault@main/output/"},
            {"镜像",
                    "https://ghfast.top/https://raw.githubusercontent.com/startime-ltk/MimaVault/main/output/version.txt",
                    "https://ghfast.top/https://raw.githubusercontent.com/startime-ltk/MimaVault/main/output/"},
            {"直连",
                    "https://raw.githubusercontent.com/startime-ltk/MimaVault/main/output/version.txt",
                    "https://raw.githubusercontent.com/startime-ltk/MimaVault/main/output/"}
    };

    /** 更新信息：远端版本号 + 安装包文件名 + 命中源（显示名 / 安装包下载基础 URL）+ 可选校验值 */
    public static final class UpdateInfo {
        public final String version;
        public final String packageName;
        public final String sourceName;
        public final String packageBaseUrl;

        /**
         * 安装包 SHA-256（64 位小写 hex）；{@code null} 表示更新清单未提供校验值。
         *
         * <p><b>注意</b>：这个值本身也是从更新源取回的，因此它只能防"传输损坏 / CDN 缓存错乱"，
         * <b>不能证明来源可信</b> —— 若更新源被篡改，哈希会跟着一起被改。真正的来源认证
         * 需要非对称签名（发版方私钥签、程序内嵌公钥验），那是后续版本的事。
         */
        public final String sha256;

        UpdateInfo(String version, String packageName, String sourceName, String packageBaseUrl, String sha256) {
            this.version = version;
            this.packageName = packageName;
            this.sourceName = sourceName;
            this.packageBaseUrl = packageBaseUrl;
            this.sha256 = sha256;
        }

        /** 命中源对应的安装包完整下载地址（version.txt 第二段可为完整 URL，直接透传） */
        public String packageUrl() {
            if (packageName.startsWith("http://") || packageName.startsWith("https://")) {
                return packageName;
            }
            return packageBaseUrl + packageName;
        }
    }

    private UpdateChecker() {
    }

    /**
     * 检查远端更新源（多源策略）：依次尝试 CDN → 镜像 → 直连，
     * 每源超时约 {@link #TIMEOUT_MS} 毫秒，超时或失败自动切换下一源；
     * 命中可用源后返回带源信息的 UpdateInfo。
     *
     * @param currentVersion 当前版本号（如 "8.35.0"）
     * @return 有新版时返回 UpdateInfo（含命中源）；已是最新或远端内容非法时返回 null
     * @throws IOException 全部更新源均不可达
     */
    public static UpdateInfo checkRemote(String currentVersion) throws IOException {
        IOException lastError = null;
        for (String[] source : SOURCES) {
            try {
                String body = httpGet(source[1]);
                String line = firstNonEmptyLine(body);
                if (line == null) {
                    continue;
                }
                String[] parts = splitVersionLine(line);
                if (parts == null) {
                    // 该行格式非法：换下一个源
                    continue;
                }
                if (compareVersions(parts[0], currentVersion) <= 0) {
                    // 命中可用源且已是最新：停止问下一个源
                    return null;
                }
                return new UpdateInfo(parts[0], parts[1], source[0], source[2], parts[2]);
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (lastError == null) {
            lastError = new IOException("所有更新源均不可用");
        }
        throw lastError;
    }

    /**
     * 拆分一行更新清单：{@code 版本|文件名或URL[|sha256]}。
     *
     * <p>第三段可选；缺失或格式非法时返回的数组中该位置为 {@code null}（表示"未提供校验值"），
     * 而不是让整行作废 —— 老格式（两段）必须继续可用。
     *
     * @param line 清单中的一行
     * @return 长度为 3 的数组 {@code {版本, 包名或URL, sha256或null}}；整行非法时返回 null
     */
    static String[] splitVersionLine(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.trim().split("\\|", -1);
        if (parts.length < 2) {
            return null;
        }
        String remoteVersion = parts[0].trim();
        String packageName = parts[1].trim();
        if (remoteVersion.isEmpty() || packageName.isEmpty()) {
            return null;
        }
        String sha256 = parts.length >= 3 ? normalizeSha256(parts[2]) : null;
        return new String[]{remoteVersion, packageName, sha256};
    }

    /** 规范化 SHA-256：只接受 64 位十六进制，统一小写；否则返回 null（视为未提供） */
    static String normalizeSha256(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        // 允许用户写成 "sha256:xxxx" 或 "SHA256=xxxx" 这类带前缀的形式
        int sep = s.indexOf(':');
        if (sep >= 0) {
            s = s.substring(sep + 1).trim();
        }
        sep = s.indexOf('=');
        if (sep >= 0) {
            s = s.substring(sep + 1).trim();
        }
        if (s.length() != 64) {
            return null;
        }
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return null;
            }
        }
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    /** 版本号比较：按 . - _ 分段，每段取首个连续数字块；无数字段按 0。
     * "8.35.0" > "8.34.0"；"v8.35.0" 与 "8.35.0" 相等。
     */
    public static int compareVersions(String a, String b) {
        List<Integer> va = parseVersion(a);
        List<Integer> vb = parseVersion(b);
        int n = Math.max(va.size(), vb.size());
        for (int i = 0; i < n; i++) {
            int x = i < va.size() ? va.get(i) : 0;
            int y = i < vb.size() ? vb.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** 拆分版本号并提取每段首个数字块（容错 "v"、"A"、"beta" 等非数字段） */
    private static List<Integer> parseVersion(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        String[] parts = s.split("[.\\-_\\s]+");
        Pattern p = Pattern.compile("\\d+");
        for (String part : parts) {
            Matcher m = p.matcher(part);
            int val = 0;
            if (m.find()) {
                try {
                    val = Integer.parseInt(m.group());
                } catch (NumberFormatException ignored) {
                    val = 0;
                }
            }
            out.add(val);
        }
        return out;
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "MimaVault-PC");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("更新源不可达，HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String firstNonEmptyLine(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\r?\n")) {
            if (!line.trim().isEmpty()) {
                return line;
            }
        }
        return null;
    }
}
