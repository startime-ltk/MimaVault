package com.passwordmaster.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密工具
 * 密钥由主密码经 SHA-256 派生（32 字节 = 256 位）
 * GCM 模式自带完整性校验，密文格式：Base64(iv + ciphertext)
 */
public final class AesUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;      // GCM 推荐 96 位 IV
    private static final int TAG_LENGTH = 128;    // GCM 认证标签 128 位

    private AesUtil() {
    }

    /** 由主密码派生 AES-256 密钥 */
    public static SecretKey deriveKey(String masterPassword) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(masterPassword.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }

    /** 加密明文，返回 Base64(iv + ciphertext) */
    public static String encrypt(String plainText, SecretKey key) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密 Base64(iv + ciphertext) */
    public static String decrypt(String cipherText, SecretKey key) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败，主密码可能不正确", e);
        }
    }

    /** 计算字符串的 SHA-256 十六进制哈希（用于主密码校验） */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("哈希计算失败", e);
        }
    }
}
