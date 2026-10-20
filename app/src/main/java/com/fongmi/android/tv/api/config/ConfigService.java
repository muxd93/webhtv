package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ConfigDepot;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.db.AppDatabase;

import java.util.ArrayList;
import java.util.List;

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
            Config child = ConfigRepository.upsert(new Config().type(depot.getType()).kind(Config.SINGLE).url(url).name(item.getName()));
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
}
