package com.mimavault.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 指纹/面部解锁支持：主密码登录成功后，将派生的 AES 密钥
 * 用 Android Keystore 硬件密钥（需生物识别认证）加密保存。
 * 之后解锁时通过 BiometricPrompt 认证 → Keystore 解密 → 恢复会话密钥。
 *
 * Keystore 密钥受系统级保护，App 卸载/清除数据后不可恢复。
 */
public final class BiometricHelper {

    private static final String TAG = "BiometricHelper";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mimavault_biometric_key";
    private static final String PREFS = "biometric_vault";
    private static final String P_ENC_KEY = "enc_key";
    private static final String P_SALT = "salt";
    private static final String P_ITER = "iter";
    private static final String P_IV = "iv";
    private static final int TAG_LENGTH = 128;

    private BiometricHelper() {
    }

    public static boolean isKeystoreAvailable() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            return ks.containsAlias(KEY_ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    /** 首次使用：创建仅本 App 可见、需生物识别的 AES 密钥 */
    public static boolean setup(Context context) {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                return true;
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                builder.setInvalidatedByBiometricEnrollment(true);
            }
            kg.init(builder.build());
            kg.generateKey();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Keystore 初始化失败", e);
            return false;
        }
    }

    /** 保存会话密钥（需在主密码验证成功后调用） */
    public static boolean storeVaultKey(Context context, byte[] vaultKeyBytes, String saltHex, int iterations) {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (!ks.containsAlias(KEY_ALIAS)) {
                if (!setup(context)) {
                    return false;
                }
                ks = KeyStore.getInstance(ANDROID_KEYSTORE);
                ks.load(null);
            }
            SecretKey secretKey = (SecretKey) ks.getKey(KEY_ALIAS, null);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] enc = cipher.doFinal(vaultKeyBytes);
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(P_ENC_KEY, Base64.encodeToString(enc, Base64.NO_WRAP))
                    .putString(P_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(P_SALT, saltHex)
                    .putInt(P_ITER, iterations)
                    .apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "保存密钥失败", e);
            return false;
        }
    }

    /** 是否有可用的生物识别恢复数据 */
    public static boolean hasStoredKey(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.contains(P_ENC_KEY) && isKeystoreAvailable();
    }

    /** 生物识别认证通过后调用：解密恢复会话密钥 */
    public static VaultKeyRecord decryptStoredKey(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encB64 = sp.getString(P_ENC_KEY, null);
        String ivB64 = sp.getString(P_IV, null);
        String saltHex = sp.getString(P_SALT, null);
        int iter = sp.getInt(P_ITER, 0);
        if (encB64 == null || ivB64 == null) {
            return null;
        }
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            SecretKey secretKey = (SecretKey) ks.getKey(KEY_ALIAS, null);
            if (secretKey == null) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(TAG_LENGTH, Base64.decode(ivB64, Base64.NO_WRAP)));
            byte[] vaultKeyBytes = cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP));
            return new VaultKeyRecord(vaultKeyBytes, saltHex, iter);
        } catch (Exception e) {
            Log.e(TAG, "解密恢复失败（密钥可能已失效）", e);
            return null;
        }
    }

    public static void clearStoredKey(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** 恢复结果：AES-256 密钥字节 + 派生参数 */
    public static final class VaultKeyRecord {
        public final SecretKey key;
        public final String saltHex;
        public final int iterations;

        VaultKeyRecord(byte[] keyBytes, String saltHex, int iterations) {
            this.key = new SecretKeySpec(keyBytes, "AES");
            this.saltHex = saltHex;
            this.iterations = iterations;
        }
    }
}
