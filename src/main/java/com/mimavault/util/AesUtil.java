package com.mimavault.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密工具
 *
 * 密钥派生：
 * - 新版：PBKDF2WithHmacSHA256（盐 16 字节，迭代 210000，输出 256 位），
 *   盐+哈希存储格式为 pbkdf2$迭代次数$盐hex$哈希hex（settings.master_hash），
 *   迭代次数自描述：与安卓端统一为同一档位，历史库（600000 档 / 旧 SHA-256）解锁后可正常打开
 * - 旧版（兼容迁移）：主密码直接 SHA-256 派生（仅用于旧数据一次性迁移）
 *
 * GCM 模式自带完整性校验，密文格式：Base64(iv + ciphertext)
 */
public final class AesUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;      // GCM 推荐 96 位 IV
    private static final int TAG_LENGTH = 128;    // GCM 认证标签 128 位

    public static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    public static final int PBKDF2_SALT_LENGTH = 16;   // 盐 16 字节
    // 与安卓端统一档位：210000（OWASP 2021 推荐档），
    // record 为自描述格式(pbkdf2$iter$salt$hash)，600000 老库验证仍走记录内档位，
    // 解锁成功后由 PasswordService 自动迁移重加密至本档位，保证双端密库互通。
    public static final int PBKDF2_ITERATIONS = 210000; // 慢哈希迭代次数
    public static final int PBKDF2_KEY_LENGTH = 256;    // 输出 256 位
    public static final String PBKDF2_PREFIX = "pbkdf2$";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AesUtil() {
    }

    // ---------- PBKDF2 密钥派生（新方案） ----------

    /** 生成随机盐（16 字节） */
    public static byte[] generateSalt() {
        byte[] salt = new byte[PBKDF2_SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }

    /** 由主密码经 PBKDF2 派生 AES-256 密钥（盐+迭代次数显式指定） */
    public static SecretKey deriveKeyPbkdf2(char[] masterPassword, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(masterPassword, salt, iterations, PBKDF2_KEY_LENGTH);
            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
                byte[] derived = factory.generateSecret(spec).getEncoded();
                return new SecretKeySpec(derived, ALGORITHM);
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 密钥派生失败", e);
        }
    }

    /** PBKDF2 派生原始字节（用于存储校验哈希） */
    public static byte[] pbkdf2Bytes(char[] masterPassword, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(masterPassword, salt, iterations, PBKDF2_KEY_LENGTH);
            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
                return factory.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 哈希失败", e);
        }
    }

    /** 生成新格式存储串：pbkdf2$迭代次数$盐hex$哈希hex */
    public static String buildPbkdf2Record(char[] masterPassword, byte[] salt, int iterations) {
        byte[] hash = pbkdf2Bytes(masterPassword, salt, iterations);
        return PBKDF2_PREFIX + iterations + "$" + toHex(salt) + "$" + toHex(hash);
    }

    /**
     * 校验主密码：自动识别新/旧存储格式
     * 返回 true 表示密码正确（调用方需根据 isLegacyRecord 决定是否迁移）
     */
    public static boolean verifyPassword(char[] masterPassword, String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        if (stored.startsWith(PBKDF2_PREFIX)) {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = fromHex(parts[2]);
                byte[] expected = fromHex(parts[3]);
                byte[] actual = pbkdf2Bytes(masterPassword, salt, iterations);
                return MessageDigest.isEqual(expected, actual);
            } catch (Exception e) {
                return false;
            }
        }
        // 旧格式：纯 64 位 hex（SHA-256）
        return constantTimeEquals(stored.toLowerCase(), sha256Hex(new String(masterPassword)));
    }

    /** 是否为旧版 SHA-256 格式（需要一次性迁移） */
    public static boolean isLegacyRecord(String stored) {
        return stored != null && !stored.isEmpty() && !stored.startsWith(PBKDF2_PREFIX);
    }

    /** 从存储串解析盐（仅新格式） */
    public static byte[] saltFromRecord(String stored) {
        if (stored == null || !stored.startsWith(PBKDF2_PREFIX)) {
            return null;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return null;
        }
        return fromHex(parts[2]);
    }

    /** 从存储串解析迭代次数（仅新格式） */
    public static int iterationsFromRecord(String stored) {
        if (stored == null || !stored.startsWith(PBKDF2_PREFIX)) {
            return PBKDF2_ITERATIONS;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return PBKDF2_ITERATIONS;
        }
        return Integer.parseInt(parts[1]);
    }

    // ---------- 旧版派生（兼容迁移用） ----------

    /**
     * 【旧版】由主密码经 SHA-256 派生 AES-256 密钥
     * 仅用于旧数据一次性迁移与备份导入兼容，新库请使用 deriveKeyPbkdf2
     */
    public static SecretKey deriveKey(String masterPassword) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(masterPassword.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }

    /** 【旧版】SHA-256 十六进制哈希（仅旧格式校验使用） */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("哈希计算失败", e);
        }
    }

    // ---------- AES-256-GCM 加解密（新旧共用） ----------

    /** 加密明文，返回 Base64(iv + ciphertext) */
    public static String encrypt(String plainText, SecretKey key) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
            try {
                byte[] encrypted = cipher.doFinal(plainBytes);
                byte[] combined = new byte[iv.length + encrypted.length];
                System.arraycopy(iv, 0, combined, 0, iv.length);
                System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
                return Base64.getEncoder().encodeToString(combined);
            } finally {
                java.util.Arrays.fill(plainBytes, (byte) 0); // 明文字节用完即清
            }
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密 Base64(iv + ciphertext) */
    public static String decrypt(String cipherText, SecretKey key) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            try {
                byte[] iv = new byte[IV_LENGTH];
                System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
                byte[] encrypted = new byte[combined.length - IV_LENGTH];
                System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
                byte[] decrypted = cipher.doFinal(encrypted);
                try {
                    return new String(decrypted, StandardCharsets.UTF_8);
                } finally {
                    java.util.Arrays.fill(decrypted, (byte) 0); // 解密字节用完即清
                }
            } finally {
                java.util.Arrays.fill(combined, (byte) 0);
            }
        } catch (Exception e) {
            throw new IllegalStateException("解密失败，主密码可能不正确", e);
        }
    }

    // ---------- 工具 ----------

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 十六进制字符串转字节数组（供备份文件 header 解析等外部使用） */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] fromHex(String hex) {
        return hexToBytes(hex);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
