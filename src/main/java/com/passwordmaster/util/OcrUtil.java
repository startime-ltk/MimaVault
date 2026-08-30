package com.passwordmaster.util;

import com.passwordmaster.config.AppConfig;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import javax.swing.*;
import java.awt.*;
import java.awt.Dialog.ModalityType;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 离线 OCR 识图工具（Tesseract + tess4j）
 * - 识别语言：中文 chi_sim
 * - 语言包首次使用时才下载（data/tessdata/chi_sim.traineddata，约 15MB）
 * - 下载成功后离线可用
 */
public final class OcrUtil {

    private static final String LANGUAGE = "chi_sim";
    private static final String TRAINEDDATA_NAME = "chi_sim.traineddata";
    private static final String DOWNLOAD_URL =
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main/chi_sim.traineddata";

    private OcrUtil() {
    }

    /** tessdata 目录（data/tessdata/），不存在则创建 */
    public static File getTessdataDir() {
        File dir = new File(AppConfig.DATA_DIR.toFile(), "tessdata");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** 中文语言包文件 */
    public static File getTrainedDataFile() {
        return new File(getTessdataDir(), TRAINEDDATA_NAME);
    }

    /** 语言包是否已就绪 */
    public static boolean isLanguageReady() {
        File f = getTrainedDataFile();
        return f.exists() && f.length() > 0;
    }

    /**
     * 识别图片中的文字
     * 首次使用会先下载中文语言包；语言包缺失且用户取消返回 null
     */
    public static String doOcr(File imageFile) throws Exception {
        return doOcr(imageFile, null);
    }

    /**
     * 识别图片中的文字（带父窗口）
     * 首次使用会先下载中文语言包（确认/进度对话框以 parent 为宿主）；语言包缺失且用户取消返回 null
     */
    public static String doOcr(File imageFile, Component parent) throws Exception {
        if (imageFile == null || !imageFile.exists()) {
            throw new IllegalArgumentException("图片文件不存在");
        }
        if (!isLanguageReady()) {
            boolean downloaded = downloadLanguagePack(parent);
            if (!downloaded) {
                return null;
            }
        }
        try {
            ITesseract tesseract = new Tesseract();
            tesseract.setDatapath(getTessdataDir().getAbsolutePath());
            tesseract.setLanguage(LANGUAGE);
            return tesseract.doOCR(imageFile);
        } catch (TesseractException e) {
            throw new Exception("OCR 识别失败：" + e.getMessage(), e);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new Exception("Tesseract 本地库加载失败（缺少 native 库 / dll）：" + e.getMessage()
                    + "\n请确认系统已安装 VC++ 运行库或 tess4j 支持的环境。", e);
        }
    }

    /**
     * 下载中文语言包，带简单百分比进度条
     *
     * @return true 下载成功；false 用户取消或失败（失败已弹窗提示）
     */
    private static boolean downloadLanguagePack(Component parent) {
        Window owner = null;
        if (parent instanceof Window) {
            owner = (Window) parent;
        } else if (parent != null) {
            owner = SwingUtilities.getWindowAncestor(parent);
        }

        int confirm = JOptionPane.showConfirmDialog(parent,
                "首次使用识图功能需要下载中文语言包（约 15MB）。\n"
                        + "下载后将保存到 data\\tessdata\\，之后完全离线可用。\n是否现在下载？",
                "下载中文语言包", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) {
            return false;
        }

        JDialog dlg = new JDialog(owner, "下载中文语言包", ModalityType.APPLICATION_MODAL);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JLabel label = new JLabel("正在下载 chi_sim.traineddata ...");
        label.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setPreferredSize(new Dimension(360, 24));
        panel.add(label, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        dlg.add(panel);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setResizable(false);

        final boolean[] result = {false};
        Thread worker = new Thread(() -> {
            try {
                URL url = new URL(DOWNLOAD_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("服务器返回错误码 " + code);
                }
                long total = conn.getContentLengthLong();
                File target = getTrainedDataFile();
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            SwingUtilities.invokeLater(() -> bar.setValue(pct));
                        }
                    }
                    out.flush();
                }
                result[0] = true;
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    JOptionPane.showMessageDialog(parent, "语言包下载完成，可开始使用识图功能。", "提示",
                            JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    JOptionPane.showMessageDialog(parent,
                            "语言包下载失败：" + ex.getMessage()
                                    + "\n您可以手动下载 chi_sim.traineddata 并放入 data\\tessdata\\ 目录。",
                            "下载失败", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
        worker.setDaemon(true);
        worker.start();

        dlg.setVisible(true);
        return result[0];
    }
}
