package com.mimavault.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.model.BackupModel;
import com.mimavault.service.BackupService;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 二维码导入：逐段扫描或从剪贴板粘贴，拼装后恢复
 */
public class QrImportActivity extends AppCompatActivity {

    public static void start(android.app.Activity from) {
        from.startActivity(new android.content.Intent(from, QrImportActivity.class));
    }

    private final TreeMap<Integer, String> collected = new TreeMap<>();
    private int totalSegments = 0;
    private TextView tvStatus;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() == null) {
                    Toast.makeText(this, "已取消扫描", Toast.LENGTH_SHORT).show();
                    return;
                }
                String text = result.getContents().trim();
                if (text.startsWith("MVQR1|")) {
                    parseSegment(text);
                } else {
                    // 单段完整包（小数据量场景）
                    doImport(text);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_import);
        tvStatus = findViewById(R.id.tvQrImportStatus);

        findViewById(R.id.btnQrScan).setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("扫描密匣导出二维码（第 " + (collected.size() + 1) + " 段）");
            options.setBeepEnabled(false);
            scanLauncher.launch(options);
        });

        findViewById(R.id.btnQrPaste).setOnClickListener(v -> pasteFromClipboard());

        findViewById(R.id.btnQrImportNow).setOnClickListener(v -> {
            if (collected.isEmpty()) {
                Toast.makeText(this, "请先扫描或粘贴内容", Toast.LENGTH_SHORT).show();
                return;
            }
            if (totalSegments > 0 && collected.size() < totalSegments) {
                Toast.makeText(this, "已收集 " + collected.size() + "/" + totalSegments + " 段，请继续扫描", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String s : collected.values()) {
                sb.append(s);
            }
            doImport(sb.toString());
        });

        updateStatus();
    }

    private void parseSegment(String seg) {
        try {
            String[] parts = seg.split("\\|", 4);
            int idx = Integer.parseInt(parts[1].split("/")[0]);
            totalSegments = Integer.parseInt(parts[1].split("/")[1]);
            collected.put(idx, parts[3]);
            updateStatus();
            if (collected.size() >= totalSegments) {
                Toast.makeText(this, "全部 " + totalSegments + " 段已收集，可点击开始恢复", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已收集 " + collected.size() + "/" + totalSegments + " 段，继续扫描下一段", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "段数据格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = cd.getItemAt(0).coerceToText(this).toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.startsWith("MVQR1|")) {
            parseSegment(text);
        } else {
            doImport(text);
        }
    }

    private void updateStatus() {
        String s;
        if (totalSegments == 0) {
            s = "未开始扫描";
        } else {
            s = "已收集 " + collected.size() + " / " + totalSegments + " 段";
        }
        tvStatus.setText(s);
    }

    private void doImport(String fullText) {
        if (VaultSession.get().masterPasswordCopy() == null) {
            Toast.makeText(this, "请使用主密码解锁后导入", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("二维码导入")
                .setItems(new String[]{getString(R.string.merge_import), getString(R.string.overwrite_import)}, (d, which) -> {
                    boolean overwrite = which == 1;
                    new Thread(() -> {
                        try {
                            BackupService.ImportResult result = BackupService.importFromContentDetailed(
                                    fullText, VaultSession.get().masterPasswordCopy(), VaultSession.get().key());
                            BackupModel.BackupPackage pack = result.pack;
                            int[] stat = BackupService.restore(pack, new PasswordService(MimaVaultApp.db()), overwrite,
                                    result.backupKey, VaultSession.get().key());
                            main.post(() -> {
                                Toast.makeText(this, getString(R.string.import_success, stat[0], stat[1]), Toast.LENGTH_LONG).show();
                                collected.clear();
                                totalSegments = 0;
                                updateStatus();
                            });
                        } catch (Exception e) {
                            main.post(() -> Toast.makeText(this, getString(R.string.import_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
