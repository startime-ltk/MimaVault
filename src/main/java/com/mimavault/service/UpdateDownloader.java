package com.mimavault.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * 更新包下载与完整性校验。
 *
 * <p>负责三件事：把安装包下到本地、算出它的 SHA-256、和更新清单里声明的值比对。
 * 不做任何"执行"动作 —— 是否运行安装程序由 {@link UpdateInstaller} 决定。
 *
 * <p><b>安全边界</b>：SHA-256 只能证明"下到的字节和清单说的一致"，
 * 证明不了"这份清单本身可信"。清单和安装包走的是同一批更新源，
 * 源被拿下时两者会一起被替换。要真正防住这一点需要发版方非对称签名。
 */
public final class UpdateDownloader {

    /** 连接超时 */
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    /** 单次读取超时（大文件慢速下载时按"两次读到数据之间"计） */
    private static final int READ_TIMEOUT_MS = 30_000;

    /** 体积上限保护：安装包约 70MB，留足余量但拒绝异常超大响应 */
    private static final long MAX_BYTES = 400L * 1024 * 1024;

    /** 每读多少个字节回报一次进度（约 256KB） */
    private static final int PROGRESS_STEP = 256 * 1024;

    /** 下载进度回调 */
    public interface Progress {
        /**
         * @param downloaded 已下载字节数
         * @param total      总字节数；{@code <= 0} 表示服务端未提供 Content-Length
         */
        void onProgress(long downloaded, long total);
    }

    private UpdateDownloader() {
    }

    /**
     * 下载到 {@code targetDir/fileName}（已存在则覆盖）。
     *
     * @param rawUrl   下载地址，必须是 http/https
     * @param targetDir 目标目录（不存在会自动创建）
     * @param fileName 目标文件名（会被清洗掉路径分隔符）
     * @param progress 进度回调，可为 null
     * @param cancelled 取消判定，可为 null；返回 true 时中止并抛 {@link CancelledException}
     * @return 下载完成的本地文件
     */
    public static Path download(String rawUrl, Path targetDir, String fileName,
                                Progress progress, BooleanSupplier cancelled) throws IOException {
        requireHttpUrl(rawUrl);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(sanitizeFileName(fileName));
        Path part = target.resolveSibling(target.getFileName() + ".part");

        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MimaVault-PC-updater");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载失败，HTTP " + code + "（" + rawUrl + "）");
            }
            long total = conn.getContentLengthLong();
            if (total > MAX_BYTES) {
                throw new IOException("安装包体积异常（" + total + " 字节），已中止");
            }

            long downloaded = 0;
            int sinceReport = 0;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new CancelledException();
                    }
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (downloaded > MAX_BYTES) {
                        throw new IOException("安装包体积异常（已超过 " + MAX_BYTES + " 字节），已中止");
                    }
                    sinceReport += n;
                    if (progress != null && sinceReport >= PROGRESS_STEP) {
                        sinceReport = 0;
                        progress.onProgress(downloaded, total);
                    }
                }
            } catch (IOException e) {
                Files.deleteIfExists(part);
                throw e;
            }
            if (progress != null) {
                progress.onProgress(downloaded, total > 0 ? total : downloaded);
            }
            if (total > 0 && downloaded != total) {
                Files.deleteIfExists(part);
                throw new IOException("下载不完整：期望 " + total + " 字节，实际 " + downloaded + " 字节");
            }
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(part);
                throw e;
            }
            return target;
        } finally {
            conn.disconnect();
        }
    }

    /** 计算文件 SHA-256（64 位小写 hex） */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 JVM 不支持 SHA-256", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 校验文件 SHA-256。
     *
     * @param file        待校验文件
     * @param expectedHex 期望值；null/空表示未提供校验值，本方法直接返回 true（调用方负责提示）
     * @return 是否一致
     */
    public static boolean verifySha256(Path file, String expectedHex) throws IOException {
        if (expectedHex == null || expectedHex.trim().isEmpty()) {
            return true;
        }
        String actual = sha256Hex(file);
        return actual.equalsIgnoreCase(expectedHex.trim());
    }

    /** 只允许 http/https，避免 file:// 之类的本地路径被塞进更新清单 */
    private static void requireHttpUrl(String rawUrl) throws IOException {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IOException("下载地址为空");
        }
        String lower = rawUrl.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IOException("下载地址协议不受支持：" + rawUrl);
        }
    }

    /** 清掉路径分隔符与 .. ，避免更新清单里的文件名写到预期目录之外 */
    public static String sanitizeFileName(String raw) {
        String name = raw == null ? "" : raw.trim();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }
        name = name.replace("..", "_").replaceAll("[\\r\\n\\t]", "");
        if (name.isEmpty()) {
            name = "MimaVault-Setup.exe";
        }
        return name;
    }

    /** 用户取消下载 */
    public static final class CancelledException extends IOException {
        CancelledException() {
            super("已取消下载");
        }
    }
}
