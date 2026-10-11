package com.mimavault.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * .pmaster 备份包 JSON 结构，与 PC 端 BackupUtil.BackupPackage / BackupItem 1:1 对齐
 */
public final class BackupModel {

    private BackupModel() {
    }

    /** 备份包 */
    public static class BackupPackage {
        public String version;
        public long exportedAt;
        public List<BackupItem> items = new ArrayList<>();
    }

    /** 备份条目（字段名与 PC 端完全一致，Gson 按名映射） */
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
        public String totpSecret;    // "encrypted:<密文>" 或 null（TOTP 密钥，PC 端无此字段则忽略）
        public String extraEnc;      // "encrypted:<密文>" 或 null（分类专属字段，WiFi/银行卡/证件/API Key）
        public String syncStatus;
        public Date createdAt;
        public Date updatedAt;
        // A9.13.0：使用次数与收藏。PC 端旧版未定义这些字段，Gson 反序列化时自动忽略，保证 .pmaster 向后兼容
        public long useCount;
        public boolean isFavorite;

        /** 还原为 Entry（密码保持密文形式） */
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
            if (extraEnc != null && extraEnc.startsWith("encrypted:")) {
                e.setExtraEnc(extraEnc.substring("encrypted:".length()));
            }
            e.setSyncStatus(syncStatus == null ? "local" : syncStatus);
            e.setCreatedAt(createdAt);
            e.setUpdatedAt(updatedAt);
            e.setUseCount(useCount);
            e.setFavorite(isFavorite);
            return e;
        }
    }
}
