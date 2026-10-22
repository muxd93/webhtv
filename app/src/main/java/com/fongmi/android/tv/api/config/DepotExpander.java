package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ConfigDepot;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

/**
 * 多仓展开器：把多仓入口展开为叶子子仓的配置 JSON。
 *
 * - {@link #leaf} 单叶子（同步）：两段式加载的第一段用，优先当前子仓（通常命中缓存零请求）；
 * - {@link #expand} 全量（并行）：第二段后台补全用；
 * - 子仓本身又是多仓（urls）时递归展开（深度上限 3），并升级为 DEPOT 行、登记子关系；
 * - 拉取失败有缓存用缓存，无缓存跳过（不影响其他子仓）；
 * - L1 缓存：子仓 Config 行的 json 列，TTL 6 小时。
 */
public final class DepotExpander {

    private static final String TAG = DepotExpander.class.getSimpleName();

    private static final int MAX_DEPTH = 3;

    private static final long TTL = 6 * 60 * 60 * 1000L;

    /** 叶子子仓：配置行 + 已解析的配置 JSON */
    public static class Leaf {

        public final Config config;
        public final JsonObject json;

        public Leaf(Config config, JsonObject json) {
            this.config = config;
            this.json = json;
        }
    }

    private DepotExpander() {
    }

    /** 展开多仓，返回叶子子仓列表（保持 urls 顺序；失败子仓跳过） */
    public static List<Leaf> expand(Config depot) {
        List<Leaf> result = Collections.synchronizedList(new ArrayList<>());
        expand(depot, 0, result);
        return result;
    }

    /**
     * 取单个叶子子仓（同步）：优先 primary（当前子仓，如点播激活子源），否则第一个子关系。
     * 用于两段式加载第一段——立刻出内容，不等全部子仓。
     */
    public static Leaf leaf(Config depot, Config primary) {
        if (depot == null || depot.isEmpty()) return null;
        List<ConfigDepot> relations = AppDatabase.get().getConfigDepotDao().findByConfig(depot.getId());
        ConfigDepot target = null;
        if (primary != null && !primary.isEmpty()) {
            for (ConfigDepot relation : relations) {
                if (relation.getUrl().equals(primary.getUrl())) {
                    target = relation;
                    break;
                }
            }
        }
        if (target == null && !relations.isEmpty()) target = relations.get(0);
        return target == null ? null : fetch(depot, target, 0, null);
    }

    private static void expand(Config depot, int depth, List<Leaf> result) {
        if (depot == null || depot.isEmpty() || depth > MAX_DEPTH) return;
        List<ConfigDepot> relations = AppDatabase.get().getConfigDepotDao().findByConfig(depot.getId());
        List<Future<?>> futures = new ArrayList<>();
        for (ConfigDepot relation : relations) futures.add(Task.submit(() -> fetch(depot, relation, depth, result)));
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ignored) {
            }
        }
    }

    /** 拉取单个子关系；result 非空时按全量语义（嵌套递归展开），为空时返回单叶子（嵌套取首个叶子） */
    private static Leaf fetch(Config depot, ConfigDepot relation, int depth, List<Leaf> result) {
        if (relation == null || TextUtils.isEmpty(relation.getUrl()) || depth > MAX_DEPTH) return null;
        Config child = ConfigRepository.findByUrl(relation.getUrl(), depot.getType());
        if (child.isEmpty()) return null;
        String json = json(child);
        if (TextUtils.isEmpty(json) || !Json.isObj(json)) return null;
        JsonObject object = Json.parse(json).getAsJsonObject();
        if (!object.has("urls")) {
            if (result != null) result.add(new Leaf(child, object));
            return new Leaf(child, object);
        }
        if (depth >= MAX_DEPTH) return null;
        try {
            Config nested = ConfigRepository.upsert(child.depot());
            ConfigService.registerDepot(nested, Depot.arrayFrom(object.getAsJsonArray("urls").toString()));
            if (result != null) {
                expand(nested, depth + 1, result);
                return null;
            }
            for (ConfigDepot sub : AppDatabase.get().getConfigDepotDao().findByConfig(nested.getId())) {
                Leaf leaf = fetch(nested, sub, depth + 1, null);
                if (leaf != null) return leaf;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 缓存感知拉取：新鲜缓存直接用，否则网络拉取并落库（TTL 6 小时） */
    private static String json(Config child) {
        String json = child.getJson();
        if (TextUtils.isEmpty(json) || System.currentTimeMillis() - child.getTime() > TTL) {
            try {
                json = Decoder.getJson(UrlUtil.convert(child.getUrl()), TAG);
                child.setJson(json);
                child.setTime(System.currentTimeMillis());
                ConfigRepository.upsert(child);
            } catch (Throwable e) {
                if (TextUtils.isEmpty(json)) return "";
            }
        }
        return json;
    }
}
