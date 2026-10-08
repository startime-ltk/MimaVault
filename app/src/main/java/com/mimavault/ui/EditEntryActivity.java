package com.mimavault.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.util.AesUtil;
import com.mimavault.util.InsetsUtil;
import com.mimavault.model.Entry;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;
import com.mimavault.util.GestureParser;
import com.mimavault.util.ImageUtil;
import com.mimavault.util.PasswordStrengthUtil;
import com.mimavault.util.TotpUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新增 / 编辑条目
 * 支持：密码生成、强度提示、九宫格手势、图片附件、动态验证码（TOTP）
 */
public class EditEntryActivity extends AppCompatActivity {

    private static final int REQ_GESTURE = 1001;

    public static void start(Activity from, long entryId) {
        Intent i = new Intent(from, EditEntryActivity.class);
        i.putExtra("id", entryId);
        from.startActivityForResult(i, 0);
    }

    private long editId = -1;
    private PasswordService service;

    private EditText etPlatform;
    private Spinner spCategory;
    private EditText etAccount;
    private EditText etPassword;
    private EditText etPhone;
    private EditText etEmail;
    private EditText etNote;
    private TextView tvStrength;
    private Button btnGenPwd;
    private Button btnGesturePick;
    private Button btnGestureClear;
    private TextView tvGesture;
    private Button btnImagePick;
    private Button btnImageClear;
    private TextView tvImageName;
    private ImageView ivImagePreview;

    // 分类专属字段（WiFi/银行卡/证件/API Key）：按分类动态构建输入区
    private LinearLayout extraFieldsBox;
    private TextView tvExtraTitle;
    private final List<EditText> extraInputs = new ArrayList<>();
    private String[] extraKeys = new String[0];

    // 动态验证码（TOTP，纯离线）
    private TextView tvTotpStatus;
    private Button btnTotpClear;
    /** 内存中的 otpauth 规范链接；保存时用会话密钥加密后落库，明文不落盘 */
    private String totpUri = "";

    /** TOTP 扫码：直接复用 ZXing 扫码组件，扫码结果为 otpauth:// 链接 */
    private final ActivityResultLauncher<ScanOptions> totpScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() == null) {
                    return;
                }
                applyTotpInput(result.getContents().trim());
            });

    private String gestureSeq = "";
    private String imagePath = "";   // 相对路径 images/xxx.jpg
    private Uri pendingImageUri;     // 待保存的图片源

    private final ActivityResultLauncher<String> imageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingImageUri = uri;
                    tvImageName.setText("已选择图片（保存时写入附件）");
                    ivImagePreview.setImageURI(uri);
                    ivImagePreview.setVisibility(ImageView.VISIBLE);
                    btnImageClear.setEnabled(true);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_entry);
        InsetsUtil.applyTopInset(findViewById(R.id.rootEditScroll));
        service = new PasswordService(MimaVaultApp.db());
        editId = getIntent().getLongExtra("id", -1);

        etPlatform = findViewById(R.id.etPlatform);
        spCategory = findViewById(R.id.spCategory);
        etAccount = findViewById(R.id.etAccount);
        etPassword = findViewById(R.id.etPassword);
        etPhone = findViewById(R.id.etPhone);
        etEmail = findViewById(R.id.etEmail);
        etNote = findViewById(R.id.etNote);
        tvStrength = findViewById(R.id.tvStrength);
        btnGenPwd = findViewById(R.id.btnGenPwd);
        btnGesturePick = findViewById(R.id.btnGesturePick);
        btnGestureClear = findViewById(R.id.btnGestureClear);
        tvGesture = findViewById(R.id.tvGesture);
        btnImagePick = findViewById(R.id.btnImagePick);
        btnImageClear = findViewById(R.id.btnImageClear);
        tvImageName = findViewById(R.id.tvImageName);
        ivImagePreview = findViewById(R.id.ivImagePreview);
        tvTotpStatus = findViewById(R.id.tvTotpStatus);
        btnTotpClear = findViewById(R.id.btnTotpClear);
        extraFieldsBox = findViewById(R.id.extraFieldsBox);
        tvExtraTitle = findViewById(R.id.tvExtraTitle);

        spCategory.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Entry.CATEGORIES));
        spCategory.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                rebuildExtraFields((String) parent.getSelectedItem());
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        if (editId > 0) {
            Entry e = service.getById(editId);
            if (e == null) {
                Toast.makeText(this, "条目不存在", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            etPlatform.setText(e.getPlatform());
            selectCategory(e.getCategory());
            etAccount.setText(e.getAccount());
            etPassword.setText("");
            etPassword.setHint("留空表示不修改密码");
            etPhone.setText(e.getPhone());
            etEmail.setText(e.getEmail());
            etNote.setText(e.getNote());
            // 分类专属字段：解密已有 JSON 并回填（解密失败视为空）
            fillExtraFields(e.getExtraEnc());
            gestureSeq = e.getGestureSeq() == null ? "" : e.getGestureSeq();
            tvGesture.setText(GestureParser.describe(gestureSeq));
            // 动态验证码：解密 otpauth 链接（解密失败视为未绑定，不阻塞编辑）
            try {
                String totpEnc = e.getTotpSecretEnc();
                totpUri = (totpEnc == null || totpEnc.isEmpty())
                        ? "" : AesUtil.decrypt(totpEnc, VaultSession.get().key());
            } catch (Exception ex) {
                totpUri = "";
            }
            imagePath = e.getImagePath() == null ? "" : e.getImagePath();
            if (!imagePath.isEmpty()) {
                tvImageName.setText("已有附件：" + imagePath.substring(imagePath.lastIndexOf('/') + 1));
                ivImagePreview.setImageURI(null);
                ivImagePreview.setImageDrawable(null);
                ivImagePreview.setBackgroundResource(R.drawable.bg_input);
                ivImagePreview.setImageBitmap(ImageUtil.loadBitmap(this, imagePath));
                ivImagePreview.setVisibility(ImageView.VISIBLE);
                btnImageClear.setEnabled(true);
            }
        } else {
            selectCategory(Entry.CATEGORY_WEBSITE);
            tvGesture.setText("未设置");
            btnImageClear.setEnabled(false);
        }

        btnGenPwd.setOnClickListener(v -> PasswordGeneratorDialog.show(this, pwd -> {
            etPassword.setText(pwd);
            refreshStrength();
        }));
        etPassword.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                refreshStrength();
            }
        });

        btnGesturePick.setOnClickListener(v ->
                startActivityForResult(new Intent(this, GestureActivity.class), REQ_GESTURE));
        btnGestureClear.setOnClickListener(v -> {
            gestureSeq = "";
            tvGesture.setText("未设置");
        });
        btnImagePick.setOnClickListener(v ->
                imageLauncher.launch("image/*"));
        btnImageClear.setOnClickListener(v -> {
            pendingImageUri = null;
            imagePath = "";
            tvImageName.setText("未选择图片");
            ivImagePreview.setImageDrawable(null);
            ivImagePreview.setVisibility(ImageView.GONE);
            btnImageClear.setEnabled(false);
        });

        // 动态验证码：扫码绑定 / 手动录入 / 清除
        findViewById(R.id.btnTotpScan).setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt(getString(R.string.totp_scan_hint));
            options.setBeepEnabled(false);
            totpScanLauncher.launch(options);
        });
        findViewById(R.id.btnTotpManual).setOnClickListener(v -> showTotpManualDialog());
        btnTotpClear.setOnClickListener(v -> {
            totpUri = "";
            updateTotpStatus();
            Toast.makeText(this, R.string.totp_clear, Toast.LENGTH_SHORT).show();
        });
        updateTotpStatus();

        findViewById(R.id.btnSave).setOnClickListener(v -> onSave());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_GESTURE && resultCode == RESULT_OK && data != null) {
            gestureSeq = data.getStringExtra(GestureActivity.EXTRA_SEQ);
            tvGesture.setText(GestureParser.describe(gestureSeq));
        }
    }

    private void selectCategory(String c) {
        if (c == null || c.isEmpty()) {
            c = Entry.CATEGORY_WEBSITE;
        }
        for (int i = 0; i < Entry.CATEGORIES.length; i++) {
            if (Entry.CATEGORIES[i].equals(c)) {
                spCategory.setSelection(i);
                return;
            }
        }
        spCategory.setSelection(0);
    }

    /** 分类专属字段键（双端一致：ExtraKeys），无专属字段的分类返回空数组 */
    private String[] getExtraKeys(String category) {
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

    /** 专属字段输入框 hint 资源（与 ExtraKeys 一一对应） */
    private int extraHintResId(String key) {
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

    /** 按分类重建专属字段输入区；无专属字段则隐藏 */
    private void rebuildExtraFields(String category) {
        extraInputs.clear();
        extraFieldsBox.removeAllViews();
        extraKeys = getExtraKeys(category);
        if (extraKeys.length == 0) {
            extraFieldsBox.setVisibility(View.GONE);
            return;
        }
        extraFieldsBox.setVisibility(View.VISIBLE);
        extraFieldsBox.addView(tvExtraTitle);
        int dp = dp(1);
        for (String key : extraKeys) {
            EditText et = new EditText(this);
            et.setBackgroundResource(R.drawable.bg_input);
            et.setHint(getString(extraHintResId(key)));
            et.setTextColor(getColor(R.color.text_main));
            et.setHintTextColor(getColor(R.color.text_hint));
            et.setPadding(dp * 14, 0, dp * 14, 0);
            et.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp * 52);
            lp.topMargin = dp * 10;
            et.setLayoutParams(lp);
            extraInputs.add(et);
            extraFieldsBox.addView(et);
        }
    }

    /** 解密已有 extraEnc 并回填输入框（解不开/无数据视为空） */
    private void fillExtraFields(String extraEnc) {
        if (extraEnc == null || extraEnc.isEmpty()) {
            return;
        }
        Map<String, String> extra;
        try {
            String json = AesUtil.decrypt(extraEnc, VaultSession.get().key());
            extra = json == null ? new LinkedHashMap<>() : Entry.decodeExtra(json);
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < extraInputs.size() && i < extraKeys.length; i++) {
            String v = extra.get(extraKeys[i]);
            if (v != null) {
                extraInputs.get(i).setText(v);
            }
        }
    }

    /** 收集当前分类专属字段（仅非空值） */
    private Map<String, String> collectExtra() {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < extraInputs.size() && i < extraKeys.length; i++) {
            String v = extraInputs.get(i).getText().toString().trim();
            if (!v.isEmpty()) {
                map.put(extraKeys[i], v);
            }
        }
        return map;
    }

    /** 加密分类专属字段：无字段返回 null，加密失败返回 null（视为未填） */
    private String encryptExtraOrNull() {
        Map<String, String> map = collectExtra();
        if (map.isEmpty()) {
            return null;
        }
        try {
            return AesUtil.encrypt(Entry.encodeExtra(map), VaultSession.get().key());
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshStrength() {
        String pwd = etPassword.getText().toString();
        if (pwd.isEmpty()) {
            tvStrength.setText("输入密码可查看强度");
            tvStrength.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        PasswordStrengthUtil.Result r = PasswordStrengthUtil.evaluate(pwd);
        tvStrength.setText(r.toString());
        tvStrength.setTextColor(getColor(r.level == PasswordStrengthUtil.Level.WEAK
                ? R.color.warning : R.color.success));
    }

    /** 解析扫码 / 手工输入的 otpauth 链接或 Base32 密钥，成功则暂存规范化链接 */
    private void applyTotpInput(String input) {
        if (input == null || input.trim().isEmpty()) {
            Toast.makeText(this, R.string.totp_empty_input, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            TotpUtil.Config c = TotpUtil.parse(input);
            totpUri = c.toUri();
            updateTotpStatus();
            Toast.makeText(this, getString(R.string.totp_set, c.label()), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.totp_invalid, Toast.LENGTH_LONG).show();
        }
    }

    private void showTotpManualDialog() {
        final EditText et = new EditText(this);
        et.setHint(R.string.totp_manual_hint);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(2);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.totp_manual_title)
                .setView(et)
                .setPositiveButton(R.string.confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = et.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.totp_empty_input, Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                TotpUtil.Config c = TotpUtil.parse(text);
                totpUri = c.toUri();
                updateTotpStatus();
                dialog.dismiss();
                Toast.makeText(this, getString(R.string.totp_set, c.label()), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, R.string.totp_invalid, Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    /** 刷新绑定状态文案与「清除」按钮可用性 */
    private void updateTotpStatus() {
        if (totpUri == null || totpUri.isEmpty()) {
            tvTotpStatus.setText(R.string.totp_not_set);
            btnTotpClear.setEnabled(false);
            return;
        }
        String label = "动态验证码";
        try {
            label = TotpUtil.parse(totpUri).label();
        } catch (Exception ignored) {
        }
        tvTotpStatus.setText(getString(R.string.totp_set, label));
        btnTotpClear.setEnabled(true);
    }

    /** 未绑定或加密失败返回 null（落库即空，视为未绑定） */
    private String encryptTotpOrNull() {
        if (totpUri == null || totpUri.isEmpty()) {
            return null;
        }
        try {
            return AesUtil.encrypt(totpUri, VaultSession.get().key());
        } catch (Exception e) {
            return null;
        }
    }

    private void onSave() {
        String platform = etPlatform.getText().toString().trim();
        if (platform.isEmpty()) {
            Toast.makeText(this, "平台名称不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String category = (String) spCategory.getSelectedItem();
        String account = etAccount.getText().toString().trim();
        String plain = etPassword.getText().toString();
        String phone = etPhone.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String note = etNote.getText().toString().trim();

        long savedId = editId;
        if (editId > 0) {
            Entry e = service.getById(editId);
            if (e == null) {
                finish();
                return;
            }
            e.setPlatform(platform);
            e.setCategory(category);
            e.setAccount(account);
            e.setPhone(phone);
            e.setEmail(email);
            e.setNote(note);
            e.setGestureSeq(gestureSeq);
            // 分类专属字段：整体加密落库；清空输入后写 null 即为清除
            e.setExtraEnc(encryptExtraOrNull());
            // 动态验证码密钥随会话密钥加密落库；清除后写空即为解绑
            e.setTotpSecretEnc(encryptTotpOrNull());
            // 无条件写入：支持清除图片后保存（置空路径）
            e.setImagePath(imagePath);
            if (plain.isEmpty()) {
                // 未输入新密码，保留原密码
                service.updateEntry(e, null, VaultSession.get().key(), true);
            } else {
                // 写入新密码：旧密码自动留档到历史版本
                service.updateEntry(e, plain, VaultSession.get().key(), false);
            }
        } else {
            if (plain.isEmpty()) {
                Toast.makeText(this, "请设置密码（可为空但建议填写）", Toast.LENGTH_SHORT).show();
                return;
            }
            Entry e = new Entry();
            e.setPlatform(platform);
            e.setCategory(category);
            e.setAccount(account);
            e.setPhone(phone);
            e.setEmail(email);
            e.setNote(note);
            e.setGestureSeq(gestureSeq);
            e.setExtraEnc(encryptExtraOrNull());
            e.setTotpSecretEnc(encryptTotpOrNull());
            e.setImagePath(imagePath);
            savedId = service.addEntry(e, plain, VaultSession.get().key());
        }

        // 图片附件：编辑时若有新选图片，保存到 images 目录并更新路径
        if (pendingImageUri != null) {
            String saved = ImageUtil.saveCompressed(this, pendingImageUri);
            if (saved != null) {
                Entry e = service.getById(savedId);
                if (e != null) {
                    e.setImagePath(saved);
                    service.updateEntry(e, null, VaultSession.get().key(), true);
                }
            }
        }

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}
