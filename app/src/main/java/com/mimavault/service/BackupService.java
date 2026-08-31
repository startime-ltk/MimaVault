package com.mimavault.service;

import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimavault.config.AppConfig;
import com.mimavault.util.AesUtil;
import com.mimavault.model.BackupModel;
import com.mimavault.model.Entry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;

/**
 * .pmaster 备份导入导出，与 PC 端 BackupUtil 1:1 兼容
 *
 * 文件格式（v2）：
 *   新格式：MimaVault1$盐hex$迭代次数$Base64密文
 *   旧格式：纯 Base64 密文（兼容）
 * 导入按 ① header PBKDF2 → ② 当前库密钥 → ③ 旧版 SHA-256 三通道尝试
 */
public final class BackupService {

    public static final String HEADER_PREFIX = "MimaVault1$";
    private static final String VERSION = "1";

    /** Gson 序列化：PC 端 Gson 默认 Date 格式为 "MMM d, yyyy, h:mm:ss a"（含 U+202F 窄空格），需兼容解析 */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Date.class, new com.google.gson.JsonDeserializer<Date>() {
                private final java.text.SimpleDateFormat[] FORMATS = {
                        new java.text.SimpleDateFormat("MMM d, yyyy, h:mm:ss a", java.util.Locale.US),
                        new java.text.SimpleDateFormat("MMM d, yyyy, h:mm:ss a", java.util.Locale.ENGLISH),
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US),
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US),
                };

                @Override
                public Date deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type type,
                                        com.google.gson.JsonDeserializationContext context) throws com.google.gson.JsonParseException {
                    String s = json.getAsString().replace('\u202F', ' ').replace('\u00A0', ' ').trim();
                    for (java.text.SimpleDateFormat f : FORMATS) {
                        try {
                            return f.parse(s);
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        return new Date(Long.parseLong(s));
                    } catch (Exception ignored) {
                    }
                    throw new com.google.gson.JsonParseException("无法解析日期: " + s);
                }
            })
            .create();

    private BackupService() {
    }

    /** 生成 .pmaster 文件内容（UTF-8 文本），由调用方写入文件/生成二维码 */
    public static String buildExportContent(List<Entry> entries, SecretKey key, String saltHex, int iterations) {
        BackupModel.BackupPackage pack = new BackupModel.BackupPackage();
        pack.version = VERSION;
        pack.exportedAt = System.currentTimeMillis();
        for (Entry e : entries) {
            BackupModel.BackupItem item = new BackupModel.BackupItem();
            item.category = e.getCategory();
            item.platform = e.getPlatform();
            item.account = e.getAccount();
            item.password = e.getPasswordEnc() == null ? null : "encrypted:" + e.getPasswordEnc();
            item.phone = e.getPhone();
            item.email = e.getEmail();
            item.note = e.getNote();
            item.gestureSeq = e.getGestureSeq();
            item.syncStatus = e.getSyncStatus();
            item.createdAt = e.getCreatedAt();
            item.updatedAt = e.getUpdatedAt();
            if (e.getImagePath() != null && !e.getImagePath().isEmpty()) {
                File img = AppConfig.resolveImagePath(e.getImagePath());
                if (img != null && img.exists()) {
                    byte[] bytes = readAllBytes(img);
                    if (bytes != null) {
                        item.imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                        item.imageName = new File(e.getImagePath()).getName();
                    }
                }
            }
            pack.items.add(item);
        }
        String json = GSON.toJson(pack);
        String encrypted = AesUtil.encrypt(json, key);
        if (saltHex != null && !saltHex.isEmpty()) {
            return HEADER_PREFIX + saltHex + "$" + iterations + "$" + encrypted;
        }
        return encrypted;
    }

    /** 导出为文件 */
    public static void exportToFile(List<Entry> entries, SecretKey key, String saltHex, int iterations, File target) throws IOException {
        String content = buildExportContent(entries, key, saltHex, iterations);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 导入结果：解密后的备份包 + 解密所用密钥（用于条目密码重加密） */
    public static final class ImportResult {
        public final BackupModel.BackupPackage pack;
        public final SecretKey backupKey;

        ImportResult(BackupModel.BackupPackage pack, SecretKey backupKey) {
            this.pack = pack;
            this.backupKey = backupKey;
        }
    }

    /** 从 .pmaster 文本内容导入（多通道），返回解密后的备份包 */
    public static BackupModel.BackupPackage importFromContent(String content, char[] masterPassword, SecretKey currentKey) {
        return importFromContentDetailed(content, masterPassword, currentKey).pack;
    }

    /** 从 .pmaster 文本内容导入（多通道），返回备份包与解密所用密钥 */
    public static ImportResult importFromContentDetailed(String content, char[] masterPassword, SecretKey currentKey) {
        if (content == null) {
            throw new IllegalStateException("备份内容为空");
        }
        content = content.trim();

        if (content.startsWith(HEADER_PREFIX)) {
            try {
                return decryptWithHeaderDetailed(content, masterPassword);
            } catch (Exception e) {
                android.util.Log.e("MimaVault", "header channel failed", e);
            }
        }
        if (currentKey != null) {
            try {
                return new ImportResult(decryptPackage(content, currentKey), currentKey);
            } catch (Exception e) {
                android.util.Log.e("MimaVault", "currentKey channel failed", e);
            }
        }
        try {
            SecretKey legacyKey = AesUtil.deriveKey(new String(masterPassword));
            return new ImportResult(decryptPackage(content, legacyKey), legacyKey);
        } catch (Exception e) {
            throw new IllegalStateException("主密码错误或文件损坏", e);
        }
    }

    /** 从 .pmaster 文件导入 */
    public static BackupModel.BackupPackage importFromFile(File source, char[] masterPassword, SecretKey currentKey) throws IOException {
        String content = readAllText(source);
        return importFromContent(content, masterPassword, currentKey);
    }

    /** 恢复备份包到数据库（覆盖/合并），返回 [成功条数, 跳过条数] */
    public static int[] restore(BackupModel.BackupPackage pack, PasswordService service, boolean overwrite) {
        return restore(pack, service, overwrite, null, null);
    }

    /**
     * 恢复备份包到数据库（覆盖/合并），返回 [成功条数, 跳过条数]
     * backupKey 为解密备份所用的密钥；localKey 为当前库密钥。
     * 两者均非空时，对条目密码做「备份密钥解密 → 库密钥重加密」，实现跨库（跨端）互通；
     * 否则按规格直接沿用备份内密文。
     */
    public static int[] restore(BackupModel.BackupPackage pack, PasswordService service, boolean overwrite,
                                SecretKey backupKey, SecretKey localKey) {
        int restored = 0;
        int skipped = 0;
        if (overwrite) {
            service.clearAll();
        }
        Set<String> existingKeys = new HashSet<>();
        if (!overwrite) {
            for (Entry e : service.listEntries()) {
                existingKeys.add(e.backupKey());
            }
        }
        List<Entry> toInsert = new ArrayList<>();
        for (BackupModel.BackupItem item : pack.items) {
            String key = backupKeyOf(item);
            if (existingKeys.contains(key)) {
                skipped++;
                continue;
            }
            existingKeys.add(key);
            Entry e = item.toEntry();
            // 条目密码重加密：备份密钥解 → 库密钥加密（跨库互通）
            if (backupKey != null && localKey != null) {
                String enc = e.getPasswordEnc();
                if (enc != null && !enc.isEmpty()) {
                    try {
                        String plain = AesUtil.decrypt(enc, backupKey);
                        e.setPasswordEnc(AesUtil.encrypt(plain, localKey));
                    } catch (Exception ex) {
                        android.util.Log.w("MimaVault", "条目密码重加密失败，保留原密文: "
                                + (item.platform == null ? "" : item.platform) + "/"
                                + (item.account == null ? "" : item.account));
                    }
                }
            }
            // 恢复图片
            if (item.imageBase64 != null && !item.imageBase64.isEmpty()) {
                String rel = saveImageFromBase64(item.imageBase64, item.imageName);
                if (rel != null) {
                    e.setImagePath(rel);
                }
            }
            toInsert.add(e);
            restored++;
        }
        if (!toInsert.isEmpty()) {
            service.insertAll(toInsert);
        }
        return new int[]{restored, skipped};
    }

    private static String backupKeyOf(BackupModel.BackupItem item) {
        return (item.platform == null ? "" : item.platform) + "|" + (item.account == null ? "" : item.account);
    }

    /** 解码 imageBase64 写入 data/images/<时间戳>_<原名>，返回相对路径 */
    private static String saveImageFromBase64(String base64, String originalName) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            String safeName = originalName == null ? "import.png" : originalName;
            String name = System.currentTimeMillis() + "_" + safeName;
            File target = new File(AppConfig.imageDir(), name);
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(bytes);
            }
            return "images/" + name;
        } catch (Exception e) {
            return null;
        }
    }

    private static ImportResult decryptWithHeaderDetailed(String content, char[] masterPassword) {
        String[] parts = content.split("\\$", 4);
        if (parts.length != 4 || !(parts[0] + "$").equals(HEADER_PREFIX)) {
            throw new IllegalStateException("备份文件 header 格式不正确");
        }
        byte[] salt = AesUtil.hexToBytes(parts[1]);
        int iterations = Integer.parseInt(parts[2]);
        SecretKey key = AesUtil.deriveKeyPbkdf2(masterPassword, salt, iterations);
        return new ImportResult(decryptPackage(parts[3], key), key);
    }

    private static BackupModel.BackupPackage decryptPackage(String base64Content, SecretKey key) {
        String json = AesUtil.decrypt(base64Content, key);
        BackupModel.BackupPackage pack = GSON.fromJson(json, BackupModel.BackupPackage.class);
        if (pack == null || pack.items == null) {
            throw new IllegalStateException("备份文件格式不正确");
        }
        return pack;
    }

    private static byte[] readAllBytes(File f) {
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    private static String readAllText(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
