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
 * 条目列表适配器：支持"分组排序 + 组头"模式。
 * A9.17.0 四梯队排序规则：
 *   梯队1：收藏条目（右侧 ☆，金色）置顶，内部按首字母拼音全拼排序，英文条目在中文前；
 *   梯队2：非收藏中 点开次数最大的 3 个（右侧 *），按次数降序；
 *   梯队3：其余非收藏条目按首字母拼音排序（规则同梯队1）；
 *   梯队4：首字符为特殊符号 / 数字的条目归 "#" 组排最后（右侧 #），内部按后续字符排序。
 * 组序列：☆ 收藏 → * 热门 → A-Z 普通组 → #。搜索过滤走同一 setGroupedData 入口自动同步。
 */
public class EntryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_ENTRY = 0;
    private static final int TYPE_HEADER = 1;

    /** 收藏组 / 热门组组头 key（索引条、组头定位共用） */
    private static final String GROUP_FAV = "☆";
    private static final String GROUP_HOT = "*";

    public interface Listener {
        void onClick(Entry entry);

        void onLongClick(Entry entry);

        /** 列表收藏星标点击：切换收藏状态 */
        void onFavoriteToggle(Entry entry);
    }

    private final List<Entry> entries = new ArrayList<>();
    /** 扁平行：String 表示组头 key，Entry 表示条目 */
    private final List<Object> rows = new ArrayList<>();
    /** 组 key -> 组头在 rows 中的位置（保持列表顺序） */
    private final Map<String, Integer> groupPos = new LinkedHashMap<>();
    /** 梯队2 热门条目集合（非收藏、点开次数最大的最多 3 个，次数 > 0） */
    private final java.util.Set<Entry> hotSet = new java.util.HashSet<>();
    private final Listener listener;

    public EntryAdapter(Listener listener) {
        this.listener = listener;
    }

    /**
     * A9.17.0 四梯队分组排序：
     *   梯队1 收藏（☆）→ 梯队2 热门 Top3（*，按次数降序）→
     *   梯队3 其余非收藏按首字母拼音（英文在前、中文拼音在后）→ 梯队4 # 特殊符号组最后。
     * 组序列：☆ → * → A-Z → #；组头 key 与索引条共用。
     */
    public void setGroupedData(List<Entry> data) {
        entries.clear();
        entries.addAll(data);
        rows.clear();
        groupPos.clear();
        hotSet.clear();
        if (!entries.isEmpty()) {
            // ---- 1. 划分梯队 ----
            List<Entry> favs = new ArrayList<>();
            List<Entry> others = new ArrayList<>();
            for (Entry e : entries) {
                if (e.isFavorite()) {
                    favs.add(e);
                } else {
                    others.add(e);
                }
            }
            // 梯队2：非收藏中 点开次数最大的 3 个（次数 > 0 才入热门）
            Collections.sort(others, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return Long.compare(b.getUseCount(), a.getUseCount());
                }
            });
            List<Entry> hots = new ArrayList<>();
            for (Entry e : others) {
                if (e.getUseCount() > 0 && hots.size() < 3) {
                    hots.add(e);
                }
            }
            hotSet.addAll(hots);
            others.removeAll(hots);
            // ---- 2. 各梯队内部排序 ----
            Collections.sort(favs, PINYIN_KEY_COMPARATOR);
            Collections.sort(hots, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return Long.compare(b.getUseCount(), a.getUseCount());
                }
            });
            // 梯队3 按 A-Z 分组（先按拼音键排序，保证字母组按 A-Z 顺序插入且组内有序）；
            // # 特殊符号进梯队4
            Collections.sort(others, PINYIN_KEY_COMPARATOR);
            Map<String, List<Entry>> letterGroups = new LinkedHashMap<>();
            List<Entry> specials = new ArrayList<>();
            for (Entry e : others) {
                String g = PinyinUtil.groupOf(e.getPlatform());
                if (PinyinUtil.HASH.equals(g)) {
                    specials.add(e);
                } else {
                    List<Entry> list = letterGroups.get(g);
                    if (list == null) {
                        list = new ArrayList<>();
                        letterGroups.put(g, list);
                    }
                    list.add(e);
                }
            }
            for (List<Entry> list : letterGroups.values()) {
                Collections.sort(list, PINYIN_KEY_COMPARATOR);
            }
            Collections.sort(specials, SPECIAL_KEY_COMPARATOR);
            // ---- 3. 拼接扁平行 + 记录组头位置（☆ → * → A-Z → #）----
            if (!favs.isEmpty()) {
                int pos = rows.size();
                rows.add(GROUP_FAV);
                groupPos.put(GROUP_FAV, pos);
                rows.addAll(favs);
            }
            if (!hots.isEmpty()) {
                int pos = rows.size();
                rows.add(GROUP_HOT);
                groupPos.put(GROUP_HOT, pos);
                rows.addAll(hots);
            }
            for (Map.Entry<String, List<Entry>> g : letterGroups.entrySet()) {
                int pos = rows.size();
                rows.add(g.getKey());
                groupPos.put(g.getKey(), pos);
                rows.addAll(g.getValue());
            }
            if (!specials.isEmpty()) {
                int pos = rows.size();
                rows.add(PinyinUtil.HASH);
                groupPos.put(PinyinUtil.HASH, pos);
                rows.addAll(specials);
            }
        }
        notifyDataSetChanged();
    }

    /** 梯队1/梯队3 拼音排序键：英文/数字/符号在前，中文在后；同段按拼音全拼，再按原文兜底 */
    private static final Comparator<Entry> PINYIN_KEY_COMPARATOR = new Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            int lp = Integer.compare(
                    PinyinUtil.langPriority(a.getPlatform()),
                    PinyinUtil.langPriority(b.getPlatform()));
            if (lp != 0) {
                return lp;
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
    };

    /** 梯队4 # 组内部排序：跳过首符号，按后续字符拼音/字符序 */
    private static final Comparator<Entry> SPECIAL_KEY_COMPARATOR = new Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            String sa = a.getPlatform() == null || a.getPlatform().length() < 2
                    ? "" : a.getPlatform().substring(1);
            String sb = b.getPlatform() == null || b.getPlatform().length() < 2
                    ? "" : b.getPlatform().substring(1);
            int c = PinyinUtil.sortKey(sa).compareTo(PinyinUtil.sortKey(sb));
            if (c != 0) {
                return c;
            }
            return sa.compareToIgnoreCase(sb);
        }
    };

    /** 组头 key 展示文本：☆ 收藏 / * 热门 / 字母 / # */
    private static String headerText(String key) {
        if (GROUP_FAV.equals(key)) {
            return "☆ 收藏";
        }
        if (GROUP_HOT.equals(key)) {
            return "* 热门";
        }
        return key;
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

    /** 当前存在的组 key（☆ → * → A-Z → #） */
    public List<String> availableLetters() {
        return new ArrayList<>(groupPos.keySet());
    }

    /**
     * 定位某组所在组的首行位置；该组不存在时，返回其后最近一个存在的组，
     * 若其后也没有则返回 null。
     * A9.17.0：# 组恒在最后（梯队4），点击普通字母时跳过 # 组，仅点击 # 直接命中。
     */
    public Integer positionOfLetterOrNext(String letter) {
        if (rows.isEmpty()) {
            return null;
        }
        Integer hit = groupPos.get(letter);
        if (hit != null) {
            return hit;
        }
        int targetRank = groupRank(letter);
        Integer best = null;
        for (Map.Entry<String, Integer> e : groupPos.entrySet()) {
            int r = groupRank(e.getKey());
            // # 组恒在最后，点击普通字母（A-Z / ☆ / *）时跳过 # 组
            if (targetRank < RANK_HASH && r == RANK_HASH) {
                continue;
            }
            if (r >= targetRank) {
                best = e.getValue();
                break;
            }
        }
        return best;
    }

    /** 组层级：☆=0，*=1，A-Z=2..27，#=28 */
    private static final int RANK_HASH = 28;

    private static int groupRank(String key) {
        if (GROUP_FAV.equals(key)) {
            return 0;
        }
        if (GROUP_HOT.equals(key)) {
            return 1;
        }
        int r = PinyinUtil.rankOf(key);
        if (r >= PinyinUtil.GROUP_COUNT - 1) {
            return RANK_HASH;
        }
        return 2 + r;
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
            ((HeaderVH) holder).tvHeader.setText(headerText((String) rows.get(position)));
            return;
        }
        EntryVH vh = (EntryVH) holder;
        Entry e = (Entry) rows.get(position);
        vh.tvPlatform.setText(e.getPlatform() == null || e.getPlatform().isEmpty() ? "(未命名)" : e.getPlatform());
        vh.chipCategory.setText(e.getCategory() == null ? Entry.CATEGORY_WEBSITE : e.getCategory());
        // A9.17.0 四梯队右侧标识：收藏 ☆（金色）/ 热门 *（primary）/ # 组 #（灰）/ 普通 ☆（灰，点击收藏）
        if (e.isFavorite()) {
            vh.tvFavorite.setText("☆");
            vh.tvFavorite.setTextColor(vh.itemView.getContext().getColor(R.color.warning));
        } else if (hotSet.contains(e)) {
            vh.tvFavorite.setText("*");
            vh.tvFavorite.setTextColor(vh.itemView.getContext().getColor(R.color.primary));
        } else if (PinyinUtil.HASH.equals(PinyinUtil.groupOf(e.getPlatform()))) {
            vh.tvFavorite.setText("#");
            vh.tvFavorite.setTextColor(vh.itemView.getContext().getColor(R.color.text_hint));
        } else {
            vh.tvFavorite.setText("☆");
            vh.tvFavorite.setTextColor(vh.itemView.getContext().getColor(R.color.text_hint));
        }
        // 使用次数角标：有使用记录时展示
        if (e.getUseCount() > 0) {
            vh.tvUseCount.setText(vh.itemView.getContext().getString(R.string.use_count_format, e.getUseCount()));
            vh.tvUseCount.setVisibility(View.VISIBLE);
        } else {
            vh.tvUseCount.setVisibility(View.GONE);
        }
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
        vh.tvFavorite.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos < 0 || pos >= rows.size() || !(rows.get(pos) instanceof Entry)) {
                return;
            }
            listener.onFavoriteToggle((Entry) rows.get(pos));
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
        TextView tvFavorite;
        TextView tvUseCount;

        EntryVH(@NonNull View itemView) {
            super(itemView);
            tvPlatform = itemView.findViewById(R.id.tvPlatform);
            chipCategory = itemView.findViewById(R.id.chipCategory);
            tvAccount = itemView.findViewById(R.id.tvAccount);
            tvPasswordMask = itemView.findViewById(R.id.tvPasswordMask);
            tvFavorite = itemView.findViewById(R.id.tvFavorite);
            tvUseCount = itemView.findViewById(R.id.tvUseCount);
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
