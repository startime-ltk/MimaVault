package com.passwordmaster.ui;

import com.passwordmaster.config.AppConfig;
import com.passwordmaster.util.DocxReader;
import com.passwordmaster.util.OcrUtil;
import com.passwordmaster.util.TextBatchParser;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能导入来源对话框
 * 支持三种来源：
 * a. 从剪贴板导入（文本 + 图片，图文混合按顺序）
 * b. 选择文件导入（.txt/.md/.csv、图片、.docx，可多选）
 * c. 手动粘贴文本导入
 * <p>
 * 收集完成后统一交给 TextBatchParser 拆分解析，返回草稿记录列表。
 */
public final class SmartImportDialog {

    private SmartImportDialog() {
    }

    /** 收集导入内容，返回草稿记录；用户取消返回 null */
    public static List<TextBatchParser.RawRecord> collect(Window owner) {
        String[] options = {"从剪贴板导入", "选择文件导入", "手动粘贴文本"};
        int choice = JOptionPane.showOptionDialog(owner,
                "请选择智能导入来源：\n" +
                        "· 剪贴板：读取系统剪贴板中的文字与图片（图文混合按顺序）\n" +
                        "· 文件：支持 txt/md/csv 文本、png/jpg/bmp 图片、docx 文档（可多选）\n" +
                        "· 手动粘贴：将文字粘贴到输入框",
                "智能导入", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice < 0) {
            return null;
        }
        try {
            if (choice == 0) {
                return collectFromClipboard(owner);
            } else if (choice == 1) {
                return collectFromFiles(owner);
            } else {
                return collectFromPaste(owner);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "智能导入失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    /** a. 剪贴板：文本 + 图片 */
    private static List<TextBatchParser.RawRecord> collectFromClipboard(Window owner) throws Exception {
        StringBuilder text = new StringBuilder();

        Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
        boolean hasImage = t != null && (t.isDataFlavorSupported(DataFlavor.imageFlavor)
                || t.isDataFlavorSupported(DataFlavor.javaFileListFlavor));

        // 文本部分
        if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            String str = (String) t.getTransferData(DataFlavor.stringFlavor);
            if (str != null && !str.trim().isEmpty()) {
                text.append(str.trim());
            }
        }

        // 图片部分
        if (hasImage) {
            List<Path> tmpFiles = new ArrayList<>();
            try {
                // 剪贴板中的图片对象
                if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    Image img = (Image) t.getTransferData(DataFlavor.imageFlavor);
                    if (img != null) {
                        BufferedImage bi = toBufferedImage(img);
                        Path tmp = saveTempImage(bi, "clipboard_img_" + System.currentTimeMillis() + ".png");
                        tmpFiles.add(tmp);
                    }
                }
                // 剪贴板中的文件列表（复制文件场景）
                if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    List<?> files = (List<?>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    for (Object o : files) {
                        if (o instanceof File) {
                            File f = (File) o;
                            String name = f.getName().toLowerCase();
                            if (isImageExt(name)) {
                                tmpFiles.add(f.toPath());
                            }
                        }
                    }
                }
                // 逐个 OCR
                for (int i = 0; i < tmpFiles.size(); i++) {
                    String ocr = ocrOrError(tmpFiles.get(i), "[图片" + (i + 1) + "]");
                    if (text.length() > 0) {
                        text.append("\n");
                    }
                    text.append("[图片").append(i + 1).append("识别结果]\n").append(ocr);
                }
            } finally {
                for (Path p : tmpFiles) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        String raw = text.toString().trim();
        if (raw.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "剪贴板中没有可导入的内容（无文字且无图片）", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        String type = hasImage ? "图文混合" : "剪贴板文字";
        List<TextBatchParser.RawRecord> records = TextBatchParser.parseBatch(raw, type);
        if (records.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "未能从剪贴板内容中解析出账号/密码记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return records;
    }

    /** b. 文件导入（多选） */
    private static List<TextBatchParser.RawRecord> collectFromFiles(Window owner) throws Exception {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择导入文件（可多选：txt/md/csv、图片、docx）");
        chooser.setMultiSelectionEnabled(true);
        javax.swing.filechooser.FileFilter allFilter = new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                if (f.isDirectory()) {
                    return true;
                }
                String n = f.getName().toLowerCase();
                return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")
                        || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".bmp")
                        || n.endsWith(".docx");
            }

            @Override
            public String getDescription() {
                return "文本/图片/docx 文件";
            }
        };
        chooser.setFileFilter(allFilter);
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File[] files = chooser.getSelectedFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        int imgSeq = 0;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (f.isDirectory()) {
                continue;
            }
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) {
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append(content);
            } else if (isImageExt(name)) {
                imgSeq++;
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append("[图片").append(imgSeq).append(":").append(f.getName()).append(" 识别结果]\n");
                text.append(ocrOrError(f.toPath(), "[图片" + imgSeq + "]"));
            } else if (name.endsWith(".docx")) {
                // docx 图文交叉按原顺序解析
                List<DocxReader.Segment> segments = DocxReader.read(f);
                for (DocxReader.Segment seg : segments) {
                    if (seg.image) {
                        imgSeq++;
                        if (text.length() > 0 && !endsWithNewline(text)) {
                            text.append("\n");
                        }
                        text.append("[图片").append(imgSeq).append(":").append(seg.imageName).append(" 识别结果]\n");
                        text.append(ocrBytesOrError(seg.imageBytes, seg.imageName, "[图片" + imgSeq + "]"));
                    } else {
                        if (text.length() > 0 && !endsWithNewline(text)) {
                            text.append("\n");
                        }
                        text.append(seg.text);
                    }
                }
            }
        }

        String raw = text.toString().trim();
        if (raw.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "所选文件中没有可导入的内容", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        List<TextBatchParser.RawRecord> records = TextBatchParser.parseBatch(raw, "文件导入");
        if (records.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "未能从所选文件中解析出账号/密码记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return records;
    }

    /** c. 手动粘贴文本 */
    private static List<TextBatchParser.RawRecord> collectFromPaste(Window owner) {
        JTextArea area = new JTextArea(10, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(420, 200));
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JLabel("请粘贴文字（支持标签式：平台/账号/密码；紧凑式：微信|xxx|123456）"), BorderLayout.NORTH);
        panel.add(sp, BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(owner, panel, "手动粘贴文本", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return null;
        }
        String raw = area.getText();
        if (raw == null || raw.trim().isEmpty()) {
            JOptionPane.showMessageDialog(owner, "未输入任何文本", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        List<TextBatchParser.RawRecord> records = TextBatchParser.parseBatch(raw, "文字");
        if (records.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "未能从文本中解析出账号/密码记录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return records;
    }

    // ---------- 工具方法 ----------

    private static boolean endsWithNewline(StringBuilder sb) {
        int len = sb.length();
        return len > 0 && (sb.charAt(len - 1) == '\n');
    }

    private static boolean isImageExt(String name) {
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".bmp");
    }

    private static String ocrOrError(Path img, String label) {
        try {
            String r = OcrUtil.doOcr(img.toFile());
            return r == null ? "" : r;
        } catch (Exception e) {
            return label + " OCR失败：" + e.getMessage();
        }
    }

    private static String ocrBytesOrError(byte[] bytes, String name, String label) {
        Path tmp = null;
        try {
            Path dir = AppConfig.DATA_DIR.resolve("temp");
            Files.createDirectories(dir);
            tmp = dir.resolve(System.currentTimeMillis() + "_" + name);
            Files.write(tmp, bytes);
            String r = OcrUtil.doOcr(tmp.toFile());
            return r == null ? "" : r;
        } catch (Exception e) {
            return label + " OCR失败：" + e.getMessage();
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Path saveTempImage(BufferedImage bi, String name) throws Exception {
        Path dir = AppConfig.DATA_DIR.resolve("temp");
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name);
        ImageIO.write(bi, "png", tmp.toFile());
        return tmp;
    }

    private static BufferedImage toBufferedImage(Image img) {
        if (img instanceof BufferedImage) {
            return (BufferedImage) img;
        }
        BufferedImage bi = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return bi;
    }
}
