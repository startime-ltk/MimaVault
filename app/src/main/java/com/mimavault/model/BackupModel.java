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
        public String syncStatus;
        public Date createdAt;
        public Date updatedAt;

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
            e.setSyncStatus(syncStatus == null ? "local" : syncStatus);
            e.setCreatedAt(createdAt);
            e.setUpdatedAt(updatedAt);
            return e;
        }
    }
}
