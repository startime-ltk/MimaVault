package com.mimavault.face;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Camera2 轻量封装：将预览流送到 TextureView Surface，同时取 YUV_420_888 的
 * Y 平面灰度帧供 OpenCV Haar 人脸检测使用（跳过颜色处理）。
 * 前置摄像头优先；分辨率由 {@link #resolvePreviewSize(Context)} 预先确定。
 */
public final class FaceCamera {

    private static final String TAG = "FaceCamera";

    public interface FrameListener {
        /** grayY: Y 平面数据（已按 width 紧凑打包），w/h 为帧尺寸 */
        void onGrayFrame(byte[] grayY, int w, int h);

        void onError(String message);
    }

    private final Context context;
    private final Size size;
    private final Surface previewSurface;
    private final FrameListener listener;
    private final HandlerThread thread = new HandlerThread("FaceCamera");
    private Handler handler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private int sensorOrientation = 90;
    private boolean closed = false;

    public FaceCamera(Context context, Size size, Surface previewSurface, FrameListener listener) {
        this.context = context.getApplicationContext();
        this.size = size;
        this.previewSurface = previewSurface;
        this.listener = listener;
    }

    public int getSensorOrientation() {
        return sensorOrientation;
    }

    /**
     * 同步解析预览尺寸：优先前置摄像头，取支持 YUV_420_888 且最接近 640x480
     * （不低于 300x300）的档位。
     */
    public static Size resolvePreviewSize(Context context) throws CameraAccessException {
        CameraManager cm = (CameraManager) context.getApplicationContext()
                .getSystemService(Context.CAMERA_SERVICE);
        Size fallback = new Size(640, 480);
        List<String> ids = Arrays.asList(cm.getCameraIdList());
        String[] order = ids.toArray(new String[0]);
        for (String id : order) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (facing == null
                    || facing != CameraCharacteristics.LENS_FACING_FRONT) {
                continue;
            }
            return pickSize(ch, 640, 480, fallback);
        }
        // 无前置回退到后置，仍无则用默认
        if (!ids.isEmpty()) {
            try {
                return pickSize(cm.getCameraCharacteristics(ids.get(0)), 640, 480, fallback);
            } catch (Exception ignore) {
                // ignore
            }
        }
        return fallback;
    }

    private static Size pickSize(CameraCharacteristics ch, int wantW, int wantH, Size fallback) {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return fallback;
        }
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return fallback;
        }
        List<Size> list = new ArrayList<>(Arrays.asList(sizes));
        Collections.sort(list, Comparator.comparingInt(s -> Math.abs(s.getWidth() - wantW)
                + Math.abs(s.getHeight() - wantH)));
        for (Size s : list) {
            if (s.getWidth() >= 300 && s.getHeight() >= 300) {
                return s;
            }
        }
        return list.get(0);
    }

    public void open() {
        if (!thread.isAlive()) {
            thread.start();
        }
        handler = new Handler(thread.getLooper());
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String selected = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    selected = id;
                    break;
                }
            }
            if (selected == null && cm.getCameraIdList().length > 0) {
                selected = cm.getCameraIdList()[0];
            }
            if (selected == null) {
                listener.onError("未找到可用摄像头");
                close();
                return;
            }
            CameraCharacteristics ch = cm.getCameraCharacteristics(selected);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(this::onFrame, handler);
            cm.openCamera(selected, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "camera disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "camera error " + error);
                    listener.onError("摄像头打开失败(" + error + ")");
                }
            }, handler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "open camera failed", e);
            listener.onError("摄像头不可用：" + e.getMessage());
        }
    }

    private void startPreview() {
        if (cameraDevice == null || imageReader == null || previewSurface == null) {
            return;
        }
        try {
            List<Surface> targets = new ArrayList<>(2);
            targets.add(previewSurface);
            targets.add(imageReader.getSurface());
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            for (Surface s : targets) {
                builder.addTarget(s);
            }
            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession s) {
                    session = s;
                    try {
                        CaptureRequest req = builder.build();
                        session.setRepeatingRequest(req, null, handler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "start repeating failed", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                    listener.onError("预览会话配置失败");
                }
            }, handler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "preview start failed", e);
            listener.onError("预览启动失败");
        }
    }

    private void onFrame(ImageReader reader) {
        if (closed) {
            return;
        }
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) {
                return;
            }
            Image.Plane yPlane = image.getPlanes()[0];
            int w = image.getWidth();
            int h = image.getHeight();
            int rowStride = yPlane.getRowStride();
            int pixelStride = yPlane.getPixelStride();
            byte[] raw = new byte[rowStride * h];
            yPlane.getBuffer().get(raw);
            byte[] compact;
            if (rowStride == w && pixelStride == 1) {
                compact = raw;
            } else {
                compact = new byte[w * h];
                int o = 0;
                for (int r = 0; r < h; r++) {
                    System.arraycopy(raw, r * rowStride, compact, o, w);
                    o += w;
                }
            }
            listener.onGrayFrame(compact, w, h);
        } catch (Exception e) {
            Log.e(TAG, "frame read failed", e);
        }
    }

    public void close() {
        closed = true;
        try {
            if (session != null) {
                session.close();
                session = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "camera close error", e);
        }
        if (thread.isAlive()) {
            thread.quitSafely();
        }
    }
}
