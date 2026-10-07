package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.source.SourceState;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Init;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Path;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 多仓聚合池（DEPOT3）：导入多仓时收集全部子仓的直播条目与广告规则。
 * 语义（用户 2026-10-07 裁定）：只增不减——仓更新/子仓移除不回收贡献，永不自动删除；
 * 手动删除（墓碑）/禁用按条目记忆，重导入与重启不复活；同名直播条目改名共存
 * （渲染期追加"（子仓名）"，二次撞名追加序号）；广告规则跨仓按规则串唯一去重。
 * 条目级收集，不做频道级合并（SYS2 否定的错误模式不适用）；spider 仅作 jar 提示
 * 存字符串，导入阶段零 jar 下载（JarLoader 选中时懒加载）。
 * 核心逻辑零 Android 依赖（镜像 LineHealth 纪律）：androidAvailable 探测跳过持久化，
 * JVM 单测直接驱动静态方法；网络收集路径（collectOne/fetch）不参与单测。
 */
public final class DepotPool {

    public static final int NORMAL = 0;
    public static final int DISABLED = 1;
    public static final int DELETED = 2;

    static final String SEP = "\u0001";
    public static final char DOM_LIVE = 'L';
    public static final char DOM_AD = 'A';
    /** 广告规则状态键与来源仓无关：同一规则跨仓唯一、一行一状态。 */
    private static final String TAG = "depot_pool";
    private static final int FETCH_CONCURRENCY = 4;
    private static final long FETCH_TIMEOUT_MS = 10_000;
    /** 收集总预算（对齐 SYS1 聚合纪律）：超时未完成的子源放弃，下次仓展开补拉。 */
    private static final long COLLECT_BUDGET_MS = 240_000;
    private static final long SAVE_DELAY = 3L;

    private static final LinkedHashMap<String, Contribution> POOL = new LinkedHashMap<>();
    private static final Map<String, Integer> STATES = new HashMap<>();
    private static final AtomicBoolean COLLECTING = new AtomicBoolean();
    private static final AtomicBoolean SAVING = new AtomicBoolean();
    private static volatile boolean loaded;

    private DepotPool() {
    }

    /** 条目：直播键 = 来源仓URL + 原始名（跨仓同名是不同源）；广告键 = 规则串本身。 */
    static String key(String url, char dom, String orig) {
        return url + SEP + dom + SEP + orig;
    }

    static String adKey(String rule) {
        return SEP + DOM_AD + SEP + rule;
    }

    /**
     * 改名共存（纯函数供单测）：原名未占用即用原名；重名追加"（子仓名）"；
     * 再撞名追加序号。占用集合由调用方维护（含当前列表与已渲染池条目）。
     */
    static String resolveName(Set<String> used, String orig, String childName) {
        String owner = childName == null || childName.isEmpty() ? "仓" : childName;
        String base = orig == null || orig.isEmpty() ? "直播" : orig;
        if (!used.contains(base)) return base;
        String candidate = base + "（" + owner + "）";
        if (!used.contains(candidate)) return candidate;
        for (int i = 2; ; i++) {
            candidate = base + "（" + owner + " " + i + "）";
            if (!used.contains(candidate)) return candidate;
        }
    }

    public static synchronized int stateOf(String url, char dom, String orig) {
        Integer state = STATES.get(dom == DOM_AD ? adKey(orig) : key(url, dom, orig));
        return state == null ? NORMAL : state;
    }

    private static int stateOfLocked(String url, char dom, String orig) {
        return stateOf(url, dom, orig);
    }

    /** 设置条目状态；NORMAL 即清除记忆。墓碑（DELETED）在后续 upsert 中保持不复活。 */
    public static synchronized void setState(String url, char dom, String orig, int state) {
        String k = dom == DOM_AD ? adKey(orig) : key(url, dom, orig);
        if (state == NORMAL) STATES.remove(k);
        else STATES.put(k, state);
        scheduleSave();
    }

    /** 恢复默认：全部状态清零（含墓碑），贡献保留。 */
    public static synchronized void resetStates() {
        if (STATES.isEmpty()) return;
        STATES.clear();
        save();
    }

    /** 清空聚合池：贡献与状态全清（管理界面兜底操作）。 */
    public static synchronized void clear() {
        if (POOL.isEmpty() && STATES.isEmpty()) return;
        POOL.clear();
        STATES.clear();
        save();
    }

    /**
     * 收集落库（纯逻辑供单测）：新条目按声明序追加、既有条目按内容刷新且保位；
     * 子仓新版本已删除的条目保留（只增不减）。返回池内容是否有变化。
     */
    public static synchronized boolean upsert(String url, String name, String spider, List<JsonObject> lives, List<String> ads) {
        if (url == null || url.isEmpty()) return false;
        Contribution c = POOL.get(url);
        boolean changed = c == null;
        if (c == null) {
            c = new Contribution();
            c.url = url;
            POOL.put(url, c);
        }
        if (name != null && !name.isEmpty() && !name.equals(c.name)) {
            c.name = name;
            changed = true;
        }
        if (spider != null && !spider.isEmpty() && !spider.equals(c.spider)) {
            c.spider = spider;
            changed = true;
        }
        c.ts = System.currentTimeMillis();
        if (lives != null) for (JsonObject live : lives) {
            String orig = nameOf(live);
            if (orig.isEmpty()) continue;
            String raw = live.toString();
            if (!raw.equals(c.lives.get(orig))) {
                c.lives.put(orig, raw);
                changed = true;
            }
        }
        if (ads != null) for (String ad : ads) {
            String rule = ad == null ? "" : ad.trim();
            if (!rule.isEmpty() && c.ads.add(rule)) changed = true;
        }
        return changed;
    }

    private static String nameOf(JsonObject live) {
        try {
            String name = live.has("name") && live.get("name").isJsonPrimitive() ? live.get("name").getAsString() : "";
            return name == null ? "" : name.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 渲染/管理共用条目视图。 */
    public record PoolLive(String url, String childName, String origName, String raw, String spider, int state) {
    }

    public record AdItem(String rule, int state) {
    }

    /**
     * 直播条目视图：按池插入序（=改名稳定序）；excludeUrl 供"当前配置来源不重复渲染"；
     * enabledOnly 剔除被禁用/已删条目。
     */
    public static synchronized List<PoolLive> lives(String excludeUrl, boolean enabledOnly) {
        List<PoolLive> out = new ArrayList<>();
        for (Contribution c : POOL.values()) {
            if (excludeUrl != null && excludeUrl.equals(c.url)) continue;
            for (Map.Entry<String, String> e : c.lives.entrySet()) {
                int state = stateOfLocked(c.url, DOM_LIVE, e.getKey());
                if (enabledOnly && state != NORMAL) continue;
                out.add(new PoolLive(c.url, c.name == null ? "" : c.name, e.getKey(), e.getValue(), c.spider == null ? "" : c.spider, state));
            }
        }
        return out;
    }

    /** 广告条目视图（管理界面用）：跨仓按规则串归一，保持首见顺序。 */
    public static synchronized List<AdItem> ads(boolean enabledOnly) {
        Map<String, AdItem> out = new LinkedHashMap<>();
        for (Contribution c : POOL.values()) {
            for (String ad : c.ads) {
                if (out.containsKey(ad)) continue;
                int state = stateOfLocked(null, DOM_AD, ad);
                if (enabledOnly && state != NORMAL) continue;
                out.put(ad, new AdItem(ad, state));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** 广告规则视图（RuleConfig 合并用）：已去重、按序。 */
    public static List<String> adsView() {
        List<String> out = new ArrayList<>();
        for (AdItem item : ads(true)) out.add(item.rule());
        return out;
    }

    public static int liveCount() {
        return lives(null, true).size();
    }

    public static int adCount() {
        return ads(true).size();
    }

    /** 收集子源元数据（type 用于跳过用户已禁用的接口）。 */
    public record Child(String url, String name, int type) {
    }

    public interface CollectCallback {

        /** 单个子仓内容有更新（任意线程回调，调用方自行切主线程）。 */
        void onChildChanged();

        /** 全部子仓处理完毕：done=成功解析数，total=子源总数。 */
        void onDone(int done, int total);
    }

    /** 单飞闸门：进行中的收集期间新请求直接跳过（下次仓展开自然补拉）。 */
    public static void collectAsync(List<Child> children, CollectCallback cb) {
        if (children == null || children.isEmpty() || !COLLECTING.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                runCollect(children, cb);
            } finally {
                COLLECTING.set(false);
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private static void runCollect(List<Child> items, CollectCallback cb) {
        try {
            CountDownLatch latch = new CountDownLatch(items.size());
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(FETCH_CONCURRENCY, items.size()));
            AtomicInteger done = new AtomicInteger();
            for (Child child : items) pool.execute(() -> {
                try {
                    if (collectOne(child)) {
                        done.incrementAndGet();
                        if (cb != null) cb.onChildChanged();
                    }
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
            latch.await(COLLECT_BUDGET_MS, TimeUnit.MILLISECONDS);
            pool.shutdownNow();
            if (cb != null) cb.onDone(done.get(), items.size());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 单个子仓：缓存优先、网络回源（回源成功回写 ConfigCache，后续零网络）；
     * 嵌套仓（含 urls）/非 JSON/无 lives 且无 ads 的内容不产生贡献。
     */
    private static boolean collectOne(Child child) {
        if (child == null || child.url() == null || !child.url().startsWith("http")) return false;
        if (SourceState.isDisabled(child.type(), child.url())) return false;
        String json = ConfigCache.get(child.url());
        if (json == null) {
            json = fetch(child.url());
            if (json == null) return false;
            ConfigCache.put(child.url(), json);
        }
        JsonElement element = Json.parse(json);
        if (!element.isJsonObject()) return false;
        JsonObject object = element.getAsJsonObject();
        if (object.has("urls")) return false;
        String spider = Json.safeString(object, "spider");
        List<JsonObject> lives = new ArrayList<>();
        for (JsonElement e : Json.safeListElement(object, "lives")) if (e.isJsonObject()) lives.add(e.getAsJsonObject());
        List<String> ads = Json.safeListString(object, "ads");
        if (lives.isEmpty() && ads.isEmpty()) return false;
        upsert(child.url(), child.name(), spider, lives, ads);
        return true;
    }

    private static String fetch(String url) {
        try {
            Request request = new Request.Builder().url(url).tag(TAG).build();
            Response response = OkHttp.client(FETCH_TIMEOUT_MS).newCall(request).execute();
            String body = response.body() == null ? null : response.body().string();
            response.close();
            return body == null || body.isEmpty() ? null : body;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 设备环境探测：JVM 单测下 Init.context() 抛 NPE（无 Android 宿主），据此跳过持久化。 */
    private static boolean androidAvailable() {
        try {
            return Init.context() != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void scheduleSave() {
        if (!androidAvailable() || !SAVING.compareAndSet(false, true)) return;
        Task.schedule(() -> {
            SAVING.set(false);
            save();
        }, SAVE_DELAY, TimeUnit.SECONDS);
    }

    private static File file() {
        File base = new File(Init.context().getFilesDir(), "live");
        if (!base.exists()) base.mkdirs();
        return new File(base, "depot_pool.json");
    }

    private static synchronized void load() {
        if (loaded || !androidAvailable()) return;
        loaded = true;
        try {
            String json = Path.read(file());
            if (json == null || json.isEmpty()) return;
            FileDTO dto = new Gson().fromJson(json, FileDTO.class);
            if (dto == null) return;
            if (dto.contributions != null) for (Contribution c : dto.contributions) {
                if (c != null && c.url != null && !c.url.isEmpty() && !POOL.containsKey(c.url)) POOL.put(c.url, c);
            }
            if (dto.states != null) STATES.putAll(dto.states);
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void save() {
        try {
            FileDTO dto = new FileDTO();
            dto.contributions = new ArrayList<>(POOL.values());
            dto.states = new HashMap<>(STATES);
            File file = file();
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(new Gson().toJson(dto).getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Throwable ignored) {
        }
    }

    private static final class FileDTO {
        List<Contribution> contributions;
        Map<String, Integer> states;
    }

    private static final class Contribution {
        String url;
        String name;
        String spider;
        long ts;
        LinkedHashMap<String, String> lives = new LinkedHashMap<>();
        LinkedHashSet<String> ads = new LinkedHashSet<>();
    }

    static void resetForTest() {
        POOL.clear();
        STATES.clear();
        loaded = false;
    }
}
