package com.mimavault.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.LinkedHashMap;
import java.util.Map;

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
    private TextView btnFavorite;
    private LinearLayout gestureBox;
    private LinearLayout imageBox;
    private TextView tvGestureText;

    // 动态验证码（TOTP）
    private LinearLayout totpBox;
    private TextView tvTotpCode;
    private TextView tvTotpCountdown;
    private ProgressBar pbTotp;
    private TotpUtil.Config totpConfig;
    // 分类专属字段
    private LinearLayout extraBox;
    private Map<String, String> extraValues;
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
        btnFavorite = findViewById(R.id.btnFavorite);
        gestureBox = findViewById(R.id.gestureBox);
        imageBox = findViewById(R.id.imageBox);
        tvGestureText = findViewById(R.id.tvGestureText);
        totpBox = findViewById(R.id.totpBox);
        tvTotpCode = findViewById(R.id.tvTotpCode);
        tvTotpCountdown = findViewById(R.id.tvTotpCountdown);
        pbTotp = findViewById(R.id.pbTotp);
        extraBox = findViewById(R.id.extraBox);
        findViewById(R.id.btnTotpCopy).setOnClickListener(v -> copyTotpCode());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copyPassword());
        findViewById(R.id.btnToggle).setOnClickListener(v -> togglePassword());
        findViewById(R.id.btnEdit).setOnClickListener(v -> EditEntryActivity.start(this, entry.getId()));
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btnHistory).setOnClickListener(v ->
                PasswordHistoryActivity.start(this, entry.getId()));
        btnFavorite.setOnClickListener(v -> toggleFavorite());

        render();
    }

    /** 收藏/取消收藏，立即刷新星标 */
    private void toggleFavorite() {
        boolean next = !entry.isFavorite();
        service.setFavorite(entry.getId(), next);
        entry.setFavorite(next);
        renderFavorite();
        Toast.makeText(this, next ? R.string.favorited : R.string.unfavorited, Toast.LENGTH_SHORT).show();
    }

    /** 使用动作（复制密码/TOTP/专属字段）累加次数 */
    private void bumpUse() {
        service.incrementUseCount(entry.getId());
        entry.setUseCount(entry.getUseCount() + 1);
    }

    private void renderFavorite() {
        btnFavorite.setText(entry.isFavorite() ? "★" : "☆");
        btnFavorite.setTextColor(getColor(entry.isFavorite()
                ? R.color.warning : R.color.text_hint));
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

    /** 分类专属字段：解密、按分类顺序展示；敏感字段掩码显示，点击查看明文、按钮复制 */
    private void renderExtraFields() {
        extraBox.removeAllViews();
        extraValues = null;
        String enc = entry.getExtraEnc();
        if (enc == null || enc.isEmpty()) {
            extraBox.setVisibility(View.GONE);
            return;
        }
        Map<String, String> extra;
        try {
            String json = AesUtil.decrypt(enc, VaultSession.get().key());
            extra = json == null ? new LinkedHashMap<>() : Entry.decodeExtra(json);
        } catch (Exception e) {
            extraBox.setVisibility(View.GONE);
            return;
        }
        if (extra.isEmpty()) {
            extraBox.setVisibility(View.GONE);
            return;
        }
        extraValues = extra;
        extraBox.setVisibility(View.VISIBLE);
        TextView title = new TextView(this);
        title.setText(getString(R.string.label_extra_fields));
        title.setTextColor(getColor(R.color.text_secondary));
        title.setTextSize(12);
        extraBox.addView(title);

        String[] keys = extraKeysOf(entry.getCategory());
        for (String key : keys) {
            final String v = extra.get(key);
            if (v == null || v.isEmpty()) {
                continue;
            }
            final boolean sensitive = isSensitiveExtra(key);
            final String label = getString(extraLabelResId(key));

            // 行容器：label(左) + 复制(右)
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, 0);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            extraBox.addView(row, rowLp);

            TextView tvLabel = new TextView(this);
            tvLabel.setText(label);
            tvLabel.setTextColor(getColor(R.color.text_secondary));
            tvLabel.setTextSize(13);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            row.addView(tvLabel, labelLp);

            TextView tvVal = new TextView(this);
            tvVal.setText(sensitive ? maskExtra(v) : v);
            tvVal.setTextColor(getColor(R.color.text_main));
            tvVal.setTextSize(15);
            tvVal.setSingleLine(true);
            tvVal.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvVal.setOnClickListener(view -> {
                // 点击敏感字段在掩码/明文间切换，非敏感字段点击复制
                if (sensitive) {
                    boolean showingPlain = tvVal.getTag() != null && Boolean.TRUE.equals(tvVal.getTag());
                    if (showingPlain) {
                        tvVal.setTag(Boolean.FALSE);
                        tvVal.setText(maskExtra(v));
                    } else {
                        tvVal.setTag(Boolean.TRUE);
                        tvVal.setText(v);
                    }
                } else {
                    copyExtra(v);
                }
            });
            row.addView(tvVal, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView tvCopy = new TextView(this);
            tvCopy.setText(getString(R.string.copy));
            tvCopy.setBackgroundResource(R.drawable.bg_chip);
            tvCopy.setPadding(dp(14), dp(5), dp(14), dp(5));
            tvCopy.setTextColor(getColor(R.color.primary));
            tvCopy.setTextSize(13);
            tvCopy.setOnClickListener(view -> copyExtra(v));
            row.addView(tvCopy, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void copyExtra(String v) {
        if (v == null || v.isEmpty()) {
            return;
        }
        ClipboardUtil.copyWithAutoClear(this, v);
        Toast.makeText(this, R.string.copied_auto_clear, Toast.LENGTH_SHORT).show();
        bumpUse();
    }

    /** 分类专属字段键序（与编辑页一致） */
    private String[] extraKeysOf(String category) {
        if (Entry.CATEGORY_WIFI.equals(category)) {
            return new String[]{Entry.ExtraKeys.WIFI_SSID, Entry.ExtraKeys.WIFI_ENCRYPTION};
        }
        if (Entry.CATEGORY_BANK_CARD.equals(category)) {
            return new String[]{
                    Entry.ExtraKeys.BANK_CARD_NO, Entry.ExtraKeys.BANK_HOLDER,
                    Entry.ExtraKeys.BANK_EXPIRY, Entry.ExtraKeys.BANK_CVV,
                    Entry.ExtraKeys.BANK_NAME
            };
        }
        if (Entry.CATEGORY_ID_CARD.equals(category)) {
            return new String[]{
                    Entry.ExtraKeys.ID_TYPE, Entry.ExtraKeys.ID_NUMBER,
                    Entry.ExtraKeys.ID_NAME, Entry.ExtraKeys.ID_AUTHORITY,
                    Entry.ExtraKeys.ID_EXPIRY
            };
        }
        if (Entry.CATEGORY_API_KEY.equals(category)) {
            return new String[]{Entry.ExtraKeys.API_KEY, Entry.ExtraKeys.API_ENDPOINT, Entry.ExtraKeys.API_MODEL};
        }
        return new String[0];
    }

    /** 敏感专属字段：脱敏展示（银行卡号/CVV/证件号/API Key） */
    private boolean isSensitiveExtra(String key) {
        switch (key) {
            case com.mimavault.model.Entry.ExtraKeys.BANK_CARD_NO:
            case com.mimavault.model.Entry.ExtraKeys.BANK_CVV:
            case com.mimavault.model.Entry.ExtraKeys.ID_NUMBER:
            case com.mimavault.model.Entry.ExtraKeys.API_KEY:
                return true;
            default:
                return false;
        }
    }

    private String maskExtra(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        if (v.length() <= 4) {
            return "****";
        }
        return v.substring(0, 2) + "****" + v.substring(v.length() - 2);
    }

    private int extraLabelResId(String key) {
        switch (key) {
            case com.mimavault.model.Entry.ExtraKeys.WIFI_SSID:
                return R.string.extra_wifi_ssid;
            case com.mimavault.model.Entry.ExtraKeys.WIFI_ENCRYPTION:
                return R.string.extra_wifi_encryption;
            case com.mimavault.model.Entry.ExtraKeys.BANK_CARD_NO:
                return R.string.extra_bank_card_no;
            case com.mimavault.model.Entry.ExtraKeys.BANK_HOLDER:
                return R.string.extra_bank_holder;
            case com.mimavault.model.Entry.ExtraKeys.BANK_EXPIRY:
                return R.string.extra_bank_expiry;
            case com.mimavault.model.Entry.ExtraKeys.BANK_CVV:
                return R.string.extra_bank_cvv;
            case com.mimavault.model.Entry.ExtraKeys.BANK_NAME:
                return R.string.extra_bank_name;
            case com.mimavault.model.Entry.ExtraKeys.ID_TYPE:
                return R.string.extra_id_type;
            case com.mimavault.model.Entry.ExtraKeys.ID_NUMBER:
                return R.string.extra_id_number;
            case com.mimavault.model.Entry.ExtraKeys.ID_NAME:
                return R.string.extra_id_name;
            case com.mimavault.model.Entry.ExtraKeys.ID_AUTHORITY:
                return R.string.extra_id_authority;
            case com.mimavault.model.Entry.ExtraKeys.ID_EXPIRY:
                return R.string.extra_id_expiry;
            case com.mimavault.model.Entry.ExtraKeys.API_KEY:
                return R.string.extra_api_key;
            case com.mimavault.model.Entry.ExtraKeys.API_ENDPOINT:
                return R.string.extra_api_endpoint;
            case com.mimavault.model.Entry.ExtraKeys.API_MODEL:
                return R.string.extra_api_model;
            default:
                return 0;
        }
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
        bumpUse();
    }

    private void render() {
        ((android.widget.Button) findViewById(R.id.btnHistory)).setText(
                getString(R.string.history_entry, service.countPasswordHistory(entry.getId())));
        renderTotp();
        renderExtraFields();
        renderFavorite();
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

        // 图片附件：仅在图片真实存在且可解码时才展示，避免出现空白卡片
        imageBox.removeAllViews();
        imageBox.setVisibility(View.GONE);
        if (entry.getImagePath() != null && !entry.getImagePath().isEmpty()) {
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
                    imageBox.setVisibility(View.VISIBLE);
                }
            }
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
        bumpUse();
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
