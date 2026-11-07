package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ConfigDepot;
import com.fongmi.android.tv.db.AppDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
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
        // 轻量投影：列表只用 url/name/type/time/kind/active，不把每行 json 正文读进内存
        List<Config> all = AppDatabase.get().getConfigDao().findUrlByType(type);
        Config active = ConfigRepository.getActive(type);
        String activeUrl = active == null ? null : active.getUrl();
        Set<String> childUrls = new LinkedHashSet<>();
        Map<Integer, List<Config>> children = group(all, AppDatabase.get().getConfigDepotDao().findAll(), childUrls);
        ensureExpandedInitialized(all, children, activeUrl);
        List<Config> depots = all.stream().filter(Config::isDepot).toList();
        List<Config> singles = all.stream().filter(config -> !config.isDepot() && !childUrls.contains(config.getUrl())).toList();
        List<Row> rows = new ArrayList<>();
        if (!depots.isEmpty()) {
            rows.add(section(App.get().getString(R.string.config_section_depot)));
            for (Config depot : depots) {
                List<Config> items = children.getOrDefault(depot.getId(), new ArrayList<>());
                Row row = row(DEPOT, depot, activeUrl, items.size(), false, healthUrls(depot, items));
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

    /** 子关系一次全表取回后在内存分组，避免「每个多仓查一次关系、每条关系再扫全表」的 O(N^2) 匹配 */
    static Map<Integer, List<Config>> group(List<Config> rows, List<ConfigDepot> relations, Set<String> childUrls) {
        Map<String, Config> byUrl = new HashMap<>();
        for (Config row : rows) byUrl.put(row.getUrl(), row);
        Map<Integer, List<Config>> children = new HashMap<>();
        for (ConfigDepot relation : relations) {
            Config child = byUrl.get(relation.getUrl());
            if (child == null) continue;
            children.computeIfAbsent(relation.getConfigId(), id -> new ArrayList<>()).add(child);
            childUrls.add(relation.getUrl());
        }
        return children;
    }

    /**
     * 回查整行：列表行是轻量投影（不含 json/etag 等正文列），直接拿去加载会丢掉已缓存正文，
     * 比如弱网下先渲染缓存的能力，也给 upsert 留下整行覆盖的隐患；因此真正切换/刷新某个源前按 url+type 取回完整行。
     */
    public static Config hydrate(Config row) {
        if (row == null || row.isEmpty()) return row;
        Config full = AppDatabase.get().getConfigDao().find(row.getUrl(), row.getType());
        return full == null ? row : full;
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

    /** 多仓行的健康度覆盖本体与其全部子源（折叠状态下也能看到「里面有几个子源在失败」） */
    private static List<String> healthUrls(Config depot, List<Config> children) {
        List<String> urls = new ArrayList<>(children.size() + 1);
        urls.add(depot.getUrl());
        for (Config child : children) urls.add(child.getUrl());
        return urls;
    }

    private static Row row(int type, Config config, String activeUrl, int childCount, boolean indent) {
        return row(type, config, activeUrl, childCount, indent, Collections.singletonList(config.getUrl()));
    }

    private static Row row(int type, Config config, String activeUrl, int childCount, boolean indent, List<String> healthUrls) {
        Row row = new Row();
        row.type = type;
        row.config = config;
        row.text = text(config, activeUrl, childCount) + SourceHealth.text(healthUrls);
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
