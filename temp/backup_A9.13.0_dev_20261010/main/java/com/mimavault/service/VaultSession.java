package com.mimavault.service;

import java.util.Arrays;

import javax.crypto.SecretKey;

/**
 * 会话状态：主密码验证通过后持有派生密钥，供条目加解密与备份使用
 * 密码字符数组与密钥字节在退出登录/销毁时清零
 */
public class VaultSession {

    private static VaultSession instance;

    private SecretKey key;
    private char[] masterPassword;
    private String masterSaltHex;
    private int iterations;

    private VaultSession() {
    }

    public static synchronized VaultSession get() {
        if (instance == null) {
            instance = new VaultSession();
        }
        return instance;
    }

    public synchronized void open(char[] masterPassword, SecretKey key, String saltHex, int iterations) {
        this.masterPassword = masterPassword.clone();
        this.key = key;
        this.masterSaltHex = saltHex;
        this.iterations = iterations;
    }

    public synchronized SecretKey key() {
        return key;
    }

    public synchronized String masterSaltHex() {
        return masterSaltHex;
    }

    public synchronized int iterations() {
        return iterations;
    }

    public synchronized char[] masterPasswordCopy() {
        return masterPassword == null ? null : masterPassword.clone();
    }

    public synchronized boolean isOpen() {
        return key != null;
    }

    public synchronized void close() {
        if (masterPassword != null) {
            Arrays.fill(masterPassword, '\0');
        }
        masterPassword = null;
        key = null;
        masterSaltHex = null;
        iterations = 0;
    }
}
