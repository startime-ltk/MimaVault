package com.mimavault.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 高频泄露密码 SHA-256 哈希库（离线资源，随 jar 打包）
 *
 * <p>从 /breached_passwords.txt 加载约 90 条高频泄露密码的 SHA-256 摘要（每行一个 64 位 hex）。
 * 资源文件只存哈希不存明文；检测时对明文密码计算 SHA-256 后在内存中比对。
 * 全程零联网、不落盘、不打印明文。
 */
public final class BreachedPasswordHashes {

    private static final Set<String> HASHES = load();

    private BreachedPasswordHashes() {
    }

    private static Set<String> load() {
        Set<String> set = new HashSet<>();
        try (InputStream in = BreachedPasswordHashes.class.getResourceAsStream("/breached_passwords.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String h = line.trim().toLowerCase();
                if (h.length() == 64) {
                    set.add(h);
                }
            }
        } catch (IOException | NullPointerException e) {
            // 资源缺失时降级为空集合，不影响主流程
        }
        return Collections.unmodifiableSet(set);
    }

    /** 计算密码 SHA-256 hex（小写） */
    public static String sha256Hex(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 明文密码是否命中离线泄露哈希库 */
    public static boolean contains(String password) {
        return password != null && !password.isEmpty() && HASHES.contains(sha256Hex(password));
    }

    /** 哈希库条目数（约90） */
    public static int size() {
        return HASHES.size();
    }
}
