package com.passwordmaster.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.passwordmaster.model.Entry;

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
 * 导出：全部条目（密码解密后明文）+ 图片 base64 组装 JSON → AES-256-GCM 加密 → 写 .pmaster
 * 导入：读取 .pmaster → 主密码解密 → 恢复条目与图片
 */
public final class BackupUtil {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 备份文件版本标记 */
    private static final String VERSION = "1";

    private BackupUtil() {
    }

    /**
     * 导出全部数据为 .pmaster 文件
     *
     * @param entries      已解密密码的条目列表（调用方用主密码解密后传入）
     * @param key          主密码派生密钥
     * @param targetPath   目标 .pmaster 文件
     */
    public static void export(List<Entry> entries, SecretKey key, Path targetPath) throws IOException {
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
        Files.createDirectories(targetPath.getParent() == null ? Path.of(".") : targetPath.getParent());
        Files.writeString(targetPath, encrypted, StandardCharsets.UTF_8);
    }

    /**
     * 从 .pmaster 读取并解密
     *
     * @return 解密后的备份包；主密码错误时抛出 IllegalStateException
     */
    public static BackupPackage importBackup(Path source, SecretKey key) throws IOException {
        String encrypted = Files.readString(source, StandardCharsets.UTF_8);
        String json = AesUtil.decrypt(encrypted.trim(), key);
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
