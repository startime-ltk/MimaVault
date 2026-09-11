package com.mimavault.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.service.BiometricHelper;
import com.mimavault.service.GestureUnlockHelper;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;
import com.mimavault.util.InsetsUtil;

import java.util.Arrays;

/**
 * 修改主密码：验证当前主密码 → 换盐重新派生密钥 → 全量重加密库内容 → 刷新会话。
 *
 * 安全要点：
 *  - 必须已解锁（会话打开）才能进入，避免在未验证状态下改密；
 *  - 重加密过程在后台线程执行，失败即中止且不落盘（由 PasswordService 保证）；
 *  - 改密成功后手势 / 指纹绑定所对应的旧密钥与旧派生参数全部失效，直接清除绑定，避免解锁失败。
 */
public class ChangeMasterPasswordActivity extends AppCompatActivity {

    public static void start(Activity from) {
        from.startActivity(new Intent(from, ChangeMasterPasswordActivity.class));
    }

    private PasswordService service;
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText etCurrent;
    private EditText etNew;
    private EditText etConfirm;
    private Button btnOk;
    private Button btnCancel;
    private ProgressBar pb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_master_password);
        InsetsUtil.applyTopInset(findViewById(R.id.rootChangeMasterScroll));
        service = new PasswordService(MimaVaultApp.db());

        etCurrent = findViewById(R.id.etCurrentMaster);
        etNew = findViewById(R.id.etNewMaster);
        etConfirm = findViewById(R.id.etConfirmMaster);
        btnOk = findViewById(R.id.btnChangeMasterOk);
        btnCancel = findViewById(R.id.btnChangeMasterCancel);
        pb = findViewById(R.id.pbChangeMaster);

        btnOk.setOnClickListener(v -> submit());
        btnCancel.setOnClickListener(v -> finish());
    }

    private void submit() {
        if (!VaultSession.get().isOpen()) {
            Toast.makeText(this, R.string.change_master_need_unlock, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        char[] current = etCurrent.getText().toString().toCharArray();
        char[] next = etNew.getText().toString().toCharArray();
        String confirm = etConfirm.getText().toString();

        if (current.length == 0) {
            Toast.makeText(this, R.string.change_master_current, Toast.LENGTH_SHORT).show();
            return;
        }
        if (next.length < 6) {
            Toast.makeText(this, R.string.change_master_new, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!new String(next).equals(confirm)) {
            Toast.makeText(this, R.string.change_master_mismatch, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Arrays.equals(current, next)) {
            Toast.makeText(this, R.string.change_master_same, Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                final PasswordService.MasterChangeResult result =
                        service.changeMasterPassword(current, next);
                // 重加密成功：立即把会话切到新密钥，保证后续读写用的是新主密码
                final char[] sessionPwd = next.clone();
                VaultSession.get().open(sessionPwd, result.newKey, result.newSaltHex, result.iterations);

                final boolean gestureSet = GestureUnlockHelper.isGestureSet(this);
                final boolean bioSet = BiometricHelper.hasStoredKey(this);
                if (gestureSet) {
                    GestureUnlockHelper.clear(this);
                }
                if (bioSet) {
                    BiometricHelper.clearStoredKey(this);
                }
                main.post(() -> onChanged(gestureSet, bioSet));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.change_master_failed, msg), Toast.LENGTH_LONG).show();
                });
            } finally {
                Arrays.fill(current, '\0');
                Arrays.fill(next, '\0');
            }
        }).start();
    }

    private void onChanged(boolean gestureSet, boolean bioSet) {
        setBusy(false);
        StringBuilder sb = new StringBuilder(getString(R.string.change_master_success));
        if (gestureSet || bioSet) {
            sb.append("\n\n").append(getString(R.string.change_master_unlock_reset));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.change_master_title)
                .setMessage(sb.toString())
                .setCancelable(false)
                .setPositiveButton(R.string.confirm, (d, w) -> finish())
                .show();
    }

    private void setBusy(boolean busy) {
        pb.setVisibility(busy ? ProgressBar.VISIBLE : ProgressBar.GONE);
        btnOk.setEnabled(!busy);
        btnCancel.setEnabled(!busy);
        etCurrent.setEnabled(!busy);
        etNew.setEnabled(!busy);
        etConfirm.setEnabled(!busy);
    }
}
