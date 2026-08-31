package com.mimavault.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mimavault.R;
import com.mimavault.model.Entry;

import java.util.ArrayList;
import java.util.List;

/**
 * 条目列表适配器
 */
public class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.VH> {

    public interface Listener {
        void onClick(Entry entry);

        void onLongClick(Entry entry);
    }

    private final List<Entry> items = new ArrayList<>();
    private final Listener listener;

    public EntryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setData(List<Entry> data) {
        items.clear();
        items.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_entry, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Entry e = items.get(position);
        holder.tvPlatform.setText(e.getPlatform() == null || e.getPlatform().isEmpty() ? "(未命名)" : e.getPlatform());
        holder.chipCategory.setText(e.getCategory() == null ? Entry.CATEGORY_WEBSITE : e.getCategory());
        StringBuilder account = new StringBuilder();
        if (e.getAccount() != null && !e.getAccount().isEmpty()) {
            account.append(e.getAccount());
        }
        if (e.getPhone() != null && !e.getPhone().isEmpty()) {
            if (account.length() > 0) {
                account.append(" · ");
            }
            account.append(e.getPhone());
        }
        if (e.getEmail() != null && !e.getEmail().isEmpty()) {
            if (account.length() > 0) {
                account.append(" · ");
            }
            account.append(e.getEmail());
        }
        holder.tvAccount.setText(account.length() == 0 ? "（无账号信息）" : account.toString());
        String enc = e.getPasswordEnc();
        if (enc == null || enc.isEmpty()) {
            holder.tvPasswordMask.setText("未设置密码");
        } else {
            StringBuilder mask = new StringBuilder("••••••••");
            if (e.getGestureSeq() != null && !e.getGestureSeq().isEmpty()) {
                mask.append("  ◈手势");
            }
            if (e.getImagePath() != null && !e.getImagePath().isEmpty()) {
                mask.append("  ▣图片");
            }
            holder.tvPasswordMask.setText(mask.toString());
        }
        holder.itemView.setOnClickListener(v -> listener.onClick(items.get(holder.getBindingAdapterPosition())));
        holder.itemView.setOnLongClickListener(v -> {
            listener.onLongClick(items.get(holder.getBindingAdapterPosition()));
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvPlatform;
        TextView chipCategory;
        TextView tvAccount;
        TextView tvPasswordMask;

        VH(@NonNull View itemView) {
            super(itemView);
            tvPlatform = itemView.findViewById(R.id.tvPlatform);
            chipCategory = itemView.findViewById(R.id.chipCategory);
            tvAccount = itemView.findViewById(R.id.tvAccount);
            tvPasswordMask = itemView.findViewById(R.id.tvPasswordMask);
        }
    }
}
