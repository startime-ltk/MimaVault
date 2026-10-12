package com.mimavault.util;

import com.mimavault.model.Entry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 主列表四梯队排序器（与安卓端 A9.17.0 EntryAdapter.setGroupedData 对齐）。
 * 排序规则：
 *   梯队1：收藏条目置顶，内部按首字母拼音全拼排序，英文条目在中文前；
 *   梯队2：非收藏中 点开次数最大的 3 个（次数 > 0 才入热门），按次数降序；
 *   梯队3：其余非收藏条目按首字母拼音排序（规则同梯队1）；
 *   梯队4：首字符为特殊符号 / 数字的条目归 "#" 组排最后，内部按后续字符排序。
 * 与安卓端差异：Swing JTable 无组头行，排序结果直接平铺返回；
 * 收藏/热门标识通过列内 ☆ / * 图标体现，分组信息见分组位置统计。
 */
public final class EntrySorter {

    /** 热门梯队最多条数（与安卓端一致） */
    public static final int HOT_TOP_N = 3;

    private EntrySorter() {
    }

    /**
     * 返回四梯队排序后的条目列表（平铺，无组头行）。
     * 同时返回热门 Top3 的 id 集合（供表格行渲染 * 标识）。
     */
    public static List<Entry> sort(List<Entry> data, List<Long> hotIdsOut) {
        List<Entry> result = new ArrayList<>(data.size());
        if (data.isEmpty()) {
            return result;
        }
        List<Entry> favs = new ArrayList<>();
        List<Entry> others = new ArrayList<>();
        for (Entry e : data) {
            if (e.isFavorite()) {
                favs.add(e);
            } else {
                others.add(e);
            }
        }
        // 梯队2：非收藏中 点开次数最大的 3 个（次数 > 0 才入热门）
        Collections.sort(others, USE_COUNT_DESC);
        List<Entry> hots = new ArrayList<>();
        for (Entry e : others) {
            if (e.getUseCount() > 0 && hots.size() < HOT_TOP_N) {
                hots.add(e);
            }
        }
        others.removeAll(hots);
        // 各梯队内部排序
        Collections.sort(favs, PINYIN_KEY_COMPARATOR);
        Collections.sort(hots, USE_COUNT_DESC);
        Collections.sort(others, PINYIN_KEY_COMPARATOR);
        List<Entry> specials = new ArrayList<>();
        List<Entry> letters = new ArrayList<>();
        for (Entry e : others) {
            if (PinyinUtil.HASH.equals(PinyinUtil.groupOf(e.getPlatform()))) {
                specials.add(e);
            } else {
                letters.add(e);
            }
        }
        Collections.sort(specials, SPECIAL_KEY_COMPARATOR);
        // 拼接：☆ → * → A-Z → #
        result.addAll(favs);
        result.addAll(hots);
        result.addAll(letters);
        result.addAll(specials);
        if (hotIdsOut != null) {
            hotIdsOut.clear();
            for (Entry e : hots) {
                hotIdsOut.add(e.getId());
            }
        }
        return result;
    }

    /** 使用次数降序 */
    private static final Comparator<Entry> USE_COUNT_DESC = new Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            return Long.compare(b.getUseCount(), a.getUseCount());
        }
    };

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
}
