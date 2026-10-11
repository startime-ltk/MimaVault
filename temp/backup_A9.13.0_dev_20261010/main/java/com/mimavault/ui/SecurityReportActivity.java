package com.mimavault.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.util.InsetsUtil;
import com.mimavault.model.Entry;
import com.mimavault.service.PasswordHealthChecker;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;

import java.util.List;

/**
 * 安全报告：弱口令 / 重复口令 / 已泄露密码 离线检测结果
 */
public class SecurityReportActivity extends AppCompatActivity {

    private PasswordService service;

    public static void start(Context ctx) {
        ctx.startActivity(new Intent(ctx, SecurityReportActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_report);
        InsetsUtil.applyTopInset(findViewById(R.id.titleBarReport));
        service = new PasswordService(MimaVaultApp.db());

        TextView tvScore = findViewById(R.id.tvScore);
        TextView tvLevel = findViewById(R.id.tvLevel);
        TextView tvSummary = findViewById(R.id.tvSummary);
        LinearLayout issueList = findViewById(R.id.issueList);
        findViewById(R.id.btnReportBack).setOnClickListener(v -> finish());

        List<Entry> entries = service.listEntries();
        PasswordHealthChecker.ReportResult r = PasswordHealthChecker.check(
                entries, e -> service.decryptPassword(e, VaultSession.get().key()));

        tvScore.setText(String.valueOf(r.score));
        tvLevel.setText(r.level.getText());
        int color;
        switch (r.level) {
            case OK:
                color = getColor(R.color.success);
                break;
            case WARNING:
                color = getColor(R.color.warning);
                break;
            default:
                color = getColor(R.color.danger);
                break;
        }
        tvScore.setTextColor(color);
        tvLevel.setTextColor(color);

        tvSummary.setText(getString(R.string.security_summary,
                r.totalCount, r.weakCount, r.duplicateCount, r.breachedCount));

        issueList.removeAllViews();
        if (r.issues == null || r.issues.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.security_no_issue);
            empty.setTextColor(getColor(R.color.success));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(40), dp(16), dp(40));
            issueList.addView(empty);
        } else {
            for (PasswordHealthChecker.HealthIssue issue : r.issues) {
                issueList.addView(buildIssueCard(issue));
            }
        }
    }

    private View buildIssueCard(PasswordHealthChecker.HealthIssue issue) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        TextView type = new TextView(this);
        type.setText(issue.typeText);
        type.setTextSize(14);
        type.setTypeface(type.getTypeface(), android.graphics.Typeface.BOLD);
        type.setTextColor(severityColor(issue.severity));
        card.addView(type);

        TextView title = new TextView(this);
        title.setText((issue.platform == null ? "" : issue.platform)
                + (issue.account == null || issue.account.isEmpty() ? "" : " / " + issue.account));
        title.setTextSize(15);
        title.setTextColor(getColor(R.color.text_main));
        title.setPadding(0, dp(4), 0, 0);
        card.addView(title);

        TextView msg = new TextView(this);
        msg.setText(issue.message + "（" + issue.passwordPreview + "）");
        msg.setTextSize(13);
        msg.setTextColor(getColor(R.color.text_secondary));
        msg.setPadding(0, dp(4), 0, 0);
        card.addView(msg);

        return card;
    }

    private int severityColor(PasswordHealthChecker.Severity s) {
        switch (s) {
            case CRITICAL:
                return getColor(R.color.danger);
            case WARNING:
                return getColor(R.color.warning);
            default:
                return getColor(R.color.success);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
