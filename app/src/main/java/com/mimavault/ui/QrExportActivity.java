package com.mimavault.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.model.Entry;
import com.mimavault.service.BackupService;
import com.mimavault.service.VaultSession;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 二维码导出：把 .pmaster 内容分段生成二维码，逐页显示
 */
public class QrExportActivity extends AppCompatActivity {

    public static void start(android.app.Activity from) {
        from.startActivity(new android.content.Intent(from, QrExportActivity.class));
    }

    private static final int SEGMENT_CHARS = 1100;

    private ImageView qrImage;
    private TextView tvPage;
    private TextView tvCount;
    private List<String> segments = new ArrayList<>();
    private int page = 0;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_export);
        qrImage = findViewById(R.id.qrImage);
        tvPage = findViewById(R.id.tvQrPage);
        tvCount = findViewById(R.id.tvQrCount);
        findViewById(R.id.btnQrPrev).setOnClickListener(v -> showPage(page - 1));
        findViewById(R.id.btnQrNext).setOnClickListener(v -> showPage(page + 1));

        tvCount.setText("正在生成…");
        new Thread(() -> {
            try {
                List<Entry> entries = new com.mimavault.service.PasswordService(MimaVaultApp.db()).listEntries();
                String full = BackupService.buildExportContent(entries, VaultSession.get().key(),
                        VaultSession.get().masterSaltHex(), VaultSession.get().iterations());
                segments.clear();
                int total = (full.length() + SEGMENT_CHARS - 1) / SEGMENT_CHARS;
                for (int i = 0; i < total; i++) {
                    int start = i * SEGMENT_CHARS;
                    int end = Math.min(full.length(), start + SEGMENT_CHARS);
                    segments.add(String.format("MVQR1|%d/%d|%s", i + 1, total, full.substring(start, end)));
                }
                main.post(() -> {
                    tvCount.setText("共 " + segments.size() + " 段");
                    showPage(0);
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showPage(int p) {
        if (segments.isEmpty()) {
            return;
        }
        if (p < 0) {
            p = 0;
        }
        if (p >= segments.size()) {
            p = segments.size() - 1;
        }
        page = p;
        tvPage.setText("第 " + (page + 1) + " / " + segments.size() + " 段");
        Bitmap bmp = makeQr(segments.get(page), 720);
        qrImage.setImageBitmap(bmp);
    }

    private Bitmap makeQr(String content, int size) {
        try {
            java.util.Map<EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
