package com.mimavault.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.mimavault.util.AesUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * 手势解锁支持（"主密码为主、手势密码可选"登录）。
 *
 * 安全模型与生物识别对称：设置 / 修改手势前必须先验证主密码（由调用方保证），
 * 设置时将「vaultKey + 派生参数」用「手势派生密钥」加密后持久化，
 * 解锁时输入手势 → 尝试解密 → 成功即恢复会话密钥并进入主界面。
 *
 * 手势派生密钥 = SHA-256("mimavault:gesture:" + ANDROID_ID + ":" + 手势序列)，
 * 叠加设备标识可防止本地密文被整体拷贝到其它设备后离线穷举。
 * 存储位置：SharedPreferences（与条目、生物识别恢复数据同为 App 私有数据）。
 *
 * 注意：本类不存储手势明文，也不做“哈希校验”，只保存密文；
 * 密文能解开即手势正确，避免存两份可比对数据。
 */
public final class GestureUnlockHelper {

    private static final String TAG = "GestureUnlockHelper";
    private static final String PREFS = "gesture_unlock";
    private static final String P_VERSION = "version";
    private static final String P_ENC = "enc";
    private static final String P_FAILS = "fails";
    private static final String CURRENT_VERSION = "v1";

    /** 手势连续错误达到该次数后，本次解锁周期内只能使用主密码（或生物识别）解锁 */
    public static final int MAX_ATTEMPTS = 3;

    private GestureUnlockHelper() {
    }

    /** 是否已设置过手势密码 */
    public static boolean isGestureSet(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return CURRENT_VERSION.equals(sp.getString(P_VERSION, null)) && sp.contains(P_ENC);
    }

    /**
     * 设置 / 修改手势密码（必须在主密码已验证、会话打开后调用）。
     *
     * @param seq            手势序列（如 "1,2,5,8,9"，需调用方先经 GestureParser.valid 校验）
     * @param vaultKeyBytes  当前会话 vault key（VaultSession.get().key().getEncoded()）
     * @param saltHex        主密码 PBKDF2 盐（hex）
     * @param iterations     主密码 PBKDF2 迭代次数
     */
    public static void setup(Context context, String seq, byte[] vaultKeyBytes,
                             String saltHex, int iterations) {
        if (vaultKeyBytes == null || vaultKeyBytes.length == 0) {
            throw new IllegalArgumentException("vault key bytes is empty");
        }
        String payload = saltHex + "\n" + iterations + "\n"
                + Base64.getEncoder().encodeToString(vaultKeyBytes);
        String enc = AesUtil.encrypt(payload, gestureKey(context, seq));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(P_VERSION, CURRENT_VERSION)
                .putString(P_ENC, enc)
                .apply();
        Log.i(TAG, "gesture unlock saved");
    }

    /**
     * 用输入的手势尝试解锁。
     *
     * @return 成功返回恢复结果（密钥 + 派生参数）；手势错误 / 未设置 / 数据异常返回 null
     */
    public static Result tryUnlock(Context context, String seq) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String enc = sp.getString(P_ENC, null);
        if (enc == null) {
            return null;
        }
        try {
            String payload = AesUtil.decrypt(enc, gestureKey(context, seq));
            String[] parts = payload.split("\n", 3);
            if (parts.length != 3) {
                Log.w(TAG, "gesture payload corrupted");
                return null;
            }
            String saltHex = parts[0];
            int iterations = Integer.parseInt(parts[1]);
            byte[] keyBytes = Base64.getDecoder().decode(parts[2]);
            if (keyBytes.length == 0) {
                return null;
            }
            return new Result(keyBytes, saltHex, iterations);
        } catch (Exception e) {
            // 解密失败（GCM 校验不过）即手势错误
            return null;
        }
    }

    /** 清除手势密码 */
    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** 当前连续错误次数 */
    public static int getFails(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(P_FAILS, 0);
    }

    /** 记录一次手势解锁失败，并返回失败后是否已锁定 */
    public static boolean increaseFails(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int fails = sp.getInt(P_FAILS, 0) + 1;
        sp.edit().putInt(P_FAILS, fails).apply();
        Log.i(TAG, "gesture unlock failed, fails=" + fails);
        return fails >= MAX_ATTEMPTS;
    }

    /** 主密码 / 生物识别解锁成功后重置失败计数 */
    public static void resetFails(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getInt(P_FAILS, 0) != 0) {
            sp.edit().putInt(P_FAILS, 0).apply();
            Log.i(TAG, "gesture unlock fails reset");
        }
    }

    /** 手势是否因连续错误次数过多而被锁定（锁定后只能主密码 / 生物识别解锁） */
    public static boolean isLocked(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(P_FAILS, 0) >= MAX_ATTEMPTS;
    }

    private static SecretKey gestureKey(Context context, String seq) {
        String device = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        if (device == null) {
            device = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("mimavault:gesture:" + device + ":" + seq)
                    .getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("手势密钥派生失败", e);
        }
    }

    /** 恢复结果：AES-256 密钥 + 主密码派生参数 */
    public static final class Result {
        public final SecretKey key;
        public final String saltHex;
        public final int iterations;

        Result(byte[] keyBytes, String saltHex, int iterations) {
            this.key = new SecretKeySpec(keyBytes, "AES");
            this.saltHex = saltHex;
            this.iterations = iterations;
        }
    }
}
