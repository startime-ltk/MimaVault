package com.passwordmaster.ui;

import com.passwordmaster.config.AppConfig;
import com.passwordmaster.util.DocxReader;
import com.passwordmaster.util.OcrUtil;
import com.passwordmaster.util.TextBatchParser;
import com.passwordmaster.util.ZhipuAiClient;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.awt.Dialog;
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

    /** 智能导入收集结果：草稿记录 + 来源原文 + 来源图片（供预览/放大） */
    public static class Result {
        public final List<TextBatchParser.RawRecord> records;
        public final String sourceText;
        public final List<Path> images;

        public Result(List<TextBatchParser.RawRecord> records, String sourceText, List<Path> images) {
            this.records = records;
            this.sourceText = sourceText == null ? "" : sourceText;
            this.images = images == null ? new ArrayList<>() : images;
        }
    }

    /** 收集导入内容，返回草稿记录 + 来源原文/图片；用户取消返回 null */
    public static Result collect(Window owner) {
        String[] options = {"从剪贴板导入", "选择文件导入", "手动粘贴文本", "AI 识别（智谱）"};
        int choice = JOptionPane.showOptionDialog(owner,
                "请选择智能导入来源：\n" +
                        "· 剪贴板：读取系统剪贴板中的文字与图片（图文混合按顺序）\n" +
                        "· 文件：支持 txt/md/csv 文本、png/jpg/bmp 图片、docx 文档（可多选）\n" +
                        "· 手动粘贴：将文字粘贴到输入框\n" +
                        "· AI 识别：调用智谱 GLM-4V-Flash 提取截图/文本中的账号密码（需联网）",
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
            } else if (choice == 2) {
                return collectFromPaste(owner);
            } else {
                return collectFromAi(owner);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "智能导入失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    /** a. 剪贴板：文本 + 图片 */
    private static Result collectFromClipboard(Window owner) throws Exception {
        StringBuilder text = new StringBuilder();
        List<Path> imagePaths = new ArrayList<>();

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

        // 图片部分：临时图片保留在 data/temp 供预览（不删除）
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
                        imagePaths.add(tmp);
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
                                imagePaths.add(f.toPath());
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
                // 剪贴板生成的临时图片保留在 data/temp 供编辑预览，不删除
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
        return new Result(records, raw, imagePaths);
    }

    /** b. 文件导入（多选） */
    private static Result collectFromFiles(Window owner) throws Exception {
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
        List<Path> imagePaths = new ArrayList<>();
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
                imagePaths.add(f.toPath());
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
                        // 内嵌图片落盘到 data/temp 供预览，不删除
                        Path imgPath = saveDocxImage(seg.imageBytes, seg.imageName);
                        if (imgPath != null) {
                            imagePaths.add(imgPath);
                        }
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
        return new Result(records, raw, imagePaths);
    }

    /** c. 手动粘贴文本 */
    private static Result collectFromPaste(Window owner) {
        JTextArea area = new JTextArea(10, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(420, 200));
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setOpaque(false);
        panel.add(new JLabel("请粘贴文字（标签式：平台/账号/密码；紧凑式：微信|xxx|123456。平台识别不出可留空，预览中补填）"), BorderLayout.NORTH);
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
        return new Result(records, raw, new ArrayList<>());
    }

    /** d. AI 识别（智谱 GLM-4V-Flash）：截图或文本交给模型提取结构化记录 */
    private static Result collectFromAi(Window owner) throws Exception {
        // 1. API Key 检查/录入
        AppConfig cfg = AppConfig.load();
        if (cfg.zhipuApiKey == null || cfg.zhipuApiKey.trim().isEmpty()) {
            JPasswordField keyField = new JPasswordField(30);
            JPanel keyPanel = new JPanel(new BorderLayout(6, 6));
            keyPanel.setOpaque(false);
            keyPanel.add(new JLabel("需要智谱开放平台 API Key（GLM-4V-Flash 免费）："), BorderLayout.NORTH);
            keyPanel.add(keyField, BorderLayout.CENTER);
            int r = JOptionPane.showConfirmDialog(owner, keyPanel,
                    "AI 识别设置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            String key = new String(keyField.getPassword()).trim();
            if (key.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "未输入 API Key，AI 识别取消", "提示", JOptionPane.INFORMATION_MESSAGE);
                return null;
            }
            cfg.zhipuApiKey = key;
            cfg.save();
        }

        // 2. 输入方式：图片文件 / 粘贴文本
        String[] ways = {"选择图片文件", "粘贴文本"};
        int w = JOptionPane.showOptionDialog(owner,
                "选择 AI 识别输入：\n· 图片：整张截图直接交给模型（效果最好）\n· 文本：粘贴大段账号密码文字",
                "AI 识别", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, ways, ways[0]);
        if (w < 0) {
            return null;
        }

        List<Path> images = new ArrayList<>();
        String text = null;
        if (w == 0) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择图片（可多选）");
            chooser.setMultiSelectionEnabled(true);
            javax.swing.filechooser.FileFilter imgFilter = new javax.swing.filechooser.FileFilter() {
                @Override
                public boolean accept(File f) {
                    return f.isDirectory() || isImageExt(f.getName().toLowerCase());
                }

                @Override
                public String getDescription() {
                    return "图片文件 (png/jpg/jpeg/bmp)";
                }
            };
            chooser.setFileFilter(imgFilter);
            if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
                return null;
            }
            for (File f : chooser.getSelectedFiles()) {
                if (f.isFile()) {
                    images.add(f.toPath());
                }
            }
            if (images.isEmpty()) {
                return null;
            }
        } else {
            JTextArea area = new JTextArea(10, 40);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            JScrollPane sp = new JScrollPane(area);
            sp.setPreferredSize(new Dimension(420, 200));
            JPanel panel = new JPanel(new BorderLayout(6, 6));
            panel.setOpaque(false);
            panel.add(new JLabel("请粘贴大段账号密码文字（可包含口语化描述）："), BorderLayout.NORTH);
            panel.add(sp, BorderLayout.CENTER);
            int r = JOptionPane.showConfirmDialog(owner, panel, "AI 识别文本", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            text = area.getText();
            if (text == null || text.trim().isEmpty()) {
                JOptionPane.showMessageDialog(owner, "未输入任何文本", "提示", JOptionPane.INFORMATION_MESSAGE);
                return null;
            }
        }

        // 3. 后台调用 + 模态等待（避免阻塞 EDT）
        final int way = w;
        final String inputText = text;
        final List<Path> inputImages = images;
        final List<TextBatchParser.RawRecord>[] resultBox = new List[1];
        final Throwable[] errorBox = new Throwable[1];

        JDialog waitDlg = new JDialog(owner, "AI 识别中", Dialog.ModalityType.APPLICATION_MODAL);
        waitDlg.setIconImage(UiTheme.getAppIcon());
        JPanel waitPanel = new JPanel(new BorderLayout(10, 10));
        waitPanel.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
        JLabel waitLabel = new JLabel("正在调用智谱 GLM-4V-Flash 识别，请稍候（约 5~30 秒）...");
        waitPanel.add(waitLabel, BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        waitPanel.add(bar, BorderLayout.CENTER);
        GradientButton cancelBtn = GradientButton.secondary("取消");
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnRow.setOpaque(false);
        btnRow.add(cancelBtn);
        waitPanel.add(btnRow, BorderLayout.SOUTH);
        waitDlg.add(waitPanel);
        waitDlg.pack();
        waitDlg.setLocationRelativeTo(owner);

        SwingWorker<List<TextBatchParser.RawRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<TextBatchParser.RawRecord> doInBackground() throws Exception {
                if (way == 0) {
                    List<TextBatchParser.RawRecord> all = new ArrayList<>();
                    for (Path p : inputImages) {
                        all.addAll(ZhipuAiClient.recognizeImage(p));
                    }
                    return all;
                }
                return ZhipuAiClient.recognizeText(inputText);
            }

            @Override
            protected void done() {
                try {
                    resultBox[0] = get();
                } catch (Throwable t) {
                    errorBox[0] = t.getCause() != null ? t.getCause() : t;
                } finally {
                    waitDlg.dispose();
                }
            }
        };
        cancelBtn.addActionListener(e -> {
            worker.cancel(true);
            waitDlg.dispose();
        });
        worker.execute();
        waitDlg.setVisible(true); // 模态阻塞，done() 内 dispose 后返回

        if (errorBox[0] != null) {
            JOptionPane.showMessageDialog(owner, "AI 识别失败：" + errorBox[0].getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        List<TextBatchParser.RawRecord> records = resultBox[0];
        if (records == null || records.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "AI 未能识别出账号/密码记录，请检查图片清晰度或文本内容", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }

        // 4. 组装结果
        String sourceText = way == 0
                ? "AI识别图片：" + inputImages.size() + " 张"
                : inputText;
        String sourceType = way == 0 ? "AI识别图片" : "AI识别文本";
        for (TextBatchParser.RawRecord rec : records) {
            rec.sourceType = sourceType;
        }
        return new Result(records, sourceText, way == 0 ? inputImages : new ArrayList<>());
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

    /** docx 内嵌图片落盘到 data/temp 供预览；失败返回 null */
    private static Path saveDocxImage(byte[] bytes, String name) {
        try {
            Path dir = AppConfig.DATA_DIR.resolve("temp");
            Files.createDirectories(dir);
            String safe = name == null ? "docx_img" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
            Path tmp = dir.resolve(System.currentTimeMillis() + "_" + safe);
            Files.write(tmp, bytes);
            return tmp;
        } catch (Exception ex) {
            return null;
        }
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
