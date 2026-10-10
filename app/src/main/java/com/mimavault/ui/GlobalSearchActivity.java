package com.mimavault.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.model.Entry;
import com.mimavault.service.GlobalSearchIndex;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;
import com.mimavault.util.InsetsUtil;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

/**
 * 全局搜索面板（A9.12.0，对齐 PC 端 v8.34.0 Ctrl+K 面板）
 *
 *  - 普通关键词：跨条目（含四分类专属字段、备注、回收站外全部条目）模糊子序列匹配，
 *    标题优先排序；点击 / 回车打开对应条目详情。
 *  - > 前缀命令模式：执行常用命令（锁定、回收站、修改主密码、安全报告、新建条目）。
 *  - 保留主界面顶部搜索框与分类筛选入口，本面板为独立全局入口。
 */
public class GlobalSearchActivity extends AppCompatActivity {

    /** 命令定义 */
    public static final class CommandDef {
        public final String id;
        public final String label;
        public final String hint;

        public CommandDef(String id, String label, String hint) {
            this.id = id;
            this.label = label;
            this.hint = hint;
        }
    }

    /** 命令集（对齐 PC 端；migrate 为 PC 专属能力，安卓端无此功能故不收录） */
    private static final CommandDef[] COMMANDS = {
            new CommandDef("lock", "锁定", "立即锁定并回到解锁界面"),
            new CommandDef("trash", "回收站", "打开回收站（恢复/彻底删除）"),
            new CommandDef("settings", "修改主密码", "验证当前密码后全库重加密"),
            new CommandDef("health", "安全报告", "密码健康检测报告"),
            new CommandDef("new", "新建条目", "新增一条记录")
    };

    public static void start(android.app.Activity from) {
        Intent i = new Intent(from, GlobalSearchActivity.class);
        from.startActivity(i);
    }

    private PasswordService service;
    private SecretKey key;
    private final GlobalSearchIndex index = new GlobalSearchIndex();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText etInput;
    private TextView tvHint;
    private TextView tvEmpty;
    private RecyclerView recycler;
    private ResultAdapter adapter;

    private boolean indexReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_global_search);
        InsetsUtil.applyTopInset(findViewById(R.id.header));

        service = new PasswordService(MimaVaultApp.db());
        key = VaultSession.get().key();
        if (key == null) {
            Toast.makeText(this, R.string.global_search_need_unlock, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        etInput = findViewById(R.id.etGlobalSearch);
        tvHint = findViewById(R.id.tvGlobalHint);
        tvEmpty = findViewById(R.id.tvGlobalEmpty);
        recycler = findViewById(R.id.recyclerGlobal);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultAdapter();
        recycler.setAdapter(adapter);

        findViewById(R.id.btnGlobalClose).setOnClickListener(v -> finish());

        tvHint.setText(R.string.global_search_hint_default);
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(R.string.global_search_building);

        // 后台重建索引，构建期间输入可用但暂不返回结果
        new Thread(() -> {
            try {
                index.rebuild(service, key);
                indexReady = true;
                main.post(() -> {
                    onQueryChanged();
                    etInput.requestFocus();
                });
            } catch (Exception e) {
                main.post(() -> {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText(R.string.global_search_build_failed);
                });
            }
        }).start();

        etInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onQueryChanged();
            }
        });
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                openSelected();
                return true;
            }
            return false;
        });
        etInput.requestFocus();
    }

    private void onQueryChanged() {
        String text = etInput.getText() == null ? "" : etInput.getText().toString().trim();
        adapter.setItems(renderItems(text));
        updateHint(text);
    }

    private List<Object> renderItems(String text) {
        List<Object> items = new ArrayList<>();
        if (text.startsWith(">")) {
            items.addAll(renderCommands(text.substring(1).trim().toLowerCase()));
        } else if (!text.isEmpty()) {
            if (indexReady) {
                items.addAll(index.search(text, 50));
            }
        }
        return items;
    }

    private List<Object> renderCommands(String q) {
        List<Object> rows = new ArrayList<>();
        for (CommandDef cmd : COMMANDS) {
            String key = (cmd.id + " " + cmd.label).toLowerCase();
            if (q.isEmpty() || key.contains(q) || isSubsequence(q, key)) {
                rows.add(cmd);
            }
        }
        return rows;
    }

    private void updateHint(String text) {
        if (!indexReady) {
            tvHint.setText(R.string.global_search_building);
            return;
        }
        if (text.startsWith(">")) {
            int n = adapter.getItemCount();
            tvHint.setText(getString(R.string.global_search_cmd_count, n));
            return;
        }
        if (text.isEmpty()) {
            tvHint.setText(R.string.global_search_hint_default);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.global_search_hint_default);
            return;
        }
        int n = adapter.getItemCount();
        if (n == 0) {
            tvHint.setText(R.string.global_search_no_result);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.global_search_no_result);
        } else {
            tvHint.setText(getString(R.string.global_search_hits, n));
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void openSelected() {
        Object sel = adapter.selected();
        if (sel == null) {
            return;
        }
        if (sel instanceof CommandDef) {
            CommandDef cmd = (CommandDef) sel;
            runCommand(cmd.id);
            return;
        }
        if (sel instanceof GlobalSearchIndex.Hit) {
            GlobalSearchIndex.Hit hit = (GlobalSearchIndex.Hit) sel;
            DetailActivity.start(this, hit.entry.getId());
            finish();
        }
    }

    /** 命令分发（对齐 PC 端 runCommand 语义，映射到安卓对应页面） */
    private void runCommand(String id) {
        if ("lock".equals(id)) {
            VaultSession.get().close();
            Intent i = new Intent(this, UnlockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }
        if ("trash".equals(id)) {
            TrashActivity.start(this);
            finish();
            return;
        }
        if ("settings".equals(id)) {
            ChangeMasterPasswordActivity.start(this);
            finish();
            return;
        }
        if ("health".equals(id)) {
            SecurityReportActivity.start(this);
            finish();
            return;
        }
        if ("new".equals(id)) {
            EditEntryActivity.start(this, -1);
            finish();
        }
    }

    /** 字符串 A 是否按序出现在 B 中（命令匹配辅助） */
    private static boolean isSubsequence(String a, String b) {
        if (a == null || a.isEmpty()) {
            return true;
        }
        if (b == null || b.isEmpty()) {
            return false;
        }
        int i = 0;
        int len = a.length();
        for (int j = 0; j < b.length() && i < len; j++) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
            }
        }
        return i == len;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int color(int res) {
        return getResources().getColor(res);
    }

    /** 结果列表适配器：条目命中行 与 命令行 两种类型 */
    private final class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.VH> {
        private final List<Object> items = new ArrayList<>();
        private int selected = -1;

        void setItems(List<Object> list) {
            items.clear();
            items.addAll(list);
            selected = items.isEmpty() ? -1 : 0;
            notifyDataSetChanged();
        }

        Object selected() {
            if (selected < 0 || selected >= items.size()) {
                return null;
            }
            return items.get(selected);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout root = new LinearLayout(GlobalSearchActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color(R.color.card_bg));
            bg.setCornerRadius(dp(12));
            root.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            root.setLayoutParams(lp);

            LinearLayout top = new LinearLayout(GlobalSearchActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(GlobalSearchActivity.this);
            title.setTextSize(14);
            title.setTextColor(color(R.color.text_main));
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView tag = new TextView(GlobalSearchActivity.this);
            tag.setTextSize(10);
            tag.setTextColor(color(R.color.white));
            tag.setPadding(dp(10), dp(2), dp(10), dp(2));
            GradientDrawable tagBg = new GradientDrawable();
            tagBg.setColor(color(R.color.primary));
            tagBg.setCornerRadius(dp(10));
            tag.setBackground(tagBg);
            top.addView(title);
            top.addView(tag);

            TextView sub = new TextView(GlobalSearchActivity.this);
            sub.setTextSize(11);
            sub.setTextColor(color(R.color.text_secondary));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(3);
            sub.setLayoutParams(subLp);

            root.addView(top);
            root.addView(sub);
            return new VH(root, title, tag, sub);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Object value = items.get(position);
            h.itemView.setAlpha(position == selected ? 1f : 0.92f);
            if (value instanceof CommandDef) {
                CommandDef cmd = (CommandDef) value;
                h.title.setText("> " + cmd.id + "   " + cmd.label);
                h.title.setTextColor(color(R.color.primary_dark));
                h.sub.setText(cmd.hint);
                h.tag.setText(R.string.global_cmd_tag);
                h.tag.getBackground().setTint(color(R.color.accent));
            } else if (value instanceof GlobalSearchIndex.Hit) {
                GlobalSearchIndex.Hit hit = (GlobalSearchIndex.Hit) value;
                h.title.setText(hit.summary());
                h.title.setTextColor(color(R.color.text_main));
                h.sub.setText(buildSubLine(hit));
                h.tag.setText(hit.entry.getCategory() == null ? "" : hit.entry.getCategory());
                h.tag.getBackground().setTint(tagColor(hit.entry.getCategory()));
            }
        }

        private String buildSubLine(GlobalSearchIndex.Hit hit) {
            StringBuilder sb = new StringBuilder();
            sb.append("命中[").append(hit.matchedField).append("]  ");
            sb.append(nullToEmpty(hit.entry.getCategory()));
            if (hit.entry.getNote() != null && !hit.entry.getNote().trim().isEmpty()) {
                sb.append("  ·  ").append(oneLine(hit.entry.getNote()));
            }
            return sb.toString();
        }

        private int tagColor(String category) {
            if (Entry.CATEGORY_WIFI.equals(category)) {
                return 0xFF2E86DE;
            }
            if (Entry.CATEGORY_BANK_CARD.equals(category)) {
                return 0xFFE67E22;
            }
            if (Entry.CATEGORY_ID_CARD.equals(category)) {
                return 0xFF27AE60;
            }
            if (Entry.CATEGORY_API_KEY.equals(category)) {
                return 0xFF8E44AD;
            }
            if (Entry.CATEGORY_APP.equals(category)) {
                return 0xFF16A085;
            }
            return color(R.color.primary);
        }

        private String oneLine(String s) {
            String t = s.replace('\n', ' ').replace('\r', ' ').trim();
            return t.length() > 24 ? t.substring(0, 24) + "…" : t;
        }

        private String nullToEmpty(String s) {
            return s == null ? "" : s;
        }

        private final class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView tag;
            final TextView sub;

            VH(View itemView, TextView title, TextView tag, TextView sub) {
                super(itemView);
                this.title = title;
                this.tag = tag;
                this.sub = sub;
                itemView.setOnClickListener(v -> openSelected());
            }
        }
    }
}
