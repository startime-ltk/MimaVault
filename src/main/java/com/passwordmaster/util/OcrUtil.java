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

/**
 * 离线 OCR 识图工具（Tesseract + tess4j）
 * - 识别语言：中文 chi_sim
 * - 中文语言包已内置在 jar 的 /tessdata/chi_sim.traineddata（约 2.4MB）
 * - 首次使用自动释放到 data/tessdata/，完全零联网
 */
public final class OcrUtil {

    private static final String LANGUAGE = "chi_sim";
    private static final String TRAINEDDATA_NAME = "chi_sim.traineddata";
    /** jar 内内置语言包的 classpath 资源路径 */
    private static final String RESOURCE_PATH = "/tessdata/" + TRAINEDDATA_NAME;

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
     * 首次使用会自动从 jar 释放内置中文语言包；释放失败返回 null
     */
    public static String doOcr(File imageFile) throws Exception {
        return doOcr(imageFile, null);
    }

    /**
     * 识别图片中的文字（带父窗口）
     * 首次使用会自动从 jar 释放内置中文语言包（提示对话框以 parent 为宿主）；释放失败返回 null
     */
    public static String doOcr(File imageFile, Component parent) throws Exception {
        if (imageFile == null || !imageFile.exists()) {
            throw new IllegalArgumentException("图片文件不存在");
        }
        if (!isLanguageReady()) {
            boolean released = ensureLanguagePack(parent);
            if (!released) {
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
     * 从 jar 内置资源释放中文语言包到 data/tessdata/（完全零联网）。
     * 已存在且非空则直接跳过；释放失败弹窗提示并返回 false。
     *
     * @return true 释放成功或已就绪；false 释放失败（已弹窗提示）
     */
    private static boolean ensureLanguagePack(Component parent) {
        File target = getTrainedDataFile();
        if (target.exists() && target.length() > 0) {
            return true;
        }
        try (InputStream in = OcrUtil.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IOException("jar 内未找到内置语言包资源 " + RESOURCE_PATH);
            }
            File dir = getTessdataDir();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建目录 " + dir.getAbsolutePath());
            }
            // 先写 .tmp 临时文件，释放成功后再改名，避免中断残留半成品
            File tmp = new File(dir, TRAINEDDATA_NAME + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            if (tmp.length() == 0) {
                throw new IOException("语言包释放内容为空");
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("无法替换已存在的语言包文件");
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("语言包文件重命名失败");
            }
            return true;
        } catch (Exception ex) {
            // 清理残留 .tmp，保证 isLanguageReady 不误判、下次可重试
            try {
                File tmpFile = new File(getTessdataDir(), TRAINEDDATA_NAME + ".tmp");
                if (tmpFile.exists()) {
                    tmpFile.delete();
                }
            } catch (Exception ignored) {
            }
            JOptionPane.showMessageDialog(parent,
                    "内置中文语言包释放失败：" + ex.getMessage()
                            + "\n请确认程序对 data 目录有写入权限。",
                    "语言包初始化失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }
}
