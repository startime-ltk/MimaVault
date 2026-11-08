package com.mimavault.api;

import com.mimavault.model.Entry;
import com.mimavault.util.AesUtil;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link Entry} 投影成"可以安全交给外部调用方"的元数据视图。
 *
 * <p><b>这是本地 API 的唯一出口白名单。</b>任何新增字段都必须先在这里判断是否可外泄，
 * 绝不允许直接把 {@link Entry} 序列化成 JSON —— Entry 里有 {@code passwordEnc}、
 * {@code totpSecretEnc}、{@code note}（用户可能手写恢复码）等敏感明文/密文。
 *
 * <p>不可外泄的字段及处理方式：
 * <ul>
 *   <li>{@code passwordEnc} → 只回 {@code hasPassword} 布尔；明文仅由 /copy、/secret 端点按需取用</li>
 *   <li>{@code totpSecretEnc} → 只回 {@code hasTotp}；otpauth 链接本身等价于长期密钥，不外泄</li>
 *   <li>{@code note} → 只回 {@code noteLength}（搜索仍可命中内容，但内容不出库）</li>
 *   <li>{@code extraEnc} 中的密钥类字段 → 打码为 {@code "***"}，只表示"有值"</li>
 *   <li>{@code gestureSeq} / {@code imagePath} → 完全不回</li>
 * </ul>
 */
public final class ApiEntryView {

    /**
     * 分类专属字段里的"本身就是密钥/凭据"的键名。
     * 这些键的值等价于密码，一律打码，只表明存在。
     */
    private static final Set<String> SECRET_EXTRA_KEYS = new HashSet<>(Arrays.asList(
            "api_key",
            "bank_cvv",
            "bank_card_no",
            "id_number"));

    /** 打码后占位符 */
    public static final String MASK = "***";

    private ApiEntryView() {
    }

    /** 解密条目专属字段；无值/解密失败返回空 Map（解不开不影响元数据返回） */
    public static Map<String, String> extraOf(Entry e, SecretKey key) {
        if (e == null || e.getExtraEnc() == null || e.getExtraEnc().isEmpty() || key == null) {
            return new LinkedHashMap<>();
        }
        try {
            String json = AesUtil.decrypt(e.getExtraEnc(), key);
            Map<String, String> map = Entry.decodeExtra(json);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    /** 该键是否属于"值即密钥"的范畴 */
    public static boolean isSecretExtraKey(String k) {
        return k != null && SECRET_EXTRA_KEYS.contains(k);
    }

    /** 所有会被打码的键名（供调用方判断某字段需走 /secret 端点取值） */
    public static Set<String> secretExtraKeys() {
        return java.util.Collections.unmodifiableSet(SECRET_EXTRA_KEYS);
    }

    /**
     * 单条目的可外泄元数据。
     *
     * @param e   条目
     * @param key 当前会话密钥（可为 null：此时 extra 视为空，其余元数据仍可返回）
     * @return 有序 Map，直接交给 Gson 序列化
     */
    public static Map<String, Object> of(Entry e, SecretKey key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("category", nz(e.getCategory()));
        m.put("platform", nz(e.getPlatform()));
        m.put("account", nz(e.getAccount()));
        m.put("email", nz(e.getEmail()));
        m.put("phone", nz(e.getPhone()));
        m.put("hasPassword", notEmpty(e.getPasswordEnc()));
        m.put("hasTotp", notEmpty(e.getTotpSecretEnc()));
        m.put("hasImage", notEmpty(e.getImagePath()));
        m.put("favorite", e.isFavorite());
        // 备注内容不外泄，只给长度：模型可据此判断"这条有说明"，但拿不到内容
        m.put("noteLength", e.getNote() == null ? 0 : e.getNote().length());
        m.put("updatedAt", e.getUpdatedAt() == null ? 0L : e.getUpdatedAt().getTime());

        Map<String, Object> extraOut = new LinkedHashMap<>();
        for (Map.Entry<String, String> kv : extraOf(e, key).entrySet()) {
            String k = kv.getKey();
            String v = kv.getValue();
            if (v == null || v.isEmpty()) {
                continue;
            }
            extraOut.put(k, isSecretExtraKey(k) ? MASK : v);
        }
        m.put("extra", extraOut);
        return m;
    }

    /** 批量投影 */
    public static List<Map<String, Object>> ofAll(Collection<Entry> entries, SecretKey key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (Entry e : entries) {
            if (e != null) {
                out.add(of(e, key));
            }
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
