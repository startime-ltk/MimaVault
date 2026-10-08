package com.mimavault.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 密码条目实体
 * 对应数据库 entries 表
 */
public class Entry {

    public static final String CATEGORY_WEBSITE = "网站";
    public static final String CATEGORY_APP = "应用";
    public static final String CATEGORY_OTHER = "其他";
    public static final String CATEGORY_WIFI = "WiFi";
    public static final String CATEGORY_BANK_CARD = "银行卡";
    public static final String CATEGORY_ID_CARD = "证件";
    public static final String CATEGORY_API_KEY = "API Key";
    public static final String[] CATEGORIES = {CATEGORY_WEBSITE, CATEGORY_APP, CATEGORY_OTHER,
            CATEGORY_WIFI, CATEGORY_BANK_CARD, CATEGORY_ID_CARD, CATEGORY_API_KEY};

    private static final Gson GSON = new Gson();

    private long id;
    private String category;      // 条目分类：网站 / 应用 / 其他 / WiFi / 银行卡 / 证件 / API Key
    private String platform;      // 平台名称
    private String account;       // 账号
    private String passwordEnc;   // 密码（AES-256-GCM 加密存储，可空）
    private String phone;         // 手机号
    private String email;         // 邮箱
    private String note;          // 备注
    private String imagePath;     // 附件图片相对路径（data/images/ 下）
    private String gestureSeq;    // 九宫格手势数字序列，如 "1,4,7,8,9"
    private String totpSecretEnc; // TOTP 动态验证码（otpauth:// 链接，AES-256-GCM 加密存储，可空）
    private String extraEnc;      // 分类专属字段（WiFi/银行卡/证件/API Key），JSON 字符串整体 AES-256-GCM 加密存储（可空）
    private String syncStatus;    // 同步状态（默认 local，为后期手机互联预留）
    private Date createdAt;       // 创建时间
    private Date updatedAt;       // 更新时间
    private Date deletedAt;       // 回收站删除时间（null 表示未删除）

    /** 分类专属字段键名（双端一致，用于 JSON 序列化与 CSV 列映射） */
    public static final class ExtraKeys {
        // WiFi
        public static final String WIFI_SSID = "wifi_ssid";
        public static final String WIFI_ENCRYPTION = "wifi_encryption";
        // 银行卡
        public static final String BANK_CARD_NO = "bank_card_no";
        public static final String BANK_HOLDER = "bank_holder";
        public static final String BANK_EXPIRY = "bank_expiry";
        public static final String BANK_CVV = "bank_cvv";
        public static final String BANK_NAME = "bank_name";
        // 证件
        public static final String ID_TYPE = "id_type";
        public static final String ID_NUMBER = "id_number";
        public static final String ID_NAME = "id_name";
        public static final String ID_AUTHORITY = "id_authority";
        public static final String ID_EXPIRY = "id_expiry";
        // API Key
        public static final String API_KEY = "api_key";
        public static final String API_ENDPOINT = "api_endpoint";
        public static final String API_MODEL = "api_model";
    }

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

    public String getTotpSecretEnc() {
        return totpSecretEnc;
    }

    public void setTotpSecretEnc(String totpSecretEnc) {
        this.totpSecretEnc = totpSecretEnc;
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

    public String getExtraEnc() {
        return extraEnc;
    }

    public void setExtraEnc(String extraEnc) {
        this.extraEnc = extraEnc;
    }

    /** 将分类专属字段 Map 序列化为 JSON 字符串（加密前的明文载体） */
    public static String encodeExtra(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        return GSON.toJson(extra);
    }

    /** 将加密前的 JSON 字符串还原为分类专属字段 Map（空/非法返回空 Map） */
    public static Map<String, String> decodeExtra(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> map = GSON.fromJson(json, new TypeToken<Map<String, String>>() {
            }.getType());
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
