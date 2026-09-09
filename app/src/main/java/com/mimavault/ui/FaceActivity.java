package com.mimavault.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.face.FaceCamera;
import com.mimavault.face.FaceEngine;
import com.mimavault.service.FaceUnlockHelper;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;
import com.mimavault.util.InsetsUtil;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;

/**
 * 人脸解锁 / 人脸录入（OpenCV 本地摄像头方案）。
 *
 * 两种模式由 {@link #EXTRA_MODE} 区分：
 * - MODE_REGISTER：主密码已验证（会话已打开）时录入人脸模板并加密保存；
 * - MODE_UNLOCK：解锁页发起，实时摄像头比对，连续多帧命中后恢复会话进入主界面。
 *
 * 失败保护：MODE_UNLOCK 累计尝试超 {@link FaceUnlockHelper#MAX_ATTEMPTS} 次后锁定人脸通道
 * （回到主密码 / 手势 / 系统生物识别），本页自动退出。
 */
public class FaceActivity extends AppCompatActivity implements FaceCamera.FrameListener {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_REGISTER = "register";
    public static final String MODE_UNLOCK = "unlock";

    private static final String TAG = "FaceActivity";
    private static final int REQ_CAMERA = 1001;
    /** 录入前需人脸连续稳定出现的最少检测帧数 */
    private static final int STABLE_FRAMES = 4;
    /** 录入需要的最低清晰度（灰度标准差） */
    private static final double MIN_STD = 14d;
    /** 解锁模式：无成功比对的累计耗时（毫秒）达到该值计一次失败尝试 */
    private static final long FAIL_AFTER_MS = 7000L;

    private String mode = MODE_REGISTER;
    private TextureView textureView;
    private FaceOverlayView overlay;
    private TextView tvStatus;
    private Button btnAction;
    private FaceCamera camera;
    private boolean permissionGranted;
    private final Handler main = new Handler(Looper.getMainLooper());

    // 状态（相机线程读写）
    private volatile boolean finished;
    private long lastDetectTs;
    private int stableCount;
    private Mat templateMat;          // MODE_UNLOCK 比对基准
    private byte[] pendingTemplate;   // MODE_REGISTER 最优模板 png
    private double bestScore;         // MODE_REGISTER 已采帧的最高质量
    private int hitCount;             // MODE_UNLOCK 连续命中
    private long attemptStartTs;      // MODE_UNLOCK 失败计时
    private int failCount;
    private FaceUnlockHelper.FaceRecord faceRecord;

    public static void startRegister(Context ctx) {
        Intent i = new Intent(ctx, FaceActivity.class);
        i.putExtra(EXTRA_MODE, MODE_REGISTER);
        ctx.startActivity(i);
    }

    public static void startUnlock(Context ctx) {
        Intent i = new Intent(ctx, FaceActivity.class);
        i.putExtra(EXTRA_MODE, MODE_UNLOCK);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) {
            mode = MODE_REGISTER;
        }
        InsetsUtil.applyTopInset(findViewById(R.id.rootFace));

        textureView = findViewById(R.id.facePreview);
        overlay = findViewById(R.id.faceOverlay);
        tvStatus = findViewById(R.id.tvFaceStatus);
        btnAction = findViewById(R.id.btnFaceAction);

        if (MODE_UNLOCK.equals(mode)) {
            btnAction.setText(R.string.face_use_password);
            btnAction.setOnClickListener(v -> finish());
        } else {
            btnAction.setText(R.string.face_action_back);
            btnAction.setOnClickListener(v -> finish());
        }
        findViewById(R.id.btnFaceClose).setOnClickListener(v -> finish());

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                tryStartCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                closeCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {
            }
        });

        if (!FaceEngine.init(this)) {
            Toast.makeText(this, R.string.face_engine_fail, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (MODE_UNLOCK.equals(mode)) {
            faceRecord = FaceUnlockHelper.load(this);
            if (faceRecord == null) {
                Toast.makeText(this, R.string.face_data_missing, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            templateMat = FaceEngine.decodeTemplate(faceRecord.templatePng);
            attemptStartTs = System.currentTimeMillis();
        }
        requestCameraIfNeeded();
    }

    private void requestCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            permissionGranted = true;
            tryStartCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permissionGranted = true;
                tryStartCamera();
            } else {
                Toast.makeText(this, R.string.face_camera_permission, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void tryStartCamera() {
        if (!permissionGranted || !textureView.isAvailable() || camera != null || finished) {
            return;
        }
        new Thread(() -> {
            try {
                Size sz = FaceCamera.resolvePreviewSize(FaceActivity.this);
                final Size fsz = sz;
                main.post(() -> {
                    try {
                        textureView.getSurfaceTexture().setDefaultBufferSize(fsz.getWidth(), fsz.getHeight());
                        applyPreviewTransform(fsz.getWidth(), fsz.getHeight());
                        camera = new FaceCamera(FaceActivity.this, fsz,
                                new Surface(textureView.getSurfaceTexture()), FaceActivity.this);
                        camera.open();
                    } catch (Exception e) {
                        Log.e(TAG, "camera start error", e);
                        Toast.makeText(FaceActivity.this, R.string.face_camera_fail, Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "resolve size error", e);
                main.post(() -> {
                    Toast.makeText(FaceActivity.this, R.string.face_camera_fail, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "FaceCameraResolve").start();
    }

    /** 预览旋转 + 前置镜像适配竖屏显示 */
    private void applyPreviewTransform(int bufW, int bufH) {
        int vw = textureView.getWidth();
        int vh = textureView.getHeight();
        if (vw == 0 || vh == 0) {
            return;
        }
        // 相机帧 rotate 90 后为竖屏内容：宽 bufH、高 bufW
        float scale = Math.min((float) vw / bufH, (float) vh / bufW);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        // 旋转 90 度（顺时针）：先把内容原点移动到中心再旋转，模拟 rotate90CW
        float cx = vw / 2f;
        float cy = vh / 2f;
        m.postRotate(90f, cx, cy);
        // 前置镜像：显示自然自拍视角
        m.postScale(-1f, 1f, cx, cy);
        textureView.setTransform(m);
    }

    @Override
    public void onGrayFrame(byte[] grayY, int w, int h) {
        if (finished || camera == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDetectTs < FaceEngine.DETECT_INTERVAL_MS) {
            return;
        }
        lastDetectTs = now;
        try {
            Mat gray = new Mat(h, w, org.opencv.core.CvType.CV_8UC1);
            gray.put(0, 0, grayY);
            Mat portrait = FaceEngine.rotateToPortrait(gray, camera.getSensorOrientation());
            gray.release();
            Rect face = FaceEngine.detectLargestFace(portrait);
            if (MODE_REGISTER.equals(mode)) {
                handleRegisterFrame(portrait, face);
            } else {
                handleUnlockFrame(portrait, face);
            }
            if (portrait != null) {
                portrait.release();
            }
        } catch (Throwable t) {
            Log.e(TAG, "frame process error", t);
        }
    }

    @Override
    public void onError(String message) {
        main.post(() -> {
            if (!finished) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    // ---------- 录入模式 ----------

    private void handleRegisterFrame(Mat portrait, Rect face) {
        if (finished || pendingTemplate != null) {
            return;
        }
        if (face == null) {
            stableCount = 0;
            postStatus(R.string.face_register_wait);
            return;
        }
        Mat norm = cropToNorm(portrait, face);
        if (norm == null) {
            stableCount = 0;
            return;
        }
        try {
            double std = stdDev(norm);
            if (std < MIN_STD) {
                stableCount = 0;
                postStatus(R.string.face_register_sharp);
                return;
            }
            stableCount++;
            if (stableCount >= STABLE_FRAMES && face.width >= Math.max(80, portrait.width() / 6)) {
                byte[] tpl = FaceEngine.extractTemplate(portrait, face);
                if (tpl != null) {
                    pendingTemplate = tpl;
                    postStatus(R.string.face_register_ok);
                    saveTemplate();
                }
            } else {
                postStatus(R.string.face_register_hold);
            }
        } finally {
            norm.release();
        }
    }

    private void saveTemplate() {
        main.post(() -> {
            if (finished) {
                return;
            }
            finished = true;
            closeCamera();
            try {
                VaultSession session = VaultSession.get();
                if (!session.isOpen()) {
                    Toast.makeText(this, R.string.face_session_closed, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                FaceUnlockHelper.setup(this, pendingTemplate, session.key().getEncoded(),
                        session.masterSaltHex(), session.iterations());
                FaceUnlockHelper.resetFails(this);
                Toast.makeText(this, R.string.face_saved, Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
            } catch (Exception e) {
                Log.e(TAG, "save face failed", e);
                Toast.makeText(this, "人脸保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }

    // ---------- 解锁模式 ----------

    private void handleUnlockFrame(Mat portrait, Rect face) {
        if (finished || templateMat == null) {
            return;
        }
        if (face == null) {
            hitCount = 0;
            postStatus(R.string.face_unlock_wait);
            checkFailTimer();
            return;
        }
        Mat live = cropToNorm(portrait, face);
        if (live == null) {
            return;
        }
        try {
            double score = FaceEngine.compare(templateMat, live);
            if (score >= FaceEngine.MATCH_THRESHOLD) {
                hitCount++;
                if (hitCount >= FaceEngine.HIT_FRAMES) {
                    onUnlockSuccess();
                    return;
                }
                postStatus(getString(R.string.face_unlock_matching, (int) (score * 100)));
            } else {
                hitCount = 0;
                postStatus(getString(R.string.face_unlock_no_match, (int) (score * 100)));
                checkFailTimer();
            }
        } finally {
            live.release();
        }
    }

    private void checkFailTimer() {
        long now = System.currentTimeMillis();
        if (now - attemptStartTs < FAIL_AFTER_MS) {
            return;
        }
        attemptStartTs = now;
        failCount++;
        Log.i(TAG, "face unlock attempt fail count=" + failCount);
        if (failCount >= FaceUnlockHelper.MAX_ATTEMPTS) {
            main.post(() -> {
                Toast.makeText(this, R.string.face_locked_hint, Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    private void onUnlockSuccess() {
        if (finished) {
            return;
        }
        finished = true;
        FaceUnlockHelper.resetFails(this);
        main.post(() -> {
            closeCamera();
            // 会话开启与进入主界面由发起方（UnlockActivity.onActivityResult）完成
            setResult(RESULT_OK);
            finish();
        });
    }

    // ---------- 工具 ----------

    /** 把人脸框裁剪到 96x96 标准模板 Mat（与模板同 pipeline，用于比对） */
    private Mat cropToNorm(Mat portrait, Rect face) {
        byte[] png = FaceEngine.extractTemplate(portrait, face);
        if (png == null) {
            return null;
        }
        Mat m = FaceEngine.decodeTemplate(png);
        if (m == null) {
            postStatus(R.string.face_process_fail);
        }
        return m;
    }

    private double stdDev(Mat gray) {
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        try {
            Core.meanStdDev(gray, mean, std);
            return std.toArray()[0];
        } finally {
            mean.release();
            std.release();
        }
    }

    private void postStatus(final String text) {
        main.post(() -> {
            if (tvStatus != null && !finished) {
                tvStatus.setText(text);
            }
        });
    }

    private void postStatus(int resId) {
        postStatus(getString(resId));
    }

    private void closeCamera() {
        if (camera != null) {
            FaceCamera c = camera;
            camera = null;
            c.close();
        }
    }

    @Override
    protected void onDestroy() {
        finished = true;
        closeCamera();
        if (templateMat != null) {
            templateMat.release();
            templateMat = null;
        }
        super.onDestroy();
    }
}
