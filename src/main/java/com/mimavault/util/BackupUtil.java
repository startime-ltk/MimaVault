package com.mimavault.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimavault.model.Entry;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 一键导出 / 导入
 * 导出：全部条目（密码保持密文）+ 图片 base64 组装 JSON → AES-256-GCM 加密 → 写 .pmaster
 * 导入：读取 .pmaster → 主密码解密 → 恢复条目与图片
 *
 * 备份文件格式（v2，2026-08 起）：
 *   - 新格式（PBKDF2 库）：MimaVault1$盐hex$迭代次数$Base64密文
 *     header 显式携带密钥派生参数，解决"参数在加密内容内无法派生密钥"的循环依赖，跨库可导入
 *   - 旧格式（无 header）：纯 Base64 密文（AES-256-GCM），兼容历史备份与旧版 SHA-256 库导出
 * 导入时按 通道1(header PBKDF2) → 通道2(当前库密钥) → 通道3(旧版 SHA-256) 依次尝试解密
 */
public final class BackupUtil {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 备份文件版本标记（内层 JSON） */
    private static final String VERSION = "1";

    /** v2 备份文件 header 前缀，格式：MimaVault1$盐hex$迭代次数$Base64密文 */
    public static final String HEADER_PREFIX = "MimaVault1$";

    private BackupUtil() {
    }

    /**
     * 导出全部数据为 .pmaster 文件
     *
     * @param entries      密码字段保持密文的条目列表（调用方从库中读取，不解密）
     * @param key          主密码派生密钥（新库=PBKDF2 派生；旧库=SHA-256 派生）
     * @param saltHex      当前库 PBKDF2 盐（hex，小写）；旧库/无盐传 null，此时不写 header（旧格式）
     * @param iterations   当前库 PBKDF2 迭代次数；saltHex 为 null 时该值被忽略
     * @param targetPath   目标 .pmaster 文件
     */
    public static void export(List<Entry> entries, SecretKey key, String saltHex, int iterations, Path targetPath) throws IOException {
        BackupPackage pack = new BackupPackage();
        pack.version = VERSION;
        pack.exportedAt = System.currentTimeMillis();
        for (Entry e : entries) {
            BackupItem item = new BackupItem();
            item.category = e.getCategory();
            item.platform = e.getPlatform();
            item.account = e.getAccount();
            item.password = e.getPasswordEnc() == null ? null : "encrypted:" + e.getPasswordEnc();
            item.phone = e.getPhone();
            item.email = e.getEmail();
            item.note = e.getNote();
            item.gestureSeq = e.getGestureSeq();
            item.totpSecret = e.getTotpSecretEnc() == null ? null : "encrypted:" + e.getTotpSecretEnc();
            item.syncStatus = e.getSyncStatus();
            item.createdAt = e.getCreatedAt();
            item.updatedAt = e.getUpdatedAt();
            // 图片：从 data/images 读取为 base64
            if (e.getImagePath() != null && !e.getImagePath().isEmpty()) {
                Path imgPath = ImageUtil.resolveImagePath(e.getImagePath());
                String b64 = ImageUtil.imageToBase64(imgPath);
                if (b64 != null) {
                    item.imageBase64 = b64;
                    item.imageName = Path.of(e.getImagePath()).getFileName().toString();
                }
            }
            pack.items.add(item);
        }
        String json = GSON.toJson(pack);
        String encrypted = AesUtil.encrypt(json, key);
        // v2：新格式写 header（盐hex$迭代次数），旧库（无盐）保持纯密文向后兼容
        String fileContent = encrypted;
        if (saltHex != null && !saltHex.isEmpty()) {
            fileContent = HEADER_PREFIX + saltHex + "$" + iterations + "$" + encrypted;
        }
        Files.createDirectories(targetPath.getParent() == null ? Path.of(".") : targetPath.getParent());
        Files.writeString(targetPath, fileContent, StandardCharsets.UTF_8);
    }

    /**
     * 从 .pmaster 读取并解密（多通道）
     * 按顺序尝试：① header 内盐/迭代次数走 PBKDF2（新格式，同库/跨库均可）；
     * ② 当前库密钥（救"当前版本导出的无 header 备份"）；③ 旧版 SHA-256 派生（历史备份兼容）。
     * 任一成功即返回解密后的备份包；全部失败抛出 IllegalStateException。
     *
     * @param source        备份文件
     * @param masterPassword 用户输入的主密码（调用方负责清零）
     * @param currentKey    当前库主密码派生密钥（可传 null，跳过通道2）
     */
    public static BackupPackage importBackup(Path source, char[] masterPassword, SecretKey currentKey) throws IOException {
        return importBackupDetailed(source, masterPassword, currentKey).pack;
    }

    /**
     * 从 .pmaster 读取并解密（多通道），返回备份包与解密实际使用的密钥（backupKey）。
     * 通道顺序与 importBackup 一致：① header PBKDF2 → ② 当前库密钥 → ③ 旧版 SHA-256。
     * backupKey 供调用方对条目密码做「备份密钥解密 → 当前库密钥重加密」，实现跨库/跨密码导入。
     */
    public static ImportResult importBackupDetailed(Path source, char[] masterPassword, SecretKey currentKey) throws IOException {
        String content = Files.readString(source, StandardCharsets.UTF_8).trim();

        // 通道1：带 header 的新格式 → header 内盐/迭代次数 PBKDF2 派生
        if (content.startsWith(HEADER_PREFIX)) {
            try {
                return decryptWithHeaderDetailed(content, masterPassword);
            } catch (Exception ignored) {
                // 继续尝试后续通道（密码错误或 header 损坏）
            }
        }

        // 通道2：当前库密钥（服务端 deriveKey，新库 PBKDF2 / 旧库 SHA-256）
        if (currentKey != null) {
            try {
                return new ImportResult(decryptPackage(content, currentKey), currentKey);
            } catch (Exception ignored) {
                // 继续尝试
            }
        }

        // 通道3：旧版 SHA-256 派生密钥（历史备份兜底）
        char[] copy = masterPassword.clone();
        try {
            SecretKey legacyKey = AesUtil.deriveKey(new String(copy));
            return new ImportResult(decryptPackage(content, legacyKey), legacyKey);
        } finally {
            java.util.Arrays.fill(copy, '\0');
        }
    }

    /**
     * 从 .pmaster 读取并解密（单密钥，底层）
     * 仅按给定密钥解密；主密码错误或文件损坏时抛出 IllegalStateException
     */
    public static BackupPackage importBackup(Path source, SecretKey key) throws IOException {
        String content = Files.readString(source, StandardCharsets.UTF_8).trim();
        return decryptPackage(content, key);
    }

    /**
     * 导入结果：解密后的备份包 + 实际解密所用的密钥（backupKey）
     * backupKey 用于对条目密码做「备份密钥解密 → 当前库密钥重加密」
     */
    public static class ImportResult {
        public final BackupPackage pack;
        public final SecretKey backupKey;

        public ImportResult(BackupPackage pack, SecretKey backupKey) {
            this.pack = pack;
            this.backupKey = backupKey;
        }
    }

    /** 解析 v2 header（MimaVault1$盐hex$迭代次数$Base64密文）并用 header 参数 PBKDF2 派生密钥解密 */
    private static BackupPackage decryptWithHeader(String content, char[] masterPassword) {
        return decryptWithHeaderDetailed(content, masterPassword).pack;
    }

    /** 解析 v2 header（MimaVault1$盐hex$迭代次数$Base64密文）并用 header 参数 PBKDF2 派生密钥解密，返回密钥 */
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

    /** 解密纯 Base64 密文内容并解析备份包 */
    private static BackupPackage decryptPackage(String base64Content, SecretKey key) {
        String json = AesUtil.decrypt(base64Content, key);
        BackupPackage pack = GSON.fromJson(json, BackupPackage.class);
        if (pack == null || pack.items == null) {
            throw new IllegalStateException("备份文件格式不正确");
        }
        return pack;
    }

    /**
     * 备份条目数据结构（含密码字段标记）
     */
    public static class BackupItem {
        public String category;
        public String platform;
        public String account;
        public String password;      // "encrypted:<密文>" 或 null
        public String phone;
        public String email;
        public String note;
        public String imageBase64;
        public String imageName;
        public String gestureSeq;
        public String totpSecret;    // "encrypted:<otpauth 链接密文>" 或 null（与密码同样随主密码加密）
        public String syncStatus;
        public Date createdAt;
        public Date updatedAt;

        /** 还原为 Entry（密码保持密文形式，可继续用主密码解密） */
        public Entry toEntry() {
            Entry e = new Entry();
            e.setCategory(category == null ? Entry.CATEGORY_WEBSITE : category);
            e.setPlatform(platform);
            e.setAccount(account);
            if (password != null && password.startsWith("encrypted:")) {
                e.setPasswordEnc(password.substring("encrypted:".length()));
            }
            e.setPhone(phone);
            e.setEmail(email);
            e.setNote(note);
            e.setGestureSeq(gestureSeq);
            if (totpSecret != null && totpSecret.startsWith("encrypted:")) {
                e.setTotpSecretEnc(totpSecret.substring("encrypted:".length()));
            }
            e.setSyncStatus(syncStatus == null ? "local" : syncStatus);
            e.setCreatedAt(createdAt);
            e.setUpdatedAt(updatedAt);
            return e;
        }
    }

    public static class BackupPackage {
        public String version;
        public long exportedAt;
        public List<BackupItem> items = new ArrayList<>();
    }
}
