package com.mimavault.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 人脸解锁支持（OpenCV 摄像头自研，Android 厂商人脸不对第三方开放时使用）。
 *
 * 安全模型与手势密码对称：
 * - 设置 / 重录人脸前必须先验证主密码（由调用方 MainActivity.verifyMasterThen 保证）；
 * - 设置时把「vaultKey + 派生参数 + 人脸模板(96x96 灰度 png)」用 Keystore 设备密钥加密后持久化；
 * - Keystore 密钥不可导出，密文被整体拷贝到其它设备后无法离线解密，防止本地数据被搬运攻击；
 * - 卸载 / 清除数据后 Keystore 密钥销毁，人脸与恢复数据一并失效；
 * - 解锁时需实时摄像头人脸比对通过，才解密出 vaultKey 恢复会话（人脸模板不存储明文）。
 *
 * 失败保护：连续比对失败达到上限后锁定人脸通道，本次解锁周期仅能使用主密码 /
 * 手势 / 系统生物识别，成功解锁后自动复位。
 */
public final class FaceUnlockHelper {

    private static final String TAG = "FaceUnlockHelper";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mimavault_face_key";
    private static final String PREFS = "face_unlock";
    private static final String P_VERSION = "version";
    private static final String P_ENC = "enc";
    private static final String P_FAILS = "fails";
    private static final String CURRENT_VERSION = "v1";

    /** 人脸比对连续失败达到该次数后，锁定人脸通道，只能主密码 / 手势 / 系统生物识别解锁 */
    public static final int MAX_ATTEMPTS = 5;

    /** payload 行分隔 */
    private static final int IDX_SALT = 0;
    private static final int IDX_ITER = 1;
    private static final int IDX_KEY = 2;
    private static final int IDX_TPL = 3;

    private FaceUnlockHelper() {
    }

    /** 是否已录入人脸模板 */
    public static boolean isFaceSet(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return CURRENT_VERSION.equals(sp.getString(P_VERSION, null)) && sp.contains(P_ENC) && hasDeviceKey();
    }

    /** 设备 Keystore 密钥是否仍在 */
    private static boolean hasDeviceKey() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            return ks.containsAlias(KEY_ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    /** 确保设备绑定密钥存在（无需生物认证的普通 Keystore AES 密钥，仅用于本地静态加密防拷贝） */
    private static SecretKey ensureDeviceKey() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                return (SecretKey) ks.getKey(KEY_ALIAS, null);
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            SecretKey generated = kg.generateKey();
            Log.i(TAG, "device keystore key created");
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("Keystore 设备密钥初始化失败", e);
        }
    }

    /**
     * 录入 / 重录人脸（必须在主密码已验证、会话打开后调用）。
     *
     * @param faceTemplatePng 人脸模板 PNG 字节（由 FaceEngine 提取，96x96 灰度）
     * @param vaultKeyBytes   当前会话 vault key（VaultSession.get().key().getEncoded()）
     * @param saltHex         主密码 PBKDF2 盐（hex）
     * @param iterations      主密码 PBKDF2 迭代次数
     */
    public static void setup(Context context, byte[] faceTemplatePng, byte[] vaultKeyBytes,
                             String saltHex, int iterations) throws Exception {
        if (faceTemplatePng == null || faceTemplatePng.length == 0) {
            throw new IllegalArgumentException("face template empty");
        }
        if (vaultKeyBytes == null || vaultKeyBytes.length == 0) {
            throw new IllegalArgumentException("vault key bytes is empty");
        }
        SecretKey deviceKey = ensureDeviceKey();
        String payload = saltHex + "\n" + iterations + "\n"
                + Base64.getEncoder().encodeToString(vaultKeyBytes) + "\n"
                + Base64.getEncoder().encodeToString(faceTemplatePng);
        String enc = aesEncrypt(payload, deviceKey);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(P_VERSION, CURRENT_VERSION)
                .putString(P_ENC, enc)
                .apply();
        Log.i(TAG, "face unlock saved, template bytes=" + faceTemplatePng.length);
    }

    /** 清除人脸模板与恢复数据 */
    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        Log.i(TAG, "face unlock cleared");
    }

    /** 当前连续失败次数 */
    public static int getFails(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(P_FAILS, 0);
    }

    /** 记录一次人脸解锁失败，返回失败后是否已锁定 */
    public static boolean increaseFails(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int fails = sp.getInt(P_FAILS, 0) + 1;
        sp.edit().putInt(P_FAILS, fails).apply();
        Log.i(TAG, "face unlock failed, fails=" + fails);
        return fails >= MAX_ATTEMPTS;
    }

    /** 主密码 / 手势 / 系统生物识别解锁成功后重置失败计数 */
    public static void resetFails(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getInt(P_FAILS, 0) != 0) {
            sp.edit().putInt(P_FAILS, 0).apply();
            Log.i(TAG, "face unlock fails reset");
        }
    }

    /** 人脸通道是否因连续失败过多而被锁定（锁定后只能主密码 / 手势 / 系统生物识别） */
    public static boolean isLocked(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(P_FAILS, 0) >= MAX_ATTEMPTS;
    }

    /**
     * 读取人脸恢复数据（含模板 PNG 与 vaultKey）。
     * 设备绑定 Keystore 密钥可正常解密即数据完整；
     * 人脸正确性由调用方通过 FaceEngine 比对分数判定，解密本身不代表人脸通过。
     */
    public static FaceRecord load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String enc = sp.getString(P_ENC, null);
        if (enc == null) {
            return null;
        }
        try {
            SecretKey deviceKey = ensureDeviceKey();
            String payload = aesDecrypt(enc, deviceKey);
            String[] parts = payload.split("\n", 4);
            if (parts.length != 4) {
                Log.w(TAG, "face payload corrupted");
                return null;
            }
            byte[] keyBytes = Base64.getDecoder().decode(parts[IDX_KEY]);
            byte[] tplPng = Base64.getDecoder().decode(parts[IDX_TPL]);
            if (keyBytes.length == 0 || tplPng.length == 0) {
                return null;
            }
            return new FaceRecord(tplPng, keyBytes, parts[IDX_SALT], Integer.parseInt(parts[IDX_ITER]));
        } catch (Exception e) {
            Log.e(TAG, "face payload load failed", e);
            return null;
        }
    }

    private static String aesEncrypt(String plain, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] enc = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(enc, 0, combined, iv.length, enc.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private static String aesDecrypt(String encB64, SecretKey key) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encB64);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, combined, 0, 12));
        byte[] plain = cipher.doFinal(combined, 12, combined.length - 12);
        return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 恢复结果：人脸模板 PNG + AES-256 密钥 + 主密码派生参数 */
    public static final class FaceRecord {
        public final byte[] templatePng;
        public final SecretKey key;
        public final String saltHex;
        public final int iterations;

        FaceRecord(byte[] templatePng, byte[] keyBytes, String saltHex, int iterations) {
            this.templatePng = templatePng;
            this.key = new SecretKeySpec(keyBytes, "AES");
            this.saltHex = saltHex;
            this.iterations = iterations;
        }
    }
}
