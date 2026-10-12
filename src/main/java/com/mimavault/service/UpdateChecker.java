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
 *    内容格式：版本号|安装包文件名，如 "8.35.0|MimaVault-安装程序_v8.35.0.exe"
 *  - 下载：命中源后由系统浏览器打开同一源的安装包地址（<源安装包基础URL>/<文件名>）
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

    /** 更新信息：远端版本号 + 安装包文件名 + 命中源（显示名 / 安装包下载基础 URL） */
    public static final class UpdateInfo {
        public final String version;
        public final String packageName;
        public final String sourceName;
        public final String packageBaseUrl;

        UpdateInfo(String version, String packageName, String sourceName, String packageBaseUrl) {
            this.version = version;
            this.packageName = packageName;
            this.sourceName = sourceName;
            this.packageBaseUrl = packageBaseUrl;
        }

        /** 命中源对应的安装包完整下载地址 */
        public String packageUrl() {
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
                String[] parts = line.trim().split("\\|");
                if (parts.length < 2) {
                    continue;
                }
                String remoteVersion = parts[0].trim();
                String packageName = parts[1].trim();
                if (remoteVersion.isEmpty() || packageName.isEmpty()) {
                    continue;
                }
                if (compareVersions(remoteVersion, currentVersion) <= 0) {
                    return null;
                }
                return new UpdateInfo(remoteVersion, packageName, source[0], source[2]);
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
     * 版本号比较：按 . - _ 分段，每段取首个连续数字块；无数字段按 0。
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
