package com.mimavault.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mimavault.MimaVaultApp;
import com.mimavault.R;
import com.mimavault.util.InsetsUtil;
import com.mimavault.model.Entry;
import com.mimavault.service.PasswordService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 回收站：查看已删除条目，支持恢复、彻底删除、清空回收站
 */
public class TrashActivity extends AppCompatActivity {

    private PasswordService service;
    private RecyclerView recycler;
    private TextView tvEmpty;
    private TrashAdapter adapter;

    public static void start(android.app.Activity from) {
        from.startActivity(new Intent(from, TrashActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trash);
        InsetsUtil.applyTopInset(findViewById(R.id.headerTrash));
        service = new PasswordService(MimaVaultApp.db());

        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TrashAdapter();
        recycler.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPurgeAll).setOnClickListener(v -> confirmPurgeAll());

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (service != null && service.isInitialized()) {
            reload();
        }
    }

    private void reload() {
        List<Entry> list = service.listTrashed();
        adapter.setData(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmPurgeAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_purge_all)
                .setMessage(R.string.trash_purge_all_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    service.purgeAllTrashed();
                    Toast.makeText(this, R.string.trash_purged, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showItemActions(Entry e) {
        String[] items = {getString(R.string.trash_restore), getString(R.string.trash_purge)};
        new AlertDialog.Builder(this)
                .setTitle(e.getPlatform() == null || e.getPlatform().isEmpty() ? e.getAccount() : e.getPlatform())
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        service.restore(e.getId());
                        Toast.makeText(this, R.string.trash_restored, Toast.LENGTH_SHORT).show();
                    } else {
                        service.purge(e.getId());
                        Toast.makeText(this, R.string.trash_purged, Toast.LENGTH_SHORT).show();
                    }
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- Adapter ----------

    private class TrashAdapter extends RecyclerView.Adapter<TrashAdapter.VH> {

        private final List<Entry> data = new ArrayList<>();
        private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        void setData(List<Entry> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_trash, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Entry e = data.get(position);
            String title = e.getPlatform();
            if (title == null || title.isEmpty()) title = e.getAccount();
            holder.tvTitle.setText(title == null || title.isEmpty() ? "(无标题)" : title);
            String sub = e.getAccount() == null ? "" : e.getAccount();
            Date deleted = e.getDeletedAt();
            if (deleted != null) {
                sub = sub + "  ·  删除于 " + fmt.format(deleted);
            }
            holder.tvSub.setText(sub.trim());
            holder.itemView.setOnClickListener(v -> showItemActions(e));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvTitle;
            final TextView tvSub;

            VH(@NonNull View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tvTitle);
                tvSub = itemView.findViewById(R.id.tvSub);
            }
        }
    }
}
