package com.mimavault.model;

import java.util.Date;

/**
 * 密码条目实体
 * 对应数据库 entries 表
 */
public class Entry {

    public static final String CATEGORY_WEBSITE = "网站";
    public static final String CATEGORY_APP = "应用";
    public static final String CATEGORY_OTHER = "其他";
    public static final String[] CATEGORIES = {CATEGORY_WEBSITE, CATEGORY_APP, CATEGORY_OTHER};

    private long id;
    private String category;      // 条目分类：网站 / 应用 / 其他
    private String platform;      // 平台名称
    private String account;       // 账号
    private String passwordEnc;   // 密码（AES-256-GCM 加密存储，可空）
    private String phone;         // 手机号
    private String email;         // 邮箱
    private String note;          // 备注
    private String imagePath;     // 附件图片相对路径（data/images/ 下）
    private String gestureSeq;    // 九宫格手势数字序列，如 "1,4,7,8,9"
    private String syncStatus;    // 同步状态（默认 local，为后期手机互联预留）
    private Date createdAt;       // 创建时间
    private Date updatedAt;       // 更新时间
    private Date deletedAt;       // 回收站删除时间（null 表示未删除）

    public Entry() {
        this.category = CATEGORY_WEBSITE;
        this.syncStatus = "local";
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPasswordEnc() {
        return passwordEnc;
    }

    public void setPasswordEnc(String passwordEnc) {
        this.passwordEnc = passwordEnc;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getGestureSeq() {
        return gestureSeq;
    }

    public void setGestureSeq(String gestureSeq) {
        this.gestureSeq = gestureSeq;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Date getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Date deletedAt) {
        this.deletedAt = deletedAt;
    }


    /** 用于合并导入去重的键：platform|account（与 PC 端 keyOf 一致） */
    public String backupKey() {
        return (platform == null ? "" : platform) + "|" + (account == null ? "" : account);
    }
}
