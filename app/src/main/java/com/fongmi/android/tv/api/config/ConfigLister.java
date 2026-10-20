package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ConfigDepot;
import com.fongmi.android.tv.db.AppDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 源列表行构建：把 Config 平铺结果整理为「多仓源（可折叠展开子源）/ 单源」两组，
 * 附带当前标记、子源数量、上次使用时间等徽标文本，供 HistoryDialog 双端适配器使用。
 */
public final class ConfigLister {

    public static final int SECTION = 0;
    public static final int DEPOT = 1;
    public static final int ITEM = 2;

    /** 已展开的多仓配置 id（进程内记忆；首次构建时默认展开持有当前源的仓库） */
    private static final Set<Integer> expanded = new LinkedHashSet<>();
    private static boolean expandedInitialized;

    public static class Row {

        public int type;
        public Config config;
        public String text;
        public int childCount;
        public boolean active;
        public boolean indent;
        public boolean expanded;
    }

    private ConfigLister() {
    }

    public static int childCount(Config depot) {
        if (depot == null || !depot.isDepot()) return 0;
        return AppDatabase.get().getConfigDepotDao().findByConfig(depot.getId()).size();
    }

    public static void toggle(int configId) {
        if (!expanded.remove(configId)) expanded.add(configId);
    }

    public static List<Row> build(int type) {
        List<Config> all = Config.getAll(type);
        Config active = ConfigRepository.getActive(type);
        String activeUrl = active == null ? null : active.getUrl();
        Map<Integer, List<Config>> children = new HashMap<>();
        Map<String, Boolean> childUrls = new HashMap<>();
        for (Config depot : all) {
            if (!depot.isDepot()) continue;
            List<Config> items = new ArrayList<>();
            for (ConfigDepot relation : AppDatabase.get().getConfigDepotDao().findByConfig(depot.getId())) {
                for (Config config : all) {
                    if (config.getUrl().equals(relation.getUrl())) {
                        items.add(config);
                        childUrls.put(relation.getUrl(), true);
                        break;
                    }
                }
            }
            children.put(depot.getId(), items);
        }
        ensureExpandedInitialized(all, children, activeUrl);
        List<Config> depots = all.stream().filter(Config::isDepot).toList();
        List<Config> singles = all.stream().filter(config -> !config.isDepot() && !childUrls.containsKey(config.getUrl())).toList();
        List<Row> rows = new ArrayList<>();
        if (!depots.isEmpty()) {
            rows.add(section(App.get().getString(R.string.config_section_depot)));
            for (Config depot : depots) {
                List<Config> items = children.getOrDefault(depot.getId(), new ArrayList<>());
                Row row = row(DEPOT, depot, activeUrl, items.size());
                row.expanded = expanded.contains(depot.getId());
                rows.add(row);
                if (!row.expanded) continue;
                for (Config child : items) rows.add(row(ITEM, child, activeUrl, 0, true));
            }
        }
        if (!singles.isEmpty()) {
            rows.add(section(App.get().getString(R.string.config_section_single)));
            for (Config single : singles) rows.add(row(ITEM, single, activeUrl, 0, false));
        }
        return rows;
    }

    /** 首次构建：默认展开持有当前激活源的仓库，其余折叠；用户手动折叠/展开后保持 */
    private static void ensureExpandedInitialized(List<Config> all, Map<Integer, List<Config>> children, String activeUrl) {
        if (expandedInitialized || activeUrl == null) return;
        expandedInitialized = true;
        for (Config depot : all) {
            if (!depot.isDepot()) continue;
            for (Config child : children.getOrDefault(depot.getId(), new ArrayList<>())) {
                if (activeUrl.equals(child.getUrl())) {
                    expanded.add(depot.getId());
                    return;
                }
            }
        }
    }

    private static Row section(String text) {
        Row row = new Row();
        row.type = SECTION;
        row.text = text;
        return row;
    }

    private static Row row(int type, Config config, String activeUrl, int childCount) {
        return row(type, config, activeUrl, childCount, false);
    }

    private static Row row(int type, Config config, String activeUrl, int childCount, boolean indent) {
        Row row = new Row();
        row.type = type;
        row.config = config;
        row.text = text(config, activeUrl, childCount);
        row.childCount = childCount;
        row.active = activeUrl != null && activeUrl.equals(config.getUrl());
        row.indent = indent;
        return row;
    }

    private static String text(Config config, String activeUrl, int childCount) {
        boolean active = activeUrl != null && activeUrl.equals(config.getUrl());
        StringBuilder builder = new StringBuilder(config.getDesc());
        if (config.isDepot()) {
            builder.append(" · ").append(App.get().getString(R.string.config_badge_depot, childCount));
        } else if (active) {
            builder.append(" · ").append(App.get().getString(R.string.config_badge_current));
        } else if (config.getTime() > 0) {
            builder.append(" · ").append(new SimpleDateFormat("MM-dd", Locale.getDefault()).format(new Date(config.getTime())));
        }
        return builder.toString();
    }
}
