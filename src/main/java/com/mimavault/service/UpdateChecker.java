package com.mimavault.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查更新（对齐安卓端 A9.18.0 UpdateChecker）：多源策略，国内可快速访问。
 * 仅更新通道联网，密码数据不上传。
 *
 *  - 更新源（依次问两个、取版本号最大的结果；每源超时 8 秒，失败自动换下一个）：
 *      1) GitHub 代理镜像：https://ghfast.top/https://raw.githubusercontent.com/startime-ltk/MimaVault/main/output/version.txt
 *      2) GitHub 直连：https://raw.githubusercontent.com/startime-ltk/MimaVault/main/output/version.txt
 *    内容格式：版本号|安装包文件名（同源下载）或 版本号|完整下载URL[|sha256]，
 *    如 "8.35.4|https://ghfast.top/https://github.com/startime-ltk/MimaVault/releases/download/v8.35.4/MimaVault-Setup-v8.35.4.exe|<64位哈希>"
 *  - 防缓存（v8.35.4）：实测 jsDelivr CDN 会把 version.txt 缓存成好几个版本前的旧内容
 *    （2026-11-06：线上已是 8.35.3，CDN 仍返回 8.35.1，加时间戳参数也没用），
 *    所以【不再把 CDN 当版本源】——宁可提示「检查更新失败，请重试」，也不能让人装回旧版。
 *    两个源都直接从 GitHub 仓库读，URL 带时间戳参数绕开中间缓存，并取版本号最大者。
 *    发布新版本时需另外 purge 一次 jsDelivr 缓存，照顾仍带 CDN 源的旧版客户端。
 *  - 下载：程序内直接下载安装包，下载完静默覆盖安装（v8.35.4 起不再跳浏览器）。
 *    主地址失败会自动换镜像重试；下载完成后校验 PE 头（MZ）与可选的 sha256。
 *  - 版本比较：按 . - _ 分段，每段取首个连续数字块比较，容错非数字段（v8.35.0 == 8.35.0）
 */
public final class UpdateChecker {

    /** 每个更新源的连接 / 读取超时（毫秒） */
    public static final int TIMEOUT_MS = 8_000;

    /** 下载安装包时的连接超时 / 单次读取超时（毫秒），大文件读取给足时间 */
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 30_000;

    /** 更新源定义：{显示名, 版本信息URL, 安装包下载基础URL}，按优先级排列 */
    private static final String[][] SOURCES = {
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
        /** 安装包 sha256（version.txt 第三段，可为空；为空时跳过校验） */
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

    /** 下载进度回调：downloaded 已下载字节，total 总字节（未知时 <= 0） */
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    private UpdateChecker() {
    }

    /**
     * 检查远端更新源（顺序双源 + 取最大版本）：
     * 镜像 → 直连依次请求，各源独立判错、互不影响，最后取版本号最大的结果。
     * 这样即使某个源短暂缓存了旧内容，只要另一个是新的就不会漏更新。
     * 两个源都失败时抛 IOException，由界面提示「检查更新失败，请重试」，
     * 而不是退回到会返回旧版本的 CDN 源。
     *
     * <p>没有用并发：本机网络代理在同时发多个 HTTPS 请求时更容易握手失败，
     * 顺序请求实测成功率明显更高，而两个源通常都在 1~2 秒内返回。
     *
     * @param currentVersion 当前版本号（如 "8.35.0"）
     * @return 有新版时返回 UpdateInfo（含命中源）；已是最新返回 null
     * @throws IOException 全部更新源均不可达
     */
    public static UpdateInfo checkRemote(String currentVersion) throws IOException {
        UpdateInfo best = null;
        IOException lastError = null;
        for (String[] source : SOURCES) {
            try {
                String body = httpGet(source[1]);
                UpdateInfo parsed = parseLine(firstNonEmptyLine(body), source[0], source[2]);
                if (parsed != null && (best == null || compareVersions(parsed.version, best.version) > 0)) {
                    best = parsed;
                }
            } catch (IOException e) {
                lastError = e; // 这个源挂了，继续问下一个
            }
        }
        if (best == null) {
            throw lastError != null ? lastError : new IOException("所有更新源均不可用");
        }
        return compareVersions(best.version, currentVersion) > 0 ? best : null;
    }

    /** 取单个源的内容（包可见，供隔离验证断言防缓存参数） */
    static String sourceUrlWithBuster(String url, long stamp) {
        return url + (url.contains("?") ? "&" : "?") + "t=" + stamp;
    }

    /**
     * 解析 version.txt 的一行内容（包可见，供隔离验证直接调用）。
     *
     * <p>格式：版本号|安装包文件名或完整URL[|sha256]；第三段可省略（旧格式兼容），
     * 也可以写成 "sha256:xxx"。格式不合法返回 null，由调用方换下一个更新源。
     */
    static UpdateInfo parseLine(String line, String sourceName, String packageBaseUrl) {
        if (line == null) {
            return null;
        }
        String[] parts = line.trim().split("\\|");
        if (parts.length < 2) {
            return null;
        }
        String remoteVersion = parts[0].trim();
        String packageName = parts[1].trim();
        if (remoteVersion.isEmpty() || packageName.isEmpty()) {
            return null;
        }
        String sha = parts.length >= 3 ? normalizeSha(parts[2].trim()) : "";
        return new UpdateInfo(remoteVersion, packageName, sourceName, packageBaseUrl, sha);
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

    /**
     * 下载安装包到指定目录（v8.35.4 起程序内直接下载，不再跳浏览器）。
     *
     * <p>主地址失败会自动换镜像再试；每个地址最多尝试两次。
     * 下载先写 .part 临时文件，校验通过才转正，避免半截文件被当成安装包执行。
     *
     * @param info     检查更新得到的版本信息
     * @param dir      落地目录（不存在会自动创建）
     * @param listener 进度回调，可为 null
     * @param cancelled 返回 true 时中止下载，可为 null
     * @return 下载完成的安装包文件
     * @throws IOException 所有地址都失败，或用户取消
     */
    public static File download(UpdateInfo info, File dir, ProgressListener listener,
                                BooleanSupplier cancelled) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录：" + dir);
        }

        String primary = info.packageUrl();
        List<String> urls = buildCandidateUrls(primary);

        String fileName = deriveFileName(primary);
        File out = new File(dir, fileName);
        IOException lastError = null;
        for (String url : urls) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new IOException("已取消下载");
                }
                try {
                    downloadTo(url, out, listener, cancelled);
                    verify(out, info.sha256);
                    return out;
                } catch (IOException e) {
                    lastError = e;
                    // 校验失败时坏文件可能已被转正，删掉，避免留下看似正常的安装包
                    try {
                        Files.deleteIfExists(out.toPath());
                    } catch (IOException ignored) {
                        // 清理失败不影响主流程
                    }
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw e;
                    }
                }
            }
        }
        throw lastError != null ? lastError : new IOException("下载失败");
    }

    /** 构造候选下载地址（包可见，供隔离验证断言换源逻辑） */
    static List<String> buildCandidateUrls(String primary) {
        List<String> urls = new ArrayList<>();
        urls.add(primary);
        // 直连失败时补一个镜像地址；镜像失败时退回直连
        if (primary.startsWith("https://github.com/")) {
            urls.add("https://ghfast.top/" + primary);
        } else if (primary.startsWith("https://ghfast.top/")) {
            urls.add(primary.substring("https://ghfast.top/".length()));
        }
        return urls;
    }

    private static void downloadTo(String url, File out, ProgressListener listener,
                                   BooleanSupplier cancelled) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        try {
            conn.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MimaVault-PC");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载失败，HTTP " + code);
            }
            long total = conn.getContentLengthLong();
            long read = 0L;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new IOException("已取消下载");
                    }
                    fos.write(buf, 0, n);
                    read += n;
                    if (listener != null) {
                        listener.onProgress(read, total);
                    }
                }
            }
            if (read < 1024 * 1024) {
                throw new IOException("下载内容不完整（仅 " + read + " 字节）");
            }
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (IOException ignored) {
                // 清理失败不影响主流程
            }
        }
    }

    /** 校验下载结果：必须是 Windows 可执行文件；有 sha256 时逐一比对 */
    private static void verify(File file, String expectedSha) throws IOException {
        byte[] head = new byte[2];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(head) != 2 || head[0] != 'M' || head[1] != 'Z') {
                throw new IOException("下载到的文件不是有效的安装包，已中止");
            }
        }
        if (expectedSha != null && !expectedSha.isEmpty()) {
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(expectedSha)) {
                throw new IOException("安装包校验不一致（可能是下载被中断或源文件已变更），已中止");
            }
        }
    }

    /** 计算文件 sha256（小写十六进制） */
    public static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("当前运行环境不支持 SHA-256", e);
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    /** 从 URL 末段取文件名；取不到时给个兜底名字 */
    private static String deriveFileName(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? "MimaVault-Update.exe" : name;
    }

    /** 兼容 "sha256:xxx" 与纯十六进制两种写法 */
    private static String normalizeSha(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.toLowerCase().startsWith("sha256:")) {
            s = s.substring("sha256:".length()).trim();
        }
        return s.matches("[0-9a-fA-F]{64}") ? s.toLowerCase() : "";
    }

    private static String httpGet(String url) throws IOException {
        // 版本信息每次都带不同参数，绕开 GitHub raw / 代理中间层的短缓存
        String freshUrl = sourceUrlWithBuster(url, System.currentTimeMillis());
        HttpURLConnection conn = (HttpURLConnection) new URL(freshUrl).openConnection();
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
