package com.mimavault.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 常见弱密码黑名单（离线资源，随 jar 打包）
 *
 * <p>从 /common_passwords.txt 加载约 1100 条高频弱密码明文，
 * 仅供密码健康检测在内存中比对使用；不联网、不落盘、不打印。
 */
public final class CommonPasswords {

    private static final Set<String> WORDS = load();

    private CommonPasswords() {
    }

    private static Set<String> load() {
        Set<String> set = new HashSet<>();
        try (InputStream in = CommonPasswords.class.getResourceAsStream("/common_passwords.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String w = line.trim();
                if (!w.isEmpty()) {
                    set.add(w);
                }
            }
        } catch (IOException | NullPointerException e) {
            // 资源缺失时降级为空集合，不影响主流程
        }
        return Collections.unmodifiableSet(set);
    }

    /** 明文密码是否命中常见弱密码表 */
    public static boolean contains(String password) {
        return password != null && WORDS.contains(password);
    }

    /** 弱密码表条目数（约1100） */
    public static int size() {
        return WORDS.size();
    }
}
