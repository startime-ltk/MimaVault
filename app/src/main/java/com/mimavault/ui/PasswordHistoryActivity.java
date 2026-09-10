package com.mimavault.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.model.Entry;
import com.mimavault.model.PasswordHistoryItem;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;
import com.mimavault.util.AesUtil;
import com.mimavault.util.InsetsUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 密码历史版本：展示条目改密前的历史密码（密文存储，仅本地解密查看），支持一键恢复。
 */
public class PasswordHistoryActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "entry_id";
    /** 默认掩码显示，点击「显示」才解密明文 */
    private static final String MASK = "••••••••";

    public static void start(Activity from, long entryId) {
        Intent i = new Intent(from, PasswordHistoryActivity.class);
        i.putExtra(EXTRA_ID, entryId);
        from.startActivity(i);
    }

    private PasswordService service;
    private long entryId = -1;
    private Entry entry;

    private LinearLayout listBox;
    private TextView tvEmpty;

    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_password_history);
        InsetsUtil.applyTopInset(findViewById(R.id.titleBarHistory));

        service = new PasswordService(MimaVaultApp.db());
        entryId = getIntent().getLongExtra(EXTRA_ID, -1);
        entry = service.getById(entryId);

        listBox = findViewById(R.id.historyList);
        tvEmpty = findViewById(R.id.tvHistoryEmpty);
        findViewById(R.id.btnHistoryBack).setOnClickListener(v -> finish());
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (service != null && entryId > 0) {
            entry = service.getById(entryId);
            render();
        }
    }

    private void render() {
        listBox.removeAllViews();
        if (entry == null) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        List<PasswordHistoryItem> list = service.listPasswordHistory(entryId);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        for (PasswordHistoryItem item : list) {
            listBox.addView(buildCard(item));
        }
    }

    /** 单条历史卡片：时间 + 掩码密码（可切换查看）+ 恢复按钮 */
    private View buildCard(PasswordHistoryItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(10);
        card.setLayoutParams(cardLp);

        TextView tvTime = new TextView(this);
        String when = item.getChangedAt() == null ? "" : fmt.format(item.getChangedAt());
        tvTime.setText(getString(R.string.history_item_time, when));
        tvTime.setTextColor(getColor(R.color.text_secondary));
        tvTime.setTextSize(12f);
        card.addView(tvTime);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);
        row.setLayoutParams(rowLp);

        TextView tvPwd = new TextView(this);
        tvPwd.setText(MASK);
        tvPwd.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvPwd.setTextColor(getColor(R.color.text_main));
        tvPwd.setTextSize(15f);
        tvPwd.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvPwd);

        TextView btnToggle = new TextView(this);
        btnToggle.setText(R.string.toggle_show);
        btnToggle.setBackgroundResource(R.drawable.bg_chip);
        btnToggle.setPadding(dp(14), dp(6), dp(14), dp(6));
        btnToggle.setTextColor(getColor(R.color.primary));
        btnToggle.setTextSize(13f);
        row.addView(btnToggle);
        card.addView(row);

        final boolean[] revealed = {false};
        final String[] plainCache = {null};
        btnToggle.setOnClickListener(v -> {
            if (revealed[0]) {
                revealed[0] = false;
                tvPwd.setText(MASK);
                btnToggle.setText(R.string.toggle_show);
                return;
            }
            if (plainCache[0] == null) {
                plainCache[0] = safeDecrypt(item.getPasswordEnc());
            }
            if (plainCache[0] == null) {
                Toast.makeText(this, R.string.history_decrypt_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            revealed[0] = true;
            tvPwd.setText(plainCache[0].isEmpty() ? getString(R.string.history_empty_value) : plainCache[0]);
            btnToggle.setText(R.string.toggle_hide);
        });

        Button btnRestore = new Button(this);
        btnRestore.setText(R.string.history_restore);
        btnRestore.setTextColor(getColor(R.color.white));
        btnRestore.setTextSize(13f);
        btnRestore.setBackgroundResource(R.drawable.bg_button_primary);
        btnRestore.setBackgroundTintList(null);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        btnLp.topMargin = dp(10);
        btnRestore.setLayoutParams(btnLp);
        btnRestore.setOnClickListener(v -> confirmRestore(item));
        card.addView(btnRestore);

        return card;
    }

    private void confirmRestore(PasswordHistoryItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.history_restore)
                .setMessage(R.string.history_restore_confirm)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    service.restorePasswordFromHistory(entry, item, VaultSession.get().key());
                    Toast.makeText(this, R.string.history_restored, Toast.LENGTH_SHORT).show();
                    entry = service.getById(entryId);
                    render();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 解密历史密码；失败返回 null（不抛出，避免崩溃） */
    private String safeDecrypt(String enc) {
        if (enc == null || enc.isEmpty()) {
            return "";
        }
        try {
            String plain = AesUtil.decrypt(enc, VaultSession.get().key());
            return plain == null ? "" : plain;
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
