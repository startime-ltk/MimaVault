package com.mimavault.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.util.InsetsUtil;
import com.mimavault.model.Entry;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;
import com.mimavault.util.AesUtil;
import com.mimavault.util.ClipboardUtil;
import com.mimavault.util.GestureParser;
import com.mimavault.util.ImageUtil;
import com.mimavault.util.PasswordStrengthUtil;
import com.mimavault.util.TotpUtil;

import java.io.File;

/**
 * 条目详情：显示明文/掩码、复制30秒清除、弱密码提醒、手势图、图片附件
 */
public class DetailActivity extends AppCompatActivity {

    public static void start(android.app.Activity from, long entryId) {
        Intent i = new Intent(from, DetailActivity.class);
        i.putExtra("id", entryId);
        from.startActivity(i);
    }

    private PasswordService service;
    private Entry entry;
    private String decryptedPwd = null;
    private boolean showing = false;

    private TextView tvPlatform;
    private TextView tvCategory;
    private TextView tvAccount;
    private TextView tvPhone;
    private TextView tvEmail;
    private TextView tvPassword;
    private TextView tvStrength;
    private TextView tvNote;
    private LinearLayout gestureBox;
    private LinearLayout imageBox;
    private TextView tvGestureText;

    // 动态验证码（TOTP）
    private LinearLayout totpBox;
    private TextView tvTotpCode;
    private TextView tvTotpCountdown;
    private ProgressBar pbTotp;
    private TotpUtil.Config totpConfig;
    private final Handler totpHandler = new Handler(Looper.getMainLooper());
    private final Runnable totpTick = new Runnable() {
        @Override
        public void run() {
            if (totpConfig == null) {
                return;
            }
            refreshTotp();
            totpHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        InsetsUtil.applyTopInset(findViewById(R.id.rootDetailScroll));
        service = new PasswordService(MimaVaultApp.db());
        long id = getIntent().getLongExtra("id", -1);
        entry = service.getById(id);
        if (entry == null) {
            Toast.makeText(this, "条目不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        tvPlatform = findViewById(R.id.tvPlatform);
        tvCategory = findViewById(R.id.tvCategory);
        tvAccount = findViewById(R.id.tvAccount);
        tvPhone = findViewById(R.id.tvPhone);
        tvEmail = findViewById(R.id.tvEmail);
        tvPassword = findViewById(R.id.tvPassword);
        tvStrength = findViewById(R.id.tvStrength);
        tvNote = findViewById(R.id.tvNote);
        gestureBox = findViewById(R.id.gestureBox);
        imageBox = findViewById(R.id.imageBox);
        tvGestureText = findViewById(R.id.tvGestureText);
        totpBox = findViewById(R.id.totpBox);
        tvTotpCode = findViewById(R.id.tvTotpCode);
        tvTotpCountdown = findViewById(R.id.tvTotpCountdown);
        pbTotp = findViewById(R.id.pbTotp);
        findViewById(R.id.btnTotpCopy).setOnClickListener(v -> copyTotpCode());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copyPassword());
        findViewById(R.id.btnToggle).setOnClickListener(v -> togglePassword());
        findViewById(R.id.btnEdit).setOnClickListener(v -> EditEntryActivity.start(this, entry.getId()));
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btnHistory).setOnClickListener(v ->
                PasswordHistoryActivity.start(this, entry.getId()));

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (entry != null) {
            entry = service.getById(entry.getId());
            if (entry != null) {
                decryptedPwd = null;
                showing = false;
                render();
            }
        }
        totpHandler.removeCallbacks(totpTick);
        if (totpConfig != null) {
            totpHandler.post(totpTick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面不可见立即停表，避免后台空转与验证码泄漏到快照
        totpHandler.removeCallbacks(totpTick);
    }

    /** 读取并解密 TOTP 配置；无绑定或解密失败时隐藏卡片 */
    private void renderTotp() {
        totpConfig = null;
        String enc = entry.getTotpSecretEnc();
        if (enc == null || enc.isEmpty()) {
            totpBox.setVisibility(View.GONE);
            return;
        }
        try {
            String uri = AesUtil.decrypt(enc, VaultSession.get().key());
            TotpUtil.Config c = TotpUtil.parse(uri);
            totpConfig = c;
            totpBox.setVisibility(View.VISIBLE);
            refreshTotp();
        } catch (Exception e) {
            // 密钥不可解（例如改主密码期间的旧密文）时不展示，避免显示错误验证码
            totpBox.setVisibility(View.GONE);
        }
    }

    /** 每秒刷新验证码与剩余时间 */
    private void refreshTotp() {
        if (totpConfig == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String code = TotpUtil.generate(totpConfig, now);
        tvTotpCode.setText(groupCode(code));
        int remain = TotpUtil.remainingSeconds(totpConfig.period, now);
        pbTotp.setMax(totpConfig.period);
        pbTotp.setProgress(remain);
        tvTotpCountdown.setText(getString(R.string.totp_remain, remain));
    }

    /** 6 位验证码按 3+3 分组显示，便于核对 */
    private String groupCode(String code) {
        if (code == null || code.length() < 6) {
            return code == null ? "" : code;
        }
        if (code.length() == 6) {
            return code.substring(0, 3) + " " + code.substring(3);
        }
        return code;
    }

    private void copyTotpCode() {
        if (totpConfig == null) {
            return;
        }
        String code = TotpUtil.generate(totpConfig, System.currentTimeMillis());
        if (code.isEmpty()) {
            return;
        }
        ClipboardUtil.copyWithAutoClear(this, code);
        Toast.makeText(this, R.string.totp_copied, Toast.LENGTH_SHORT).show();
    }

    private void render() {
        ((android.widget.Button) findViewById(R.id.btnHistory)).setText(
                getString(R.string.history_entry, service.countPasswordHistory(entry.getId())));
        renderTotp();
        tvPlatform.setText(entry.getPlatform());
        tvCategory.setText(entry.getCategory());
        String account = entry.getAccount();
        tvAccount.setText(account == null || account.isEmpty() ? "—" : account);
        String phone = entry.getPhone();
        tvPhone.setText(phone == null || phone.isEmpty() ? "—" : phone);
        String email = entry.getEmail();
        tvEmail.setText(email == null || email.isEmpty() ? "—" : email);
        String note = entry.getNote();
        tvNote.setText(note == null || note.isEmpty() ? "—" : note);

        if (entry.getPasswordEnc() == null || entry.getPasswordEnc().isEmpty()) {
            tvPassword.setText("（未设置密码）");
            tvStrength.setText("—");
        } else {
            tvPassword.setText(showing ? "••••••••" : "••••••••");
            if (showing) {
                String pwd = tryDecrypt();
                tvPassword.setText(pwd == null ? "••••••••" : pwd);
            }
            String pwdForScore = tryDecrypt();
            if (pwdForScore == null || pwdForScore.isEmpty()) {
                tvStrength.setText("—");
            } else {
                PasswordStrengthUtil.Result r = PasswordStrengthUtil.evaluate(pwdForScore);
                tvStrength.setText(r.toString());
                tvStrength.setTextColor(getColor(r.level == PasswordStrengthUtil.Level.WEAK
                        ? R.color.warning : R.color.success));
            }
        }

        // 手势
        gestureBox.removeAllViews();
        if (entry.getGestureSeq() != null && !entry.getGestureSeq().isEmpty()) {
            gestureBox.setVisibility(View.VISIBLE);
            tvGestureText.setText("九宫格手势：" + GestureParser.describe(entry.getGestureSeq()));
            Bitmap bmp = GestureParser.render(entry.getGestureSeq(), 260, 260);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setPadding(dp(12), dp(12), dp(12), dp(12));
            iv.setBackgroundResource(R.drawable.bg_input);
            gestureBox.addView(iv);
        } else {
            gestureBox.setVisibility(View.GONE);
        }

        // 图片附件
        imageBox.removeAllViews();
        if (entry.getImagePath() != null && !entry.getImagePath().isEmpty()) {
            imageBox.setVisibility(View.VISIBLE);
            File f = ImageUtil.resolve(this, entry.getImagePath());
            if (f != null && f.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bmp != null) {
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setAdjustViewBounds(true);
                    iv.setMaxWidth(dp(320));
                    iv.setMaxHeight(dp(420));
                    iv.setPadding(dp(8), dp(8), dp(8), dp(8));
                    iv.setBackgroundResource(R.drawable.bg_input);
                    iv.setOnClickListener(v -> showImageFull(bmp));
                    imageBox.addView(iv);
                }
            }
        } else {
            imageBox.setVisibility(View.GONE);
        }
    }

    private void showImageFull(Bitmap bmp) {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setMaxWidth(dp(600));
        iv.setMaxHeight(dp(800));
        new AlertDialog.Builder(this)
                .setTitle("图片附件")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void copyPassword() {
        String pwd = tryDecrypt();
        if (pwd == null) {
            return;
        }
        ClipboardUtil.copyWithAutoClear(this, pwd);
        Toast.makeText(this, "密码已复制，30 秒后自动清除剪贴板", Toast.LENGTH_LONG).show();
    }

    private String tryDecrypt() {
        if (entry.getPasswordEnc() == null || entry.getPasswordEnc().isEmpty()) {
            return null;
        }
        if (decryptedPwd == null) {
            try {
                decryptedPwd = service.decryptPassword(entry, VaultSession.get().key());
            } catch (Exception e) {
                return null;
            }
        }
        return decryptedPwd;
    }

    private void togglePassword() {
        if (entry.getPasswordEnc() == null || entry.getPasswordEnc().isEmpty()) {
            return;
        }
        showing = !showing;
        render();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除条目")
                .setMessage("确定删除「" + entry.getPlatform() + "」吗？")
                .setPositiveButton("删除", (d, w) -> {
                    service.delete(entry.getId());
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
