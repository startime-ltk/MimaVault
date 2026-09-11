package com.mimavault.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.model.BackupModel;
import com.mimavault.model.Entry;
import com.mimavault.service.BackupService;
import com.mimavault.service.GestureUnlockHelper;
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;
import com.mimavault.util.CsvUtil;
import com.mimavault.util.GestureParser;
import com.mimavault.util.InsetsUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 主界面：条目列表、模糊搜索、分类筛选、导入/导出、二维码互导入口
 */
public class MainActivity extends AppCompatActivity {

    private PasswordService service;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 手势录入返回（设置 / 修改手势密码） */
    private static final int REQ_GESTURE_LOGIN = 1002;

    private RecyclerView recycler;
    private TextView tvEmpty;
    private EditText etSearch;
    private EntryAdapter adapter;
    private FloatingActionButton fabAdd;
    private LinearLayout categoryBar;
    private LetterIndexBar letterBar;
    /** 手指在索引条上滑动时抑制列表滚动联动，避免高亮闪烁 */
    private boolean suppressIndexSync = false;

    private String currentKeyword = "";
    private String currentCategory = "全部";

    private final ActivityResultLauncher<String> fileExportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                if (uri != null) {
                    doExport(uri);
                }
            });

    private final ActivityResultLauncher<String[]> fileImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    doImport(uri);
                }
            });

    private final ActivityResultLauncher<String> fileCsvExportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
                if (uri != null) {
                    doExportCsv(uri);
                }
            });

    private final ActivityResultLauncher<String[]> fileCsvImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    doImportCsv(uri);
                }
            });

    /** 3 分钟无操作自动锁定检查 */
    private final Handler lockHandler = new Handler(Looper.getMainLooper());
    private final Runnable lockCheck = new Runnable() {
        @Override
        public void run() {
            if (VaultSession.get().isOpen() && MimaVaultApp.idleMillis() > MimaVaultApp.AUTO_LOCK_MILLIS) {
                VaultSession.get().close();
                Intent i = new Intent(MainActivity.this, UnlockActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
                return;
            }
            lockHandler.postDelayed(this, 10_000);
        }
    };

    public static void start(android.app.Activity from) {
        Intent i = new Intent(from, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        service = new PasswordService(MimaVaultApp.db());

        // 状态栏适配：渐变 Header 下移避开状态栏（背景向上延伸覆盖状态栏区域）
        InsetsUtil.applyTopInset(findViewById(R.id.header));

        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);
        fabAdd = findViewById(R.id.fabAdd);
        categoryBar = findViewById(R.id.categoryBar);
        letterBar = findViewById(R.id.letterBar);
        findViewById(R.id.btnMenu).setOnClickListener(v -> {
            android.util.Log.i("MimaVault", "btnMenu clicked");
            showMenu(v);
        });
        findViewById(R.id.ivHeaderLogo).setOnClickListener(v -> {
            android.util.Log.i("MimaVault", "logo clicked");
            showMenu(v);
        });

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new EntryAdapter(new EntryAdapter.Listener() {
            @Override
            public void onClick(Entry entry) {
                DetailActivity.start(MainActivity.this, entry.getId());
            }

            @Override
            public void onLongClick(Entry entry) {
                EditEntryActivity.start(MainActivity.this, entry.getId());
            }
        });
        recycler.setAdapter(adapter);

        // 字母索引条：点击 / 滑动跳转到对应分组
        letterBar.setListener(new LetterIndexBar.Listener() {
            @Override
            public void onLetterSelected(String letter) {
                jumpToLetter(letter);
            }

            @Override
            public void onTouchEnd() {
                suppressIndexSync = false;
                syncActiveLetterFromList();
            }
        });
        // 列表滚动时联动索引条高亮当前组
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull RecyclerView rv, int dx, int dy) {
                if (!suppressIndexSync) {
                    syncActiveLetterFromList();
                }
            }
        });

        fabAdd.setOnClickListener(v -> EditEntryActivity.start(this, -1));
        buildCategoryBar();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                currentKeyword = s.toString().trim();
                reload();
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (etSearch != null && service.isInitialized()) {
            reload();
        }
        lockHandler.post(lockCheck);
    }

    @Override
    protected void onPause() {
        super.onPause();
        lockHandler.removeCallbacks(lockCheck);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        MimaVaultApp.touch();
    }

    private void buildCategoryBar() {
        List<String> cats = new ArrayList<>();
        cats.add("全部");
        cats.addAll(Arrays.asList(Entry.CATEGORIES));
        for (String c : cats) {
            TextView chip = new TextView(this);
            chip.setText(c);
            chip.setTextSize(13);
            chip.setPadding(dp(16), dp(7), dp(16), dp(7));
            chip.setTextColor(getColor(R.color.text_secondary));
            chip.setBackgroundResource(R.drawable.bg_chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setTag(c);
            chip.setOnClickListener(v -> {
                currentCategory = (String) v.getTag();
                for (int i = 0; i < categoryBar.getChildCount(); i++) {
                    TextView tv = (TextView) categoryBar.getChildAt(i);
                    boolean selected = tv.getTag().equals(currentCategory);
                    tv.setTextColor(getColor(selected ? R.color.white : R.color.text_secondary));
                    tv.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
                }
                reload();
            });
            categoryBar.addView(chip);
        }
        // 默认选中"全部"
        ((TextView) categoryBar.getChildAt(0)).setTextColor(getColor(R.color.white));
        ((TextView) categoryBar.getChildAt(0)).setBackgroundResource(R.drawable.bg_chip_selected);
    }

    private void reload() {
        List<Entry> list = service.search(currentKeyword, currentCategory);
        adapter.setGroupedData(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        updateLetterBar();
    }

    /** 列表数据变化后刷新索引条的可见性与字母集合 */
    private void updateLetterBar() {
        List<String> letters = adapter.availableLetters();
        if (letters == null || letters.isEmpty()) {
            letterBar.setVisibility(View.GONE);
            return;
        }
        letterBar.setVisibility(View.VISIBLE);
        letterBar.setAvailableLetters(new HashSet<>(letters));
        syncActiveLetterFromList();
    }

    /** 点击 / 滑动索引条字母：跳到该字母组（不存在则跳到其后最近组） */
    private void jumpToLetter(String letter) {
        Integer pos = adapter.positionOfLetterOrNext(letter);
        if (pos == null) {
            return;
        }
        suppressIndexSync = true;
        letterBar.setActiveLetter(letter);
        ((LinearLayoutManager) recycler.getLayoutManager()).scrollToPositionWithOffset(pos, 0);
    }

    /** 依据列表首个可见项同步索引条高亮字母 */
    private void syncActiveLetterFromList() {
        if (recycler.getLayoutManager() == null) {
            return;
        }
        int first = ((LinearLayoutManager) recycler.getLayoutManager()).findFirstVisibleItemPosition();
        String letter = adapter.letterAt(first);
        letterBar.setActiveLetter(letter);
    }

    private void showMenu(View anchor) {
        android.util.Log.i("MimaVault", "showMenu called");
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(getString(R.string.export_menu));
        actions.add(() -> fileExportLauncher.launch("MimaVault-" + System.currentTimeMillis() + ".pmaster"));
        labels.add(getString(R.string.import_menu));
        actions.add(() -> fileImportLauncher.launch(new String[]{"*/*"}));
        labels.add(getString(R.string.export_csv));
        actions.add(this::showCsvExportRiskDialog);
        labels.add(getString(R.string.import_csv));
        actions.add(() -> fileCsvImportLauncher.launch(new String[]{"*/*"}));
        labels.add(getString(R.string.qr_export));
        actions.add(() -> QrExportActivity.start(this));
        labels.add(getString(R.string.qr_import));
        actions.add(() -> QrImportActivity.start(this));
        labels.add(getString(R.string.security_report));
        actions.add(() -> SecurityReportActivity.start(this));

        // 修改主密码：需先验证当前主密码，成功后全量重加密
        labels.add(getString(R.string.change_master_menu));
        actions.add(() -> verifyMasterThen(() -> ChangeMasterPasswordActivity.start(MainActivity.this)));

        // 手势密码（主密码为主、手势可选）：设置 / 修改前必须验证主密码
        boolean gestureSet = GestureUnlockHelper.isGestureSet(this);
        labels.add(getString(gestureSet ? R.string.gesture_menu_modify : R.string.gesture_menu_set));
        actions.add(() -> verifyMasterThen(MainActivity.this::startGestureSetup));
        if (gestureSet) {
            labels.add(getString(R.string.gesture_clear_login));
            actions.add(() -> verifyMasterThen(MainActivity.this::clearGestureUnlock));
        }

        labels.add(getString(R.string.trash));
        actions.add(() -> TrashActivity.start(this));
        labels.add(getString(R.string.logout));
        actions.add(this::confirmLogout);

        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** 设置 / 修改 / 清除手势密码统一入口：先弹主密码验证框，通过后才继续 */
    private void verifyMasterThen(Runnable onVerified) {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.gesture_verify_title)
                .setMessage(R.string.gesture_verify_hint)
                .setPositiveButton(R.string.gesture_verify_ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint(R.string.master_password);
        dialog.setView(et, dp(24), dp(8), dp(24), 0);
        final boolean[] canceled = {false};
        dialog.setOnDismissListener(d -> canceled[0] = true);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pwdStr = et.getText().toString();
            if (pwdStr.isEmpty()) {
                Toast.makeText(this, R.string.master_password, Toast.LENGTH_SHORT).show();
                return;
            }
            et.setEnabled(false);
            new Thread(() -> {
                char[] pwd = pwdStr.toCharArray();
                PasswordService.VerifyResult result = service.verifyMasterPassword(pwd);
                main.post(() -> {
                    if (canceled[0]) {
                        return;
                    }
                    if (result == PasswordService.VerifyResult.MISMATCH) {
                        et.setEnabled(true);
                        et.setText("");
                        Toast.makeText(this, R.string.gesture_verify_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    onVerified.run();
                });
            }).start();
        }));
        dialog.show();
    }

    private void startGestureSetup() {
        Intent i = new Intent(this, GestureActivity.class);
        startActivityForResult(i, REQ_GESTURE_LOGIN);
    }

    private void clearGestureUnlock() {
        GestureUnlockHelper.clear(this);
        Toast.makeText(this, R.string.gesture_cleared, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_GESTURE_LOGIN && resultCode == RESULT_OK && data != null) {
            String seq = data.getStringExtra(GestureActivity.EXTRA_SEQ);
            if (seq == null || !GestureParser.valid(seq)) {
                Toast.makeText(this, R.string.gesture_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            VaultSession session = VaultSession.get();
            GestureUnlockHelper.setup(this, seq, session.key().getEncoded(),
                    session.masterSaltHex(), session.iterations());
            Toast.makeText(this, R.string.gesture_setup_success, Toast.LENGTH_SHORT).show();
        }
    }

    private void doExport(Uri uri) {
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "export.pmaster");
                List<Entry> entries = service.listEntries();
                BackupService.exportToFile(entries, VaultSession.get().key(),
                        VaultSession.get().masterSaltHex(), VaultSession.get().iterations(), tmp);
                try (InputStream is = new java.io.FileInputStream(tmp);
                     FileOutputStream fos = (FileOutputStream) getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }
                main.post(() -> Toast.makeText(this, R.string.export_success, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void doImport(Uri uri) {
        if (VaultSession.get().masterPasswordCopy() == null) {
            Toast.makeText(this, "请使用主密码解锁后导入", Toast.LENGTH_LONG).show();
            return;
        }
        // 先读取备份文件内容，再让用户输入该备份文件的主密码（跨密码导入）
        new Thread(() -> {
            String content;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                content = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                android.util.Log.e("MimaVault", "read import file failed", e);
                main.post(() -> Toast.makeText(this, getString(R.string.import_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                return;
            }
            final String finalContent = content;
            main.post(() -> showBackupPasswordDialog(finalContent));
        }).start();
    }

    private void showBackupPasswordDialog(String content) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.backup_password_title);
        b.setMessage(R.string.backup_password_hint);
        EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint(R.string.backup_password_title);
        b.setView(et);
        b.setPositiveButton(R.string.import_ok, null);
        b.setNegativeButton(R.string.cancel, null);
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String backupPassword = et.getText().toString().trim();
            if (backupPassword.isEmpty()) {
                Toast.makeText(this, R.string.backup_password_empty, Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            showImportModeDialog(content, backupPassword.toCharArray());
        }));
        dialog.show();
    }

    private void showImportModeDialog(String content, char[] backupPassword) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("导入 .pmaster");
        b.setItems(new String[]{getString(R.string.merge_import), getString(R.string.overwrite_import)}, (d, which) -> {
            boolean overwrite = which == 1;
            new Thread(() -> {
                try {
                    BackupService.ImportResult result = BackupService.importFromContentDetailed(
                            content, backupPassword, VaultSession.get().key());
                    BackupModel.BackupPackage pack = result.pack;
                    android.util.Log.i("MimaVault", "importFromContent OK items=" + (pack.items == null ? 0 : pack.items.size()));
                    int[] stat = BackupService.restore(pack, service, overwrite, result.backupKey, VaultSession.get().key());
                    android.util.Log.i("MimaVault", "restore OK restored=" + stat[0] + " skipped=" + stat[1]);
                    main.post(() -> {
                        Toast.makeText(this, getString(R.string.import_success, stat[0], stat[1]), Toast.LENGTH_LONG).show();
                        reload();
                    });
                } catch (Exception e) {
                    android.util.Log.e("MimaVault", "import failed", e);
                    main.post(() -> Toast.makeText(this, getString(R.string.import_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
        b.setNegativeButton(R.string.cancel, null);
        b.show();
    }

    /** 下次 CSV 导出是否脱敏（默认脱敏；完整明文需在风险对话框二次确认后置为 false） */
    private boolean csvExportMasked = true;

    /** CSV 导出风险二次确认：默认走脱敏导出，完整明文导出必须再确认一次 */
    private void showCsvExportRiskDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.csv_export_risk_title)
                .setMessage(R.string.csv_export_risk_message)
                .setPositiveButton(R.string.csv_export_masked, (d, w) -> {
                    csvExportMasked = true;
                    fileCsvExportLauncher.launch("MimaVault-" + System.currentTimeMillis() + ".csv");
                })
                .setNeutralButton(R.string.csv_export_plain, (d, w) -> confirmPlainCsvExport())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 完整明文导出的第二道确认 */
    private void confirmPlainCsvExport() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.csv_export_plain_confirm_title)
                .setMessage(R.string.csv_export_plain_confirm_message)
                .setPositiveButton(R.string.csv_export_plain_confirm_ok, (d, w) -> {
                    csvExportMasked = false;
                    fileCsvExportLauncher.launch("MimaVault-" + System.currentTimeMillis() + ".csv");
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doExportCsv(Uri uri) {
        final boolean masked = csvExportMasked;
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "export.csv");
                List<Entry> entries = service.listEntries();
                CsvUtil.export(entries, VaultSession.get().key(), tmp.toPath(), masked);
                try (InputStream is = new java.io.FileInputStream(tmp);
                     FileOutputStream fos = (FileOutputStream) getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }
                main.post(() -> Toast.makeText(this,
                        masked ? R.string.csv_export_masked_success : R.string.csv_export_plain_success,
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "CSV 导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void doImportCsv(Uri uri) {
        if (VaultSession.get().masterPasswordCopy() == null) {
            Toast.makeText(this, "请使用主密码解锁后导入", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_csv)
                .setMessage(R.string.csv_import_confirm)
                .setPositiveButton(R.string.import_ok, (d, w) -> {
                    new Thread(() -> {
                        try {
                            File tmp = new File(getCacheDir(), "import.csv");
                            try (InputStream is = getContentResolver().openInputStream(uri);
                                 FileOutputStream fos = new FileOutputStream(tmp)) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = is.read(buf)) != -1) {
                                    fos.write(buf, 0, n);
                                }
                            }
                            List<CsvUtil.CsvRow> rows = CsvUtil.parse(tmp.toPath());
                            int ok = 0, fail = 0;
                            for (CsvUtil.CsvRow r : rows) {
                                try {
                                    Entry e = new Entry();
                                    e.setCategory(r.url != null && !r.url.isEmpty() ? Entry.CATEGORY_WEBSITE : Entry.CATEGORY_OTHER);
                                    e.setPlatform(r.name);
                                    e.setAccount(r.username);
                                    e.setPhone("");
                                    e.setEmail("");
                                    e.setNote(r.notes);
                                    e.setImagePath("");
                                    e.setGestureSeq("");
                                    e.setSyncStatus("local");
                                    service.addEntry(e, r.password, VaultSession.get().key());
                                    ok++;
                                } catch (Exception ex) {
                                    fail++;
                                }
                            }
                            final int okF = ok, failF = fail;
                            main.post(() -> {
                                Toast.makeText(this, getString(R.string.csv_import_done, okF, failF), Toast.LENGTH_LONG).show();
                                reload();
                            });
                        } catch (Exception e) {
                            main.post(() -> Toast.makeText(this, "CSV 导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("退出后需重新输入主密码解锁，确定退出吗？")
                .setPositiveButton("退出", (d, w) -> {
                    VaultSession.get().close();
                    Intent i = new Intent(this, UnlockActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
