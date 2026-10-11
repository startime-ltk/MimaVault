package com.mimavault.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mimavault.R;
import com.mimavault.model.Entry;
import com.mimavault.util.PinyinUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 条目列表适配器：支持"按首字母分组排序 + 组头"模式。
 * 分组规则见 PinyinUtil：A-Z 组 + # 组（数字/符号/无法归组）。
 * 组头作为独立 viewType 插入条目之间，索引条据此做字母 → 列表位置映射。
 */
public class EntryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_ENTRY = 0;
    private static final int TYPE_HEADER = 1;

    public interface Listener {
        void onClick(Entry entry);

        void onLongClick(Entry entry);
    }

    private final List<Entry> entries = new ArrayList<>();
    /** 扁平行：String 表示组头字母，Entry 表示条目 */
    private final List<Object> rows = new ArrayList<>();
    /** 分组字母 -> 组头在 rows 中的位置（保持字母序） */
    private final Map<String, Integer> groupPos = new LinkedHashMap<>();
    private final Listener listener;

    public EntryAdapter(Listener listener) {
        this.listener = listener;
    }

    /**
     * 分组模式：先按 分组字母 + 组内拼音 排序，再插入组头。
     */
    public void setGroupedData(List<Entry> data) {
        entries.clear();
        entries.addAll(data);
        rows.clear();
        groupPos.clear();
        if (!entries.isEmpty()) {
            List<Entry> sorted = new ArrayList<>(entries);
            Collections.sort(sorted, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    String ga = PinyinUtil.groupOf(a.getPlatform());
                    String gb = PinyinUtil.groupOf(b.getPlatform());
                    int r = Integer.compare(PinyinUtil.rankOf(ga), PinyinUtil.rankOf(gb));
                    if (r != 0) {
                        return r;
                    }
                    String ka = PinyinUtil.sortKey(a.getPlatform());
                    String kb = PinyinUtil.sortKey(b.getPlatform());
                    int c = ka.compareTo(kb);
                    if (c != 0) {
                        return c;
                    }
                    String na = a.getPlatform() == null ? "" : a.getPlatform();
                    String nb = b.getPlatform() == null ? "" : b.getPlatform();
                    return na.compareToIgnoreCase(nb);
                }
            });
            String lastGroup = null;
            for (Entry e : sorted) {
                String g = PinyinUtil.groupOf(e.getPlatform());
                if (!g.equals(lastGroup)) {
                    rows.add(g);
                    groupPos.put(g, rows.size() - 1);
                    lastGroup = g;
                }
                rows.add(e);
            }
        }
        notifyDataSetChanged();
    }

    /** 保持原有语义的兼容入口（不分组，仅刷新列表） */
    public void setData(List<Entry> data) {
        entries.clear();
        entries.addAll(data);
        rows.clear();
        rows.addAll(data);
        groupPos.clear();
        notifyDataSetChanged();
    }

    /** 当前存在的分组字母（字母序，末尾可能为 #） */
    public List<String> availableLetters() {
        return new ArrayList<>(groupPos.keySet());
    }

    /**
     * 定位某字母所在组的首行位置；该字母组不存在时，返回其后最近一个存在的组，
     * 若其后也没有则返回 null。
     */
    public Integer positionOfLetterOrNext(String letter) {
        if (rows.isEmpty()) {
            return null;
        }
        Integer hit = groupPos.get(letter);
        if (hit != null) {
            return hit;
        }
        int targetRank = PinyinUtil.rankOf(letter);
        Integer best = null;
        for (Map.Entry<String, Integer> e : groupPos.entrySet()) {
            int r = PinyinUtil.rankOf(e.getKey());
            if (r >= targetRank) {
                best = e.getValue();
                break;
            }
        }
        return best;
    }

    /** 根据扁平列表位置得到所在组字母（向前找最近的组头），越界返回 null */
    public String letterAt(int position) {
        if (rows.isEmpty() || position < 0 || position >= rows.size()) {
            return null;
        }
        for (int i = position; i >= 0; i--) {
            Object o = rows.get(i);
            if (o instanceof String) {
                return (String) o;
            }
        }
        return null;
    }

    /** 分组模式下共多少组 */
    public int groupCount() {
        return groupPos.size();
    }

    public boolean hasLetter(String letter) {
        return groupPos.containsKey(letter);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View v = inflater.inflate(R.layout.item_entry_header, parent, false);
            return new HeaderVH(v);
        }
        View v = inflater.inflate(R.layout.item_entry, parent, false);
        return new EntryVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).tvHeader.setText((String) rows.get(position));
            return;
        }
        EntryVH vh = (EntryVH) holder;
        Entry e = (Entry) rows.get(position);
        vh.tvPlatform.setText(e.getPlatform() == null || e.getPlatform().isEmpty() ? "(未命名)" : e.getPlatform());
        vh.chipCategory.setText(e.getCategory() == null ? Entry.CATEGORY_WEBSITE : e.getCategory());
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
        vh.tvAccount.setText(account.length() == 0 ? "（无账号信息）" : account.toString());
        String enc = e.getPasswordEnc();
        if (enc == null || enc.isEmpty()) {
            vh.tvPasswordMask.setText("未设置密码");
        } else {
            StringBuilder mask = new StringBuilder("••••••••");
            if (e.getGestureSeq() != null && !e.getGestureSeq().isEmpty()) {
                mask.append("  ◈手势");
            }
            if (e.getImagePath() != null && !e.getImagePath().isEmpty()) {
                mask.append("  ▣图片");
            }
            vh.tvPasswordMask.setText(mask.toString());
        }
        vh.itemView.setOnClickListener(v -> listener.onClick((Entry) rows.get(holder.getBindingAdapterPosition())));
        vh.itemView.setOnLongClickListener(v -> {
            listener.onLongClick((Entry) rows.get(holder.getBindingAdapterPosition()));
            return true;
        });
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof String ? TYPE_HEADER : TYPE_ENTRY;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class EntryVH extends RecyclerView.ViewHolder {
        TextView tvPlatform;
        TextView chipCategory;
        TextView tvAccount;
        TextView tvPasswordMask;

        EntryVH(@NonNull View itemView) {
            super(itemView);
            tvPlatform = itemView.findViewById(R.id.tvPlatform);
            chipCategory = itemView.findViewById(R.id.chipCategory);
            tvAccount = itemView.findViewById(R.id.tvAccount);
            tvPasswordMask = itemView.findViewById(R.id.tvPasswordMask);
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tvHeader);
        }
    }
}
