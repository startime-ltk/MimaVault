package com.mimavault.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 图片工具：压缩与写入（图片附件存储用）
 * 与 PC 端 ImageUtil 白名单思路一致：仅允许常见图片格式
 */
public final class ImageUtil {

    private static final String[] WHITELIST = {".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"};
    private static final int MAX_DIM = 1600;

    private ImageUtil() {
    }

    public static boolean isAllowedExtension(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String ext : WHITELIST) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 从 Content Uri 读取并压缩保存为 JPEG，返回相对路径 images/xxx.jpg */
    public static String saveCompressed(Context context, Uri uri) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, opts);
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            while (maxDim / sample > MAX_DIM) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bmp == null) {
                return null;
            }
            String name = System.currentTimeMillis() + ".jpg";
            File out = new File(com.mimavault.config.AppConfig.imageDir(), name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            return "images/" + name;
        } catch (Exception e) {
            android.util.Log.e("MimaVault", "saveCompressed failed uri=" + uri, e);
            return null;
        }
    }

    /** 从本地文件路径压缩保存为 JPEG，返回相对路径 */
    public static String saveScaledImage(String srcPath, String originalName, File imageDir) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(srcPath, opts);
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            int sample = 1;
            while (maxDim / sample > MAX_DIM) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(srcPath, opts);
            if (bmp == null) {
                return null;
            }
            File out = new File(imageDir, System.currentTimeMillis() + "_" + sanitize(originalName) + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            return "images/" + out.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从相对路径（images/xxx.jpg）解析出磁盘文件 */
    public static File resolve(Context context, String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        String fileName = relativePath;
        int slash = relativePath.lastIndexOf('/');
        if (slash >= 0) {
            fileName = relativePath.substring(slash + 1);
        }
        return new File(com.mimavault.config.AppConfig.imageDir(), fileName);
    }

    /** 加载相对路径图片为 Bitmap（不存在返回 null） */
    public static Bitmap loadBitmap(Context context, String relativePath) {
        File f = resolve(context, relativePath);
        if (f == null || !f.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    private static String sanitize(String name) {
        String base = name == null ? "img" : name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
