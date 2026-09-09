package com.mimavault.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * OpenCV 人脸能力引擎（Apache 2.0，纯本地零联网）。
 *
 * 负责：库加载 / Haar 人脸检测 / 人脸模板提取 / 模板与实时帧比对。
 * 模板统一为 96x96 灰度 + 直方图均衡的 PNG 字节，比对用归一化互相关(NCC)，
 * 阈值由 {@link #MATCH_THRESHOLD} 控制（MVP 演示档，无活体检测）。
 */
public final class FaceEngine {

    private static final String TAG = "FaceEngine";
    private static final String CASCADE_NAME = "haarcascade_frontalface_alt2.xml";

    /** 模板尺寸：96x96 灰度，兼顾特征与比对速度 */
    public static final int TPL_SIZE = 96;
    /** 人脸匹配阈值（0~1），实测 0.85 上下；阈值越低越宽松，演示环境可放宽 */
    public static final double MATCH_THRESHOLD = 0.82d;
    /** 连续命中多少帧判定解锁成功（防单帧抖动误放行） */
    public static final int HIT_FRAMES = 3;
    /** 检测间隔毫秒（Haar 较慢，不必逐帧跑） */
    public static final long DETECT_INTERVAL_MS = 200L;

    private static volatile boolean sLoaded = false;
    private static volatile CascadeClassifier sCascade;

    private FaceEngine() {
    }

    /** 加载 OpenCV 与级联分类器；返回是否可用 */
    public static synchronized boolean init(Context context) {
        if (sLoaded && sCascade != null) {
            return true;
        }
        try {
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV initDebug failed");
                return false;
            }
            sCascade = new CascadeClassifier(cascadePath(context).getAbsolutePath());
            if (sCascade.empty()) {
                Log.e(TAG, "cascade load failed");
                sCascade = null;
                return false;
            }
            sLoaded = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "OpenCV init error", t);
            return false;
        }
    }

    /** assets 中的 Haar 级联文件释放到私有目录（CascadeClassifier 需要文件路径） */
    private static File cascadePath(Context context) {
        File dir = new File(context.getFilesDir(), "opencv_cascades");
        File target = new File(dir, CASCADE_NAME);
        if (target.exists() && target.length() > 1000) {
            return target;
        }
        dir.mkdirs();
        try (InputStream in = context.getAssets().open(CASCADE_NAME);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "cascade copy failed", e);
        }
        return target;
    }

    /** 在灰度图（YUV Y 通道即可）上检测最大人脸，无则返回 null */
    public static Rect detectLargestFace(Mat gray) {
        if (gray == null || gray.empty() || sCascade == null) {
            return null;
        }
        MatOfRect faces = new MatOfRect();
        try {
            sCascade.detectMultiScale(gray, faces, 1.1, 4, 2,
                    new Size(Math.max(60, gray.width() / 6), Math.max(60, gray.width() / 6)),
                    new Size(gray.width(), gray.height()));
            Rect[] arr = faces.toArray();
            if (arr.length == 0) {
                return null;
            }
            Rect best = arr[0];
            for (Rect r : arr) {
                if (r.area() > best.area()) {
                    best = r;
                }
            }
            return best;
        } finally {
            faces.release();
        }
    }

    /**
     * 从灰度帧中提取标准人脸模板：按检测框外扩 1.25 倍裁出人脸区域 →
     * resize 至 96x96 → 直方图均衡 → PNG 编码。外扩包含额头/下巴周边信息，
     * 提升同一人脸不同取景的比对稳定性。
     */
    public static byte[] extractTemplate(Mat gray, Rect face) {
        if (face == null || gray == null) {
            return null;
        }
        int cx = face.x + face.width / 2;
        int cy = face.y + face.height / 2;
        int half = (int) (Math.max(face.width, face.height) * 0.66f); // 0.66*2=1.32 倍外扩
        int x0 = Math.max(0, cx - half);
        int y0 = Math.max(0, cy - half);
        int x1 = Math.min(gray.width(), cx + half);
        int y1 = Math.min(gray.height(), cy + half);
        if (x1 - x0 < 40 || y1 - y0 < 40) {
            return null;
        }
        Mat crop = new Mat(gray, new Rect(x0, y0, x1 - x0, y1 - y0));
        Mat norm = new Mat();
        try {
            Imgproc.resize(crop, norm, new Size(TPL_SIZE, TPL_SIZE), 0, 0, Imgproc.INTER_AREA);
            Imgproc.equalizeHist(norm, norm);
            MatOfByte buf = new MatOfByte();
            try {
                boolean ok = Imgcodecs.imencode(".png", norm, buf);
                if (!ok || buf.empty()) {
                    return null;
                }
                byte[] out = buf.toArray();
                buf.release();
                return out;
            } catch (Exception e) {
                if (!buf.empty()) {
                    buf.release();
                }
                Log.e(TAG, "template encode failed", e);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "template extract failed", e);
            return null;
        } finally {
            crop.release();
            norm.release();
        }
    }

    /** PNG 模板字节 → 96x96 灰度 Mat（比对基准），失败返回 null */
    public static Mat decodeTemplate(byte[] png) {
        if (png == null || png.length == 0) {
            return null;
        }
        MatOfByte buf = new MatOfByte(png);
        try {
            Mat img = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_GRAYSCALE);
            if (img == null || img.empty()) {
                return null;
            }
            if (img.cols() != TPL_SIZE || img.rows() != TPL_SIZE) {
                Mat resized = new Mat();
                Imgproc.resize(img, resized, new Size(TPL_SIZE, TPL_SIZE));
                img.release();
                img = resized;
            }
            return img;
        } finally {
            buf.release();
        }
    }

    /**
     * 归一化互相关（NCC）：两张同尺寸灰度图逐像素比较，输出 [-1,1]。
     * 全同 1.0，同一人不同光照/取景通常在 0.85+，不同人通常显著低于阈值。
     */
    public static double compare(Mat a, Mat b) {
        if (a == null || b == null || a.empty() || b.empty()
                || a.rows() != b.rows() || a.cols() != b.cols()) {
            return 0d;
        }
        int total = a.rows() * a.cols();
        if (total == 0) {
            return 0d;
        }
        byte[] ba = new byte[total];
        byte[] bb = new byte[total];
        a.get(0, 0, ba);
        b.get(0, 0, bb);
        double meanA = 0, meanB = 0;
        for (int i = 0; i < total; i++) {
            meanA += (ba[i] & 0xFF);
            meanB += (bb[i] & 0xFF);
        }
        meanA /= total;
        meanB /= total;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < total; i++) {
            double va = (ba[i] & 0xFF) - meanA;
            double vb = (bb[i] & 0xFF) - meanB;
            num += va * vb;
            da += va * va;
            db += vb * vb;
        }
        double den = Math.sqrt(da) * Math.sqrt(db);
        return den <= 1e-9 ? 0d : num / den;
    }

    /** 便捷工具：把 Bitmap 解码为 OpenCV BGR Mat（释放责任在调用方） */
    public static Mat bitmapToMat(Bitmap bmp) {
        Mat m = new Mat();
        org.opencv.android.Utils.bitmapToMat(bmp, m);
        return m;
    }

    /** 便捷工具：Android Bitmap 解码（供模板缩略图等使用） */
    public static Bitmap bitmapFromPng(byte[] png) {
        return BitmapFactory.decodeByteArray(png, 0, png.length);
    }

    /** 旋转灰度图到竖屏方向（sensorOrientation 决定旋转角度） */
    public static Mat rotateToPortrait(Mat src, int sensorOrientation) {
        int rot = sensorOrientation % 360;
        Mat dst = new Mat();
        try {
            if (rot == 90) {
                Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE);
            } else if (rot == 270) {
                Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE);
            } else if (rot == 180) {
                Core.rotate(src, dst, Core.ROTATE_180);
            } else {
                src.copyTo(dst);
            }
            return dst;
        } catch (Exception e) {
            if (!dst.empty()) {
                dst.release();
            }
            return src;
        }
    }
}
