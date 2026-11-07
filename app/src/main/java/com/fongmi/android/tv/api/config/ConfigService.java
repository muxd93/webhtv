package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ConfigDepot;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.UrlUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 配置服务：多仓登记、删除策略（历史与收藏不随源删除而销毁）。
 */
public final class ConfigService {

    private ConfigService() {
    }

    /** 导入多仓：登记子配置与关系，返回子配置列表（保持 urls 顺序；过滤 null 与自引用） */
    public static List<Config> registerDepot(Config depot, List<Depot> items) {
        List<Config> children = new ArrayList<>();
        List<ConfigDepot> relations = new ArrayList<>();
        String self = ConfigRepository.canonicalize(depot.getUrl());
        int ord = 0;
        for (Depot item : items) {
            if (item == null || TextUtils.isEmpty(item.getUrl())) continue;
            String url = ConfigRepository.canonicalize(item.getUrl());
            if (url.equals(self)) continue;
            // 复用已落库的子仓行：只更新名称/类型标记，保留 json/time/active/home/parse/logo 等字段
            Config child = ConfigRepository.upsert(DepotRow.resolve(ConfigRepository.findByUrl(url, depot.getType()), url, depot.getType(), item.getName(), Config.SINGLE));
            children.add(child);
            relations.add(new ConfigDepot().configId(depot.getId()).url(url).name(item.getName()).ord(ord++));
        }
        if (children.isEmpty()) throw new IllegalStateException("Depot urls is empty");
        AppDatabase.get().getConfigDepotDao().deleteByConfig(depot.getId());
        AppDatabase.get().getConfigDepotDao().insertOrUpdate(relations);
        return children;
    }

    /** 删除配置：多仓级联删子配置与关系；Site/Live 按 sourceId 级联清理。History/Keep/Track 一律保留 */
    public static void delete(Config config) {
        if (config == null || config.isEmpty()) return;
        if (config.isDepot()) {
            for (ConfigDepot relation : AppDatabase.get().getConfigDepotDao().findByConfig(config.getId())) {
                Config child = ConfigRepository.findByUrl(relation.getUrl(), config.getType());
                if (child != null && !child.isEmpty()) deleteOne(child);
            }
            AppDatabase.get().getConfigDepotDao().deleteByConfig(config.getId());
        }
        deleteOne(config);
    }

    private static void deleteOne(Config config) {
        AppDatabase.get().getSiteDao().deleteBySource(config.getId());
        AppDatabase.get().getLiveDao().deleteBySource(config.getId());
        AppDatabase.get().getConfigDao().delete(config.getUrl(), config.getType());
    }

    /**
     * 一次性清理历史脏数据：预设回填连按时名称「张冠李戴」——
     * 某源的名字等于同类型另一个源的 URL 文件名，却不等于自己 URL 的文件名，
     * 改回自己 URL 的文件名。只修正可证实的泄漏；用户手输的相同名字不动。
     *
     * @return 修正的源数量
     */
    public static int fixLeakedNames() {
        int fixed = 0;
        List<Config> all = AppDatabase.get().getConfigDao().findAll();
        for (int t = 0; t <= 2; t++) {
            final int type = t;
            List<Config> items = all.stream().filter(config -> config.getType() == type).toList();
            Set<String> foreign = items.stream().map(config -> fileName(config.getUrl())).collect(Collectors.toSet());
            for (Config config : items) {
                String name = config.getName();
                String own = fileName(config.getUrl());
                if (name.isEmpty() || name.equals(own) || !foreign.contains(name)) continue;
                config.setName(own);
                ConfigRepository.upsert(config);
                fixed++;
            }
        }
        return fixed;
    }

    private static String fileName(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String path = UrlUtil.path(url);
        return path.isEmpty() ? UrlUtil.host(url) : path;
    }
}
