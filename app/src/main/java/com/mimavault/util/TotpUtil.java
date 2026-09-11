package com.mimavault.util;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP（RFC 6238）离线动态验证码工具。
 *
 * 纯离线实现：不联网、不申请任何系统权限，密钥由调用方加密后存入本地库。
 * 能力：
 *  - 解析标准 otpauth://totp/... 链接（Google Authenticator / Authy / 1Password / 微软验证器等通用格式）
 *  - 支持直接粘贴 Base32 密钥手工绑定
 *  - 生成 6/7/8 位验证码；默认 30 秒周期；支持 SHA1 / SHA256 / SHA512
 *
 * 规范：HMAC 算法名形如 HmacSHA1 / HmacSHA256 / HmacSHA512，Android 平台内置支持，无需第三方库。
 */
public final class TotpUtil {

    public static final int DEFAULT_DIGITS = 6;
    public static final int DEFAULT_PERIOD = 30;
    public static final String DEFAULT_ALGORITHM = "SHA1";
    /** otpauth 只支持 totp 类型（hotp 为计数器型，不在本功能范围） */
    public static final String OTPAUTH_PREFIX = "otpauth://totp/";

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpUtil() {
    }

    /** TOTP 配置（secret 规范化为大写、无空格、无填充的 Base32） */
    public static final class Config {
        public String secret = "";
        public int digits = DEFAULT_DIGITS;
        public int period = DEFAULT_PERIOD;
        public String algorithm = DEFAULT_ALGORITHM;
        public String issuer = "";
        public String account = "";

        /** 规范化 otpauth 链接（加密后落库，保证参数可完整还原） */
        public String toUri() {
            return TotpUtil.buildUri(this);
        }

        /** 展示名：发行方 · 账号 */
        public String label() {
            String s = issuer == null ? "" : issuer.trim();
            String a = account == null ? "" : account.trim();
            if (!s.isEmpty() && !a.isEmpty()) {
                return s + " · " + a;
            }
            if (!s.isEmpty()) {
                return s;
            }
            if (!a.isEmpty()) {
                return a;
            }
            return "动态验证码";
        }
    }

    /**
     * 解析用户输入：支持 otpauth:// 链接，或直接粘贴的 Base32 密钥。
     *
     * @throws IllegalArgumentException 输入为空、类型不支持、密钥非法或过短
     */
    public static Config parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("内容为空");
        }
        String s = input.trim();
        Config c = new Config();

        if (s.toLowerCase(Locale.ROOT).startsWith("otpauth://")) {
            int q = s.indexOf('?');
            String head = q < 0 ? s : s.substring(0, q);
            String query = q < 0 ? "" : s.substring(q + 1);
            if (!head.toLowerCase(Locale.ROOT).startsWith(OTPAUTH_PREFIX)) {
                throw new IllegalArgumentException("仅支持 otpauth://totp/ 类型的链接");
            }
            String labelPart = urlDecode(head.substring(OTPAUTH_PREFIX.length()));
            int colon = labelPart.indexOf(':');
            if (colon >= 0) {
                c.issuer = labelPart.substring(0, colon).trim();
                c.account = labelPart.substring(colon + 1).trim();
            } else {
                c.account = labelPart.trim();
            }
            for (String kv : query.split("&")) {
                if (kv == null || kv.isEmpty()) {
                    continue;
                }
                int eq = kv.indexOf('=');
                String k = urlDecode(eq < 0 ? kv : kv.substring(0, eq)).toLowerCase(Locale.ROOT);
                String v = eq < 0 ? "" : urlDecode(kv.substring(eq + 1));
                switch (k) {
                    case "secret":
                        c.secret = v;
                        break;
                    case "issuer":
                        if (!v.trim().isEmpty()) {
                            c.issuer = v.trim();
                        }
                        break;
                    case "digits":
                        c.digits = parseIntOr(v, DEFAULT_DIGITS);
                        break;
                    case "period":
                        c.period = parseIntOr(v, DEFAULT_PERIOD);
                        break;
                    case "algorithm":
                        if (!v.trim().isEmpty()) {
                            c.algorithm = normalizeAlgorithm(v);
                        }
                        break;
                    default:
                        break;
                }
            }
        } else {
            c.secret = s;
        }

        c.secret = normalizeSecret(c.secret);
        if (c.secret.isEmpty()) {
            throw new IllegalArgumentException("缺少密钥（secret）");
        }
        byte[] key = decodeBase32(c.secret);
        if (key.length < 10) {
            throw new IllegalArgumentException("密钥过短，无法作为 TOTP 密钥");
        }
        if (c.digits < 6 || c.digits > 8) {
            c.digits = DEFAULT_DIGITS;
        }
        if (c.period < 10 || c.period > 300) {
            c.period = DEFAULT_PERIOD;
        }
        return c;
    }

    /** 规范化 Base32 密钥：去空格/连字符/填充符，统一大写 */
    public static String normalizeSecret(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .replace(" ", "")
                .replace("\t", "")
                .replace("-", "")
                .replace("=", "")
                .toUpperCase(Locale.ROOT);
    }

    /** 组装规范化 otpauth 链接 */
    public static String buildUri(Config c) {
        StringBuilder sb = new StringBuilder(OTPAUTH_PREFIX);
        String issuer = c.issuer == null ? "" : c.issuer.trim();
        String account = c.account == null ? "" : c.account.trim();
        String label = issuer.isEmpty() ? account : issuer + ":" + account;
        sb.append(urlEncode(label));
        sb.append("?secret=").append(c.secret);
        if (!issuer.isEmpty()) {
            sb.append("&issuer=").append(urlEncode(issuer));
        }
        if (c.digits != DEFAULT_DIGITS) {
            sb.append("&digits=").append(c.digits);
        }
        if (c.period != DEFAULT_PERIOD) {
            sb.append("&period=").append(c.period);
        }
        if (!DEFAULT_ALGORITHM.equals(c.algorithm)) {
            sb.append("&algorithm=").append(c.algorithm);
        }
        return sb.toString();
    }

    /** 生成当前时刻的验证码 */
    public static String generate(Config c, long timeMillis) {
        if (c == null) {
            return "";
        }
        long counter = (timeMillis / 1000L) / Math.max(1, c.period);
        return hotp(decodeBase32(c.secret), counter, c.digits, c.algorithm);
    }

    /** 生成指定口令（调试/校验用） */
    public static String generate(String secretBase32, int digits, int period, String algorithm, long timeMillis) {
        Config c = new Config();
        c.secret = normalizeSecret(secretBase32);
        c.digits = digits <= 0 ? DEFAULT_DIGITS : digits;
        c.period = period <= 0 ? DEFAULT_PERIOD : period;
        c.algorithm = normalizeAlgorithm(algorithm);
        return generate(c, timeMillis);
    }

    /** 当前周期剩余秒数（1 ~ period） */
    public static int remainingSeconds(int period, long timeMillis) {
        int p = period <= 0 ? DEFAULT_PERIOD : period;
        long sec = timeMillis / 1000L;
        int rem = (int) (p - (sec % p));
        return rem <= 0 ? p : rem;
    }

    // ---------- 内部实现 ----------

    private static String hotp(byte[] key, long counter, int digits, String algorithm) {
        try {
            String algo = "Hmac" + normalizeAlgorithm(algorithm);
            byte[] data = new byte[8];
            long v = counter;
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (v & 0xFF);
                v >>>= 8;
            }
            Mac mac = Mac.getInstance(algo);
            mac.init(new SecretKeySpec(key, algo));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int mod = 1;
            for (int i = 0; i < digits; i++) {
                mod *= 10;
            }
            StringBuilder sb = new StringBuilder(String.valueOf(binary % mod));
            while (sb.length() < digits) {
                sb.insert(0, '0');
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("验证码生成失败", e);
        }
    }

    /** Base32 解码（RFC 4648，忽略填充与空白） */
    public static byte[] decodeBase32(String s) {
        String t = normalizeSecret(s);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("密钥为空");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < t.length(); i++) {
            int idx = BASE32_ALPHABET.indexOf(t.charAt(i));
            if (idx < 0) {
                throw new IllegalArgumentException("非法的 Base32 字符：" + t.charAt(i));
            }
            buffer = (buffer << 5) | idx;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static String normalizeAlgorithm(String algo) {
        if (algo == null) {
            return DEFAULT_ALGORITHM;
        }
        String a = algo.trim().toUpperCase(Locale.ROOT).replace("-", "");
        if ("SHA256".equals(a) || "SHA512".equals(a)) {
            return a;
        }
        return DEFAULT_ALGORITHM;
    }

    private static int parseIntOr(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }
}
