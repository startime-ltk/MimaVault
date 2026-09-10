package com.mimavault.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.service.BiometricHelper;
import com.mimavault.service.GestureUnlockHelper;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;
import com.mimavault.util.GestureParser;
import com.mimavault.util.InsetsUtil;

import java.util.concurrent.Executor;

import javax.crypto.SecretKey;

/**
 * 解锁 / 首次设置主密码
 * 支持：主密码（PBKDF2 验证，默认主通道）、手势密码（可选，已设置时提供入口）、
 *       指纹/面部（Android Keystore + BiometricPrompt，系统级）
 */
public class UnlockActivity extends AppCompatActivity {

    private PasswordService service;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout setupBox;
    private LinearLayout unlockBox;
    private EditText etSetupPwd;
    private EditText etSetupConfirm;
    private Button btnSetup;
    private EditText etUnlockPwd;
    private Button btnUnlock;
    private Button btnBiometric;
    private Button btnGestureUnlock;
    private Button btnFaceUnlock;
    private LinearLayout gestureUnlockBox;
    private GestureView gestureVerifyView;
    private Button btnGestureBackPwd;
    private LinearLayout biometricToggleRow;
    private android.widget.CheckBox cbEnableBiometric;
    private TextView tvTitle;
    private TextView tvSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unlock);
        service = new PasswordService(MimaVaultApp.db());

        setupBox = findViewById(R.id.setupBox);
        unlockBox = findViewById(R.id.unlockBox);
        etSetupPwd = findViewById(R.id.etSetupPwd);
        etSetupConfirm = findViewById(R.id.etSetupConfirm);
        btnSetup = findViewById(R.id.btnSetup);
        etUnlockPwd = findViewById(R.id.etUnlockPwd);
        btnUnlock = findViewById(R.id.btnUnlock);
        btnBiometric = findViewById(R.id.btnBiometric);
        btnGestureUnlock = findViewById(R.id.btnGestureUnlock);
        gestureUnlockBox = findViewById(R.id.gestureUnlockBox);
        gestureVerifyView = findViewById(R.id.gestureVerifyView);
        btnGestureBackPwd = findViewById(R.id.btnGestureBackPwd);
        biometricToggleRow = findViewById(R.id.biometricToggleRow);
        cbEnableBiometric = findViewById(R.id.cbEnableBiometric);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);

        // 状态栏适配：内容整体下移避开状态栏（渐变背景自动延伸覆盖状态栏区域）
        InsetsUtil.applyTopInset(findViewById(R.id.rootUnlock));

        // 手势解锁回主密码模式
        btnGestureBackPwd.setOnClickListener(v -> showPasswordMode());

        // 手势绘制回调
        gestureVerifyView.setCallback(seq -> {
            if (gestureUnlockBox.getVisibility() != View.VISIBLE) {
                return;
            }
            if (seq.isEmpty()) {
                Toast.makeText(this, R.string.gesture_at_least_4, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!GestureParser.valid(seq)) {
                Toast.makeText(this, R.string.gesture_invalid, Toast.LENGTH_SHORT).show();
                gestureVerifyView.reset();
                return;
            }
            onGestureUnlock(seq);
        });

        if (service.isInitialized()) {
            showUnlockMode();
        } else {
            showSetupMode();
        }
    }

    private void showSetupMode() {
        setupBox.setVisibility(View.VISIBLE);
        unlockBox.setVisibility(View.GONE);
        biometricToggleRow.setVisibility(View.VISIBLE);
        tvTitle.setText(R.string.setup_title);
        tvSubtitle.setText(R.string.setup_subtitle);
        btnSetup.setText(R.string.setup_done);
        btnSetup.setOnClickListener(v -> onSetup());
    }

    private void showUnlockMode() {
        setupBox.setVisibility(View.GONE);
        unlockBox.setVisibility(View.VISIBLE);
        gestureUnlockBox.setVisibility(View.GONE);
        biometricToggleRow.setVisibility(View.GONE);
        tvTitle.setText(R.string.unlock_title);
        tvSubtitle.setText(R.string.unlock_subtitle);
        btnUnlock.setText(R.string.unlock);
        btnUnlock.setOnClickListener(v -> onUnlock());

        boolean canBiometric = BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
        if (canBiometric && BiometricHelper.hasStoredKey(this)) {
            btnBiometric.setVisibility(View.VISIBLE);
            btnBiometric.setOnClickListener(v -> onBiometricUnlock());
        } else {
            btnBiometric.setVisibility(View.GONE);
        }

        // 手势密码为可选登录通道：已设置且未因错误过多被锁定时才提供入口，主密码仍为默认主通道
        if (GestureUnlockHelper.isGestureSet(this) && !GestureUnlockHelper.isLocked(this)) {
            btnGestureUnlock.setVisibility(View.VISIBLE);
            btnGestureUnlock.setOnClickListener(v -> showGestureMode());
        } else {
            btnGestureUnlock.setVisibility(View.GONE);
        }
    }

    private void showGestureMode() {
        unlockBox.setVisibility(View.GONE);
        gestureUnlockBox.setVisibility(View.VISIBLE);
        gestureVerifyView.reset();
    }

    private void showPasswordMode() {
        gestureUnlockBox.setVisibility(View.GONE);
        unlockBox.setVisibility(View.VISIBLE);
        gestureVerifyView.reset();
        // 锁定期间隐藏手势入口，防止绕回手势通道
        btnGestureUnlock.setVisibility((GestureUnlockHelper.isGestureSet(this)
                && !GestureUnlockHelper.isLocked(this)) ? View.VISIBLE : View.GONE);
    }

    private void onGestureUnlock(String seq) {
        gestureVerifyView.setEnabled(false);
        new Thread(() -> {
            GestureUnlockHelper.Result rec = GestureUnlockHelper.tryUnlock(UnlockActivity.this, seq);
            main.post(() -> {
                gestureVerifyView.setEnabled(true);
                if (rec == null) {
                    boolean locked = GestureUnlockHelper.increaseFails(UnlockActivity.this);
                    if (locked) {
                        // 连续错误达上限：回到主密码通道，主密码解锁成功后自动重置
                        showPasswordMode();
                        Toast.makeText(this, R.string.gesture_locked_hint, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, getString(R.string.gesture_wrong_left,
                            GestureUnlockHelper.MAX_ATTEMPTS - GestureUnlockHelper.getFails(this)),
                            Toast.LENGTH_SHORT).show();
                    gestureVerifyView.reset();
                    return;
                }
                GestureUnlockHelper.resetFails(this);
                VaultSession.get().open(new char[0], rec.key, rec.saltHex, rec.iterations);
                enterMain();
            });
        }).start();
    }

    private void onSetup() {
        String p1 = etSetupPwd.getText().toString();
        String p2 = etSetupConfirm.getText().toString();
        if (p1.length() < 6) {
            Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!p1.equals(p2)) {
            Toast.makeText(this, R.string.password_mismatch, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        new Thread(() -> {
            char[] pwd = p1.toCharArray();
            service.setMasterPassword(pwd);
            SecretKey key = service.deriveKey(pwd);
            VaultSession.get().open(pwd, key, service.getMasterSaltHex(), service.getMasterIterations());
            main.post(() -> {
                setBusy(false);
                if (cbEnableBiometric.isChecked() && BiometricHelper.setup(this)) {
                    BiometricHelper.storeVaultKey(this, key.getEncoded(),
                            service.getMasterSaltHex(), service.getMasterIterations());
                }
                enterMain();
            });
        }).start();
    }

    private void onUnlock() {
        String pwdStr = etUnlockPwd.getText().toString();
        android.util.Log.d("MimaVaultUnlock", "unlock pwd len=" + pwdStr.length() + " chars=" + java.util.Arrays.toString(pwdStr.chars().toArray()));
        if (pwdStr.isEmpty()) {
            Toast.makeText(this, R.string.master_password, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        new Thread(() -> {
            char[] pwd = pwdStr.toCharArray();
            PasswordService.VerifyResult result = service.verifyMasterPassword(pwd);
            if (result == PasswordService.VerifyResult.MISMATCH) {
                main.post(() -> {
                    setBusy(false);
                    Toast.makeText(this, R.string.unlock_failed, Toast.LENGTH_SHORT).show();
                    etUnlockPwd.setText("");
                });
                return;
            }
            if (result == PasswordService.VerifyResult.MATCH_NEED_UPGRADE) {
                service.upgradeToPbkdf2(pwd);
            }
            SecretKey key = service.deriveKey(pwd);
            VaultSession.get().open(pwd, key, service.getMasterSaltHex(), service.getMasterIterations());
            GestureUnlockHelper.resetFails(UnlockActivity.this);
            main.post(() -> {
                setBusy(false);
                enterMain();
            });
        }).start();
    }

    private void onBiometricUnlock() {
        if (BiometricHelper.setup(this)) {
            BiometricPrompt prompt = new BiometricPrompt(this, mainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                            BiometricHelper.VaultKeyRecord rec = BiometricHelper.decryptStoredKey(UnlockActivity.this);
                            if (rec == null) {
                                Toast.makeText(UnlockActivity.this, "生物识别密钥失效，请使用主密码解锁", Toast.LENGTH_LONG).show();
                                BiometricHelper.clearStoredKey(UnlockActivity.this);
                                return;
                            }
                            VaultSession.get().open(new char[0], rec.key, rec.saltHex, rec.iterations);
                            GestureUnlockHelper.resetFails(UnlockActivity.this);
                            enterMain();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                            if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                    && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                                Toast.makeText(UnlockActivity.this, errString, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("指纹 / 面部解锁密匣")
                    .setSubtitle("验证通过后解锁本地密码库")
                    .setNegativeButtonText("取消")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build());
        }
    }

    private Executor mainExecutor() {
        return ContextCompat.getMainExecutor(this);
    }

    private void setBusy(boolean busy) {
        btnUnlock.setEnabled(!busy);
        btnSetup.setEnabled(!busy);
        btnUnlock.setText(busy ? "正在验证…" : getString(R.string.unlock));
        btnSetup.setText(busy ? "正在创建…" : getString(R.string.setup_done));
    }

    private void enterMain() {
        MainActivity.start(this);
        finish();
    }
}
