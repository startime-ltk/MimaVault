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
import com.mimavault.service.PasswordService;
import com.mimavault.service.VaultSession;
import com.mimavault.util.CsvUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 主界面：条目列表、模糊搜索、分类筛选、导入/导出、二维码互导入口
 */
public class MainActivity extends AppCompatActivity {

    private PasswordService service;
    private final Handler main = new Handler(Looper.getMainLooper());

    private RecyclerView recycler;
    private TextView tvEmpty;
    private EditText etSearch;
    private EntryAdapter adapter;
    private FloatingActionButton fabAdd;
    private LinearLayout categoryBar;

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

        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);
        fabAdd = findViewById(R.id.fabAdd);
        categoryBar = findViewById(R.id.categoryBar);
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
                    tv.setTextColor(getColor(selected ? R.color.primary : R.color.text_secondary));
                    tv.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
                }
                reload();
            });
            categoryBar.addView(chip);
        }
        // 默认选中"全部"
        ((TextView) categoryBar.getChildAt(0)).setTextColor(getColor(R.color.primary));
        ((TextView) categoryBar.getChildAt(0)).setBackgroundResource(R.drawable.bg_chip_selected);
    }

    private void reload() {
        List<Entry> list = service.search(currentKeyword, currentCategory);
        adapter.setData(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showMenu(View anchor) {
        android.util.Log.i("MimaVault", "showMenu called");
        String[] items = {
                getString(R.string.export_menu),
                getString(R.string.import_menu),
                getString(R.string.export_csv),
                getString(R.string.import_csv),
                getString(R.string.qr_export),
                getString(R.string.qr_import),
                getString(R.string.security_report),
                getString(R.string.trash),
                getString(R.string.logout)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            fileExportLauncher.launch("MimaVault-" + System.currentTimeMillis() + ".pmaster");
                            break;
                        case 1:
                            fileImportLauncher.launch(new String[]{"*/*"});
                            break;
                        case 2:
                            fileCsvExportLauncher.launch("MimaVault-" + System.currentTimeMillis() + ".csv");
                            break;
                        case 3:
                            fileCsvImportLauncher.launch(new String[]{"*/*"});
                            break;
                        case 4:
                            QrExportActivity.start(this);
                            break;
                        case 5:
                            QrImportActivity.start(this);
                            break;
                        case 6:
                            SecurityReportActivity.start(this);
                            break;
                        case 7:
                            TrashActivity.start(this);
                            break;
                        case 8:
                            confirmLogout();
                            break;
                    }
                })
                .show();
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
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("导入 .pmaster");
        b.setItems(new String[]{getString(R.string.merge_import), getString(R.string.overwrite_import)}, (d, which) -> {
            boolean overwrite = which == 1;
            new Thread(() -> {
                try {
                    String content;
                    try (InputStream is = getContentResolver().openInputStream(uri)) {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                        content = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    }
                    BackupService.ImportResult result = BackupService.importFromContentDetailed(
                            content, VaultSession.get().masterPasswordCopy(), VaultSession.get().key());
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

    private void doExportCsv(Uri uri) {
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "export.csv");
                List<Entry> entries = service.listEntries();
                CsvUtil.export(entries, VaultSession.get().key(), tmp.toPath());
                try (InputStream is = new java.io.FileInputStream(tmp);
                     FileOutputStream fos = (FileOutputStream) getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }
                main.post(() -> Toast.makeText(this, R.string.csv_export_success, Toast.LENGTH_LONG).show());
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
