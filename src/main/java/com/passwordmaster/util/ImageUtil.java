package com.passwordmaster.util;

import com.passwordmaster.config.AppConfig;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/**
 * 图片工具：复制截图附件、base64 编码/解码、缩放预览
 */
public final class ImageUtil {

    private ImageUtil() {
    }

    /** 图片扩展名白名单 */
    private static final String[] ALLOWED_EXT = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"};

    /**
     * 将本地截图复制到 data/images/ 下，返回相对路径（如 images/xxx.png）
     * 文件名带时间戳避免冲突
     */
    public static String importImageToData(Path source) {
        try {
            if (source == null || !Files.exists(source)) {
                return null;
            }
            Files.createDirectories(AppConfig.IMAGE_DIR);
            String ext = getExtension(source.getFileName().toString());
            String name = System.currentTimeMillis() + "_" + source.getFileName().toString();
            Path target = AppConfig.IMAGE_DIR.resolve(name);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return "images/" + name;
        } catch (IOException e) {
            System.err.println("复制附件图片失败: " + e.getMessage());
            return null;
        }
    }

    /** 相对路径转绝对路径（data 目录下） */
    public static Path resolveImagePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        return AppConfig.DATA_DIR.resolve(relativePath);
    }

    /** 图片文件转 Base64 */
    public static String imageToBase64(Path imagePath) {
        try {
            if (imagePath == null || !Files.exists(imagePath)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(imagePath);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            System.err.println("图片转 Base64 失败: " + e.getMessage());
            return null;
        }
    }

    /** Base64 还原为图片文件，返回目标路径 */
    public static Path base64ToImage(String base64, String originalName) {
        try {
            if (base64 == null || base64.isEmpty()) {
                return null;
            }
            Files.createDirectories(AppConfig.IMAGE_DIR);
            String name = System.currentTimeMillis() + "_" + (originalName == null ? "import.png" : originalName);
            Path target = AppConfig.IMAGE_DIR.resolve(name);
            byte[] bytes = Base64.getDecoder().decode(base64);
            Files.write(target, bytes);
            return target;
        } catch (Exception e) {
            System.err.println("Base64 还原图片失败: " + e.getMessage());
            return null;
        }
    }

    /** 读取图片文件为 ImageIcon（缩放到 maxSize 内） */
    public static ImageIcon loadScaledIcon(Path imagePath, int maxSize) {
        try {
            if (imagePath == null || !Files.exists(imagePath)) {
                return null;
            }
            BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
            if (img == null) {
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            double scale = Math.min(1.0, (double) maxSize / Math.max(w, h));
            int nw = Math.max(1, (int) (w * scale));
            int nh = Math.max(1, (int) (h * scale));
            Image scaled = img.getScaledInstance(nw, nh, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception e) {
            System.err.println("加载图片失败: " + e.getMessage());
            return null;
        }
    }

    /** 判断文件是否为允许的图片类型 */
    public static boolean isSupportedImage(Path file) {
        if (file == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase();
        for (String ext : ALLOWED_EXT) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String getExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return fileName.substring(idx).toLowerCase();
    }
}
