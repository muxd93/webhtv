package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ConfigDepot;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * 多仓展开器：把多仓入口展开为叶子子仓的配置 JSON。
 *
 * - {@link #leaf} 单叶子（同步）：两段式加载的第一段用，优先当前子仓（通常命中缓存零请求）；
 * - {@link #expand} 全量（并行）：第二段后台补全用；
 * - 子仓本身又是多仓（urls）时递归展开（深度上限 3），并升级为 DEPOT 行、登记子关系；
 * - 拉取失败有缓存用缓存，无缓存跳过（不影响其他子仓）；
 * - L1 缓存：子仓 Config 行的 json 列，TTL 24 小时；只缓存有效正文，历史遗留的无效正文读取侧当未命中重拉；
 * - 负缓存：连续失败的子仓在 5 分钟→1 小时阶梯窗口内直接跳过请求（只复用有效旧正文），成功即清除。
 */
public final class DepotExpander {

    private static final String TAG = DepotExpander.class.getSimpleName();

    private static final int MAX_DEPTH = 3;

    private static final long TTL = 24 * 60 * 60 * 1000L;

    /** 子仓失败负缓存键前缀，值为 {@link DepotBackoff} 状态串 */
    private static final String RETRY_KEY = "depot-retry-";

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
        forEachLayer(relations, relation -> fetch(depot, relation, depth, result));
    }

    /**
     * 一层子关系：池外调用时提交到配置专用池并行拉取（不占用共享池）；已在配置池线程内则就地顺序执行。
     * <p>
     * 关键约束：池内任务绝不等待池内任务。嵌套多仓的展开发生在其父任务所在的池线程上，
     * 若再提交同池并阻塞，线程数（3）小于嵌套宽度时所有线程都会卡在等待各自子任务上；
     * 改为就地执行后，子任务只需要当前线程，因此池线程数小于嵌套层数也不会饿死。
     */
    static <T> void forEachLayer(List<T> items, Consumer<T> task) {
        if (items == null || items.isEmpty()) return;
        if (items.size() == 1 || Task.isConfigThread()) {
            for (T item : items) task.accept(item);
            return;
        }
        List<Future<?>> futures = new ArrayList<>(items.size());
        for (T item : items) futures.add(Task.config().submit(() -> task.accept(item)));
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                // 调用方被取消（如配置切换）：恢复中断标志并停止等待，已提交的子任务各自跑完，不影响下一次展开
                Thread.currentThread().interrupt();
                return;
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

    /** 缓存 + 负缓存感知拉取：新鲜且有效的缓存直接用；负缓存窗口内不发请求；无效正文不落库；历史坏正文读取侧重拉 */
    private static String json(Config child) {
        long now = System.currentTimeMillis();
        String url = child.getUrl();
        DepotBody.Outcome outcome = DepotBody.resolve(child.getJson(), now - child.getTime() <= TTL, now, state(url), () -> Decoder.getJson(UrlUtil.convert(url), TAG), DepotExpander::isObject);
        if (outcome.store != null) {
            child.setJson(outcome.store);
            child.setTime(now);
            ConfigRepository.upsert(child);
            clearState(url);
        } else if (outcome.fetched) {
            record(url, now, outcome.stage);
        }
        if (outcome.json.isEmpty()) SpiderDebug.log(TAG, "depot child skip url=%s blocked=%s stage=%s", url, outcome.blocked, outcome.stage);
        return outcome.json;
    }

    private static String state(String url) {
        try {
            return Prefers.getString(RETRY_KEY + Util.md5(url));
        } catch (Throwable e) {
            return "";
        }
    }

    /** 失败一次：窗口按阶梯放大，跨重启保留（避免每次冷启动都重拉坏子仓） */
    private static void record(String url, long now, String stage) {
        try {
            DepotBackoff.State next = DepotBackoff.fail(DepotBackoff.parse(state(url)), now, stage);
            Prefers.put(RETRY_KEY + Util.md5(url), next.encode());
            SpiderDebug.log(TAG, "depot child failed url=%s stage=%s attempts=%s retry_in=%ss", url, stage, next.attempts, (next.until - now) / 1000);
        } catch (Throwable ignored) {
        }
    }

    private static void clearState(String url) {
        try {
            Prefers.remove(RETRY_KEY + Util.md5(url));
        } catch (Throwable ignored) {
        }
    }

    private static boolean isObject(String json) {
        return Json.isObj(json);
    }
}
