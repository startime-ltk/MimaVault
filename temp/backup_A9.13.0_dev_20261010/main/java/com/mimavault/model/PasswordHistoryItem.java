package com.mimavault.model;

import java.util.Date;

/**
 * 密码历史版本条目：条目每次改密前的旧密码（AES-256-GCM 加密存储）
 * 对应数据库 password_history 表
 */
public class PasswordHistoryItem {

    private long id;
    private long entryId;        // 所属条目 id
    private String passwordEnc;  // 历史密码（加密串）
    private Date changedAt;      // 被替换的时间

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getEntryId() {
        return entryId;
    }

    public void setEntryId(long entryId) {
        this.entryId = entryId;
    }

    public String getPasswordEnc() {
        return passwordEnc;
    }

    public void setPasswordEnc(String passwordEnc) {
        this.passwordEnc = passwordEnc;
    }

    public Date getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Date changedAt) {
        this.changedAt = changedAt;
    }
}
