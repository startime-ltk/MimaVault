package com.mimavault.service;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
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
 * 检查更新（A9.16.0）：多源策略，国内可快速访问。仅更新通道联网，数据不上传。
 *
 *  - 更新源（依次尝试，每源超时约 8 秒，失败自动切换下一源）：
 *      1) jsDelivr CDN：https://cdn.jsdelivr.net/gh/startime-ltk/MimaVault@android/output/version.txt
 *      2) GitHub 代理镜像：https://mirror.ghproxy.com/https://raw.githubusercontent.com/startime-ltk/MimaVault/android/output/version.txt
 *      3) GitHub 直连：https://raw.githubusercontent.com/startime-ltk/MimaVault/android/output/version.txt
 *    内容格式：版本号|APK文件名，如 "A9.16.0|MimaVault-A9.16.0.apk"
 *  - 下载：命中源后同一源下载 APK（<源APK基础URL>/<APK文件名>）
 *    → 写入 getExternalFilesDir(null)，经 FileProvider 交系统安装器安装
 *  - 版本比较：按 . - _ 分段，每段取首个连续数字块比较，容错非数字段（A9.16.0 == 9.16.0）
 */
public final class UpdateChecker {

    /** 每个更新源的连接 / 读取超时（毫秒） */
    public static final int TIMEOUT_MS = 8_000;

    /** 更新源定义：{显示名, 版本信息URL, APK下载基础URL}，按优先级排列 */
    private static final String[][] SOURCES = {
            {"CDN",
                    "https://cdn.jsdelivr.net/gh/startime-ltk/MimaVault@android/output/version.txt",
                    "https://cdn.jsdelivr.net/gh/startime-ltk/MimaVault@android/output/"},
            {"镜像",
                    "https://mirror.ghproxy.com/https://raw.githubusercontent.com/startime-ltk/MimaVault/android/output/version.txt",
                    "https://mirror.ghproxy.com/https://raw.githubusercontent.com/startime-ltk/MimaVault/android/output/"},
            {"直连",
                    "https://raw.githubusercontent.com/startime-ltk/MimaVault/android/output/version.txt",
                    "https://raw.githubusercontent.com/startime-ltk/MimaVault/android/output/"}
    };

    private static final String PREFS = "mimavault_update";
    private static final String KEY_PENDING_CLEANUP = "pending_cleanup";

    /** 下载进度回调（线程为下载线程，回调同样在下载线程，UI 需自行切主线程） */
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    /** 更新信息：远端版本号 + APK 文件名 + 命中源（显示名 / APK 下载基础 URL） */
    public static final class UpdateInfo {
        public final String version;
        public final String apkName;
        public final String sourceName;
        public final String apkBaseUrl;

        UpdateInfo(String version, String apkName, String sourceName, String apkBaseUrl) {
            this.version = version;
            this.apkName = apkName;
            this.sourceName = sourceName;
            this.apkBaseUrl = apkBaseUrl;
        }

        /** 命中源对应的 APK 完整下载地址 */
        public String apkUrl() {
            return apkBaseUrl + apkName;
        }
    }

    private UpdateChecker() {
    }

    /**
     * 检查远端更新源（多源策略）：依次尝试 CDN → 镜像 → 直连，
     * 每源超时约 {@link #TIMEOUT_MS} 毫秒，超时或失败自动切换下一源；
     * 命中可用源后返回带源信息的 UpdateInfo。
     *
     * @param currentVersion 当前版本号（如 "A9.16.0"）
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
                String apkName = parts[1].trim();
                if (remoteVersion.isEmpty() || apkName.isEmpty()) {
                    continue;
                }
                if (compareVersions(remoteVersion, currentVersion) <= 0) {
                    return null;
                }
                return new UpdateInfo(remoteVersion, apkName, source[0], source[2]);
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
     * 下载新版 APK 到 getExternalFilesDir(null)，使用命中源的 APK 完整地址。
     *
     * @return 下载完成后的 APK 文件
     * @throws IOException 网络 / IO 失败
     */
    public static File downloadApk(Context ctx, String apkUrl, String apkName, ProgressListener listener) throws IOException {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            throw new IOException("无法访问应用外部存储目录");
        }
        File target = new File(dir, apkName);
        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载失败，HTTP " + code);
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                long done = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (listener != null) {
                        listener.onProgress(done, total);
                    }
                }
                fos.flush();
            }
        } finally {
            conn.disconnect();
        }
        return target;
    }

    /** 经 FileProvider 启动系统安装器安装 APK，并记录待清理文件 */
    public static void installApk(Context ctx, File apk) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_PENDING_CLEANUP, apk.getAbsolutePath()).apply();
        ctx.startActivity(intent);
    }

    /** 安装启动后清理：下次进入 App 时删除此前已交给安装器的 APK（仅精确删除记录文件，无通配符） */
    public static void cleanupPending(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String path = sp.getString(KEY_PENDING_CLEANUP, null);
        if (path == null || path.isEmpty()) {
            return;
        }
        sp.edit().remove(KEY_PENDING_CLEANUP).apply();
        File f = new File(path);
        File dir = ctx.getExternalFilesDir(null);
        // 仅允许删除应用外部目录下的安装包，杜绝越界
        if (f.exists() && dir != null && f.getParentFile() != null
                && f.getParentFile().getAbsolutePath().equals(dir.getAbsolutePath())) {
            f.delete();
        }
    }

    /**
     * 版本号比较：按 . - _ 分段，每段取首个连续数字块；无数字段按 0。
     * "A9.14.0" > "A9.13.0"；"9.14" 与 "A9.14.0" 相等。
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

    /** 拆分版本号并提取每段首个数字块（容错 "A"、"v"、"beta" 等非数字段） */
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
            conn.setRequestProperty("User-Agent", "MimaVault-Android");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("更新源不可达，HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[4096];
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
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
