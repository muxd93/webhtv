package com.fongmi.android.tv.live;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.ConfigCache;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Trans;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多仓聚合：把源池内各订阅源（txt/m3u/分组 JSON/嵌套配置对象）的频道按归一名合并成一份直播数据，
 * 落盘 filesDir/live/aggregate.json 并注册为 file:// 直播配置（type=1）。
 * 源池定义存 aggregate.meta.json；随仓入池的源同步建 type=1 Config 行（pool() 以配置行为准）。
 * 可用性不做预探测：依赖播放期自动换线（LIVE7 删除 LiveProbe/隔离区/state 文件）。
 */
public class LiveAggregator {

    private static final String NAME = "聚合";
    private static final String KEY_FETCH_TS = "live_fetch_ts_";
    /** 聚合完成时间戳（Prefers）：进直播页的 staleness 判定只读它，避免主线程解析大 meta。 */
    private static final String KEY_AGG_TS = "live_agg_ts_";
    /** 手动优先标记（Prefers）：用户在会话里手动选过非聚合配置后，自动聚合不再抢切。 */
    private static final String KEY_MANUAL = "live_manual";
    private static final long FETCH_TIMEOUT = 20000;
    private static final Gson GSON = new Gson();
    /** 聚合全程串行化：入口多（随仓/刷新/源池 UI/删源联动），防止并发互覆 meta 与产物。 */
    private static final Object LOCK = new Object();
    // 频道名归一时剔除的分隔符；刻意保留 +（CCTV5+）
    private static final String SEPARATORS = "-—–_·•.。:：,，、;；!！?？（）()[]【】「」『』《》<>｜|/／\\";
    private static final String[] THEMES = {"体育", "竞技", "赛事", "电影", "影院", "剧场", "纪录", "纪实", "少儿", "卡通", "动漫", "动画", "教育", "新闻", "资讯", "音乐", "财经", "生活", "科技", "文艺", "都市", "法治", "法制", "港澳", "国际", "海外", "戏曲", "旅游", "汽车", "购物", "广播", "电台"};

    // ---------- 归一化（纯函数，无 Android 依赖，供单测） ----------

    static String normalizeChannel(String name) {
        if (name == null) return "";
        // t2s(false, …) 强制繁→简，不受 locale 门控，保证归一键确定
        String text = halfwidth(Trans.t2s(false, name)).toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSpace(c) || SEPARATORS.indexOf(c) >= 0) continue;
            out.append(c);
        }
        return out.toString();
    }

    static String normalizeGroup(String name) {
        if (name == null) return "";
        String text = stripSpace(halfwidth(Trans.t2s(false, name)));
        String upper = text.toUpperCase(Locale.ROOT);
        if (text.contains("央视") || upper.contains("CCTV") || upper.contains("CGTN")) return "央视";
        if (text.contains("卫视")) return "卫视";
        return text;
    }

    /** 分组固定顺序：央视→卫视→地方→专题→其他；隐藏组由排序强制置尾。 */
    static int order(String canonical) {
        if ("央视".equals(canonical)) return 0;
        if ("卫视".equals(canonical)) return 1;
        if (canonical.contains("地方") || canonical.contains("省市")) return 2;
        for (String theme : THEMES) if (canonical.contains(theme)) return 3;
        return 4;
    }

    private static String halfwidth(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            else if (c == 0x3000) c = ' ';
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || c == 0x00A0;
    }

    private static String stripSpace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) if (!isSpace(text.charAt(i))) out.append(text.charAt(i));
        return out.toString();
    }

    // ---------- 源池与聚合配置 ----------

    /** 聚合配置的 file:// 地址（文件由本类生成，经本机 Server /file/ 路由读取）。 */
    public static String url() {
        return "file://" + file().getAbsolutePath();
    }

    public static boolean isAggregate(Config config) {
        return config != null && url().equals(config.getUrl());
    }

    /** 查找或创建「聚合」配置项并落库。 */
    public static Config ensureConfig() {
        return Config.find(Config.create(1).url(url()).name(NAME), 1);
    }

    private static File dir() {
        File dir = new File(Path.files(), "live");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File file() {
        return new File(dir(), "aggregate.json");
    }

    private static File metaFile() {
        return new File(dir(), "aggregate.meta.json");
    }

    /** 用新一批订阅源覆盖源池定义（仅 URL 列表变化时落盘重置状态）。返回池定义是否变化。 */
    public static boolean savePool(List<Config> configs) {
        List<String> urls = new ArrayList<>();
        for (Config config : configs) if (!TextUtils.isEmpty(config.getUrl())) urls.add(config.getUrl());
        JsonObject meta = readMeta();
        List<String> oldUrls = new ArrayList<>();
        Map<String, JsonObject> old = new LinkedHashMap<>();
        if (meta.has("sources")) {
            for (JsonElement e : meta.getAsJsonArray("sources")) {
                JsonObject item = e.getAsJsonObject();
                String url = item.get("url").getAsString();
                oldUrls.add(url);
                old.put(url, item);
            }
        }
        // 集合比较：同批源仅顺序不同（用户在源池 UI 调过序）视为未变，不覆盖本地顺序
        if (oldUrls.size() == urls.size() && new HashSet<>(oldUrls).equals(new HashSet<>(urls))) return false;
        JsonArray sources = new JsonArray();
        int order = 0;
        for (Config config : configs) {
            if (TextUtils.isEmpty(config.getUrl())) continue;
            JsonObject prev = old.get(config.getUrl());
            JsonObject item = new JsonObject();
            item.addProperty("url", config.getUrl());
            item.addProperty("name", config.getName());
            item.addProperty("order", order++);
            // 复用既有源状态（启停/时间戳），避免重复导入重置一切
            item.addProperty("ok", prev != null && prev.has("ok") ? prev.get("ok").getAsBoolean() : true);
            item.addProperty("ts", prev != null && prev.has("ts") ? prev.get("ts").getAsLong() : 0);
            if (prev != null && prev.has("chan")) item.addProperty("chan", prev.get("chan").getAsInt());
            item.addProperty("enabled", prev == null || !prev.has("enabled") || prev.get("enabled").getAsBoolean());
            sources.add(item);
        }
        meta.add("sources", sources);
        writeMeta(meta);
        Prefers.put(KEY_AGG_TS, 0);
        return true;
    }

    private static List<Config> pool() {
        List<Config> items = new ArrayList<>();
        JsonObject meta = readMeta();
        if (!meta.has("sources")) return items;
        for (JsonElement e : meta.getAsJsonArray("sources")) {
            String url = e.getAsJsonObject().get("url").getAsString();
            Config config = AppDatabase.get().getConfigDao().find(url, 1);
            // 历史中已删除的源自动出池
            if (config != null) items.add(config);
        }
        return items;
    }

    // ---------- 聚合 ----------

    /** 同步聚合（须在后台线程调用）：拉取解析各源、归一合并、落盘。返回聚合文件内容是否变化。 */
    public static boolean aggregate() {
        return aggregate(false);
    }

    /** notify=true 用于导入等用户显式等待的路径（Toast 反馈进度）；后台静默刷新传 false。 */
    public static boolean aggregate(boolean notify) {
        synchronized (LOCK) {
            return aggregateLocked(notify);
        }
    }

    private static boolean aggregateLocked(boolean notify) {
        List<Config> pool = pool();
        if (pool.isEmpty()) return false;
        // 清理历史崩溃遗留的孤儿临时文件（1 小时以上，避开在途写入）
        File[] stale = dir().listFiles((d, name) -> name.endsWith(".tmp") && System.currentTimeMillis() - new File(d, name).lastModified() > 3600_000L);
        if (stale != null) for (File item : stale) item.delete();
        if (notify) App.post(() -> Notify.show(ResUtil.getString(R.string.live_agg_start, pool.size())));
        JsonObject meta = readMeta();
        JsonArray sources = meta.getAsJsonArray("sources");
        List<Live> parsed = fetchPool(pool, sources);
        if (parsed.isEmpty()) {
            if (notify) App.post(() -> Notify.show(R.string.live_agg_failed));
            return false;
        }
        Live merged = merge(parsed);
        int channelCount = 0, lineCount = 0;
        for (Group group : merged.getGroups())
            for (Channel channel : group.getChannel()) {
                channelCount++;
                lineCount += channel.getUrls().size();
            }
        if (notify) {
            int channels = channelCount, linesCount = lineCount;
            App.post(() -> Notify.show(ResUtil.getString(R.string.live_agg_done, channels, linesCount)));
        }
        String json = toJson(merged);
        String previous = file().exists() ? Path.read(file()) : "";
        JsonObject agg = new JsonObject();
        agg.addProperty("ts", System.currentTimeMillis());
        agg.addProperty("channels", channelCount);
        agg.addProperty("lines", lineCount);
        meta.add("agg", agg);
        boolean changed;
        if (json.equals(previous)) {
            writeMeta(meta);
            changed = false;
        } else {
            write(file(), json);
            writeMeta(meta);
            changed = true;
        }
        Prefers.put(KEY_AGG_TS, System.currentTimeMillis());
        return changed;
    }

    private static JsonObject findState(JsonArray sources, String url) {
        for (JsonElement e : sources) {
            JsonObject item = e.getAsJsonObject();
            if (url.equals(item.get("url").getAsString())) return item;
        }
        JsonObject item = new JsonObject();
        item.addProperty("url", url);
        item.addProperty("ok", false);
        item.addProperty("ts", 0);
        sources.add(item);
        return item;
    }

    /** 单源拉取解析：超龄才走网络（成功回写缓存与时间戳），否则用缓存；两者皆空视为失败。 */
    private static Live parse(Config config, JsonObject state) {
        long ts = state.has("ts") ? state.get("ts").getAsLong() : 0;
        boolean stale = ts <= 0 || System.currentTimeMillis() - ts >= staleMs();
        String content = null;
        if (stale) {
            try {
                // 池条目自带 ua/header（随仓 lives 常需要），拉取时随源传递
                content = OkHttp.string(UrlUtil.convert(config.getUrl()), sourceHeaders(state), FETCH_TIMEOUT);
            } catch (Throwable e) {
                content = null;
            }
        }
        if (!TextUtils.isEmpty(content)) {
            ConfigCache.put(config.getUrl(), content);
            state.addProperty("ok", true);
            state.addProperty("ts", System.currentTimeMillis());
            Prefers.put(KEY_FETCH_TS + Util.md5(config.getUrl()), System.currentTimeMillis());
        } else {
            content = ConfigCache.get(config.getUrl());
            state.addProperty("ok", false);
        }
        if (TextUtils.isEmpty(content)) return null;
        Live live = new Live(config.getName(), config.getUrl());
        // 随仓入池的源携带 lives 条目的 epg，参与合并 union
        if (state.has("epg")) live.setEpg(state.get("epg").getAsString());
        try {
            // 分组数组 JSON 直接解析；txt/m3u 交 LiveParser；完整配置对象按 lives[].url 展开解析
            if (Json.isArray(content)) live.getGroups().addAll(Group.arrayFrom(content));
            else if (Json.isObj(content)) expandConfigSource(live, content);
            else LiveParser.text(live, content);
        } catch (Throwable e) {
            return null;
        }
        return live.getGroups().isEmpty() ? null : live;
    }

    /**
     * 池源为完整配置对象（本地包主配置/点播配置）时按 lives[].url 展开拉取解析（深度 1，不再递归）；
     * spider 型子源（无 url）跳过。子源解析失败静默略过，不影响其他子源。
     */
    private static void expandConfigSource(Live live, String content) throws Exception {
        JsonObject object = Json.parse(content).getAsJsonObject();
        if (!object.has("lives")) return;
        String spider = Json.safeString(object, "spider");
        for (JsonElement e : Json.safeListElement(object, "lives")) {
            Live child = Live.objectFrom(e, spider);
            String url = child.getUrl();
            if (child.isEmpty() || TextUtils.isEmpty(url)) continue;
            String text = null;
            try {
                text = OkHttp.string(UrlUtil.convert(url), child.getHeaders(), FETCH_TIMEOUT);
            } catch (Throwable ignored) {
            }
            if (TextUtils.isEmpty(text)) continue;
            Live parsed = new Live(child.getName(), url);
            try {
                if (Json.isArray(text)) parsed.getGroups().addAll(Group.arrayFrom(text));
                else LiveParser.text(parsed, text);
            } catch (Throwable ignored) {
                continue;
            }
            live.getGroups().addAll(parsed.getGroups());
        }
    }

    /** 并发拉取解析各源（结果按池序返回，失败源跳过）；单源 20s 超时语义不变，总耗时从各源之和降为最慢单源。 */
    private static List<Live> fetchPool(List<Config> pool, JsonArray sources) {
        ExecutorService exec = Executors.newFixedThreadPool(Math.min(6, Math.max(1, pool.size())));
        Map<Integer, Live> results = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            Config config = pool.get(i);
            // findState 保持在提交循环内串行执行，避免并发写 sources 数组；state 对象此后仅被本源任务独占修改
            JsonObject state = findState(sources, config.getUrl());
            int index = i;
            exec.submit(() -> {
                try {
                    Live live = parse(config, state);
                    if (live != null) {
                        int chans = 0;
                        for (Group group : live.getGroups()) chans += group.getChannel().size();
                        state.addProperty("chan", chans);
                        results.put(index, live);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exec.shutdownNow();
        }
        List<Live> parsed = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            Live live = results.get(i);
            if (live != null) parsed.add(live);
        }
        return parsed;
    }

    // ---------- 删除联动 ----------

    /** 池内源被删除后的联动：立即出池重聚合（内容变化则静默重载）；池被删空则清盘聚合产物。 */
    public static void onSourceDeleted(String url) {
        if (TextUtils.isEmpty(url)) return;
        Task.submit(() -> {
            try {
                if (!containsSource(url)) return;
                if (pool().isEmpty()) {
                    reset();
                    if (isAggregate(LiveConfig.get().getConfig()))
                        App.post(() -> LiveConfig.load(ensureConfig(), new Callback()));
                    return;
                }
                if (aggregate()) App.post(() -> LiveConfig.get().reloadQuietly());
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
    }

    public static boolean containsSource(String url) {
        JsonObject meta = readMeta();
        if (!meta.has("sources")) return false;
        for (JsonElement e : meta.getAsJsonArray("sources")) {
            if (url.equals(e.getAsJsonObject().get("url").getAsString())) return true;
        }
        return false;
    }

    /** 清空聚合产物：文件、旁车 meta 与对应配置缓存一并移除（仅池被删空时使用）。 */
    public static void reset() {
        file().delete();
        metaFile().delete();
        ConfigCache.delete(url());
        Prefers.put(KEY_AGG_TS, 0);
    }

    // ---------- 源池管理（SUB2，供 UI 调用；只改 meta，聚合由调用方显式触发） ----------

    public static class SourceInfo {

        public final String url;
        public final String name;
        public final boolean ok;
        public final long ts;
        public final boolean enabled;
        public final int channels;

        SourceInfo(String url, String name, boolean ok, long ts, boolean enabled, int channels) {
            this.url = url;
            this.name = name;
            this.ok = ok;
            this.ts = ts;
            this.enabled = enabled;
            this.channels = channels;
        }
    }

    /** 源池快照；channels 为最近一次聚合解析的频道数（旧数据为 -1）。 */
    public static List<SourceInfo> poolStatus() {
        List<SourceInfo> items = new ArrayList<>();
        JsonObject meta = readMeta();
        if (!meta.has("sources")) return items;
        for (JsonElement e : meta.getAsJsonArray("sources")) {
            JsonObject item = e.getAsJsonObject();
            String url = item.has("url") ? item.get("url").getAsString() : "";
            if (TextUtils.isEmpty(url)) continue;
            items.add(new SourceInfo(url,
                    item.has("name") ? item.get("name").getAsString() : "",
                    item.has("ok") && item.get("ok").getAsBoolean(),
                    item.has("ts") ? item.get("ts").getAsLong() : 0,
                    !item.has("enabled") || item.get("enabled").getAsBoolean(),
                    item.has("chan") ? item.get("chan").getAsInt() : -1));
        }
        return items;
    }

    /** 聚合产物信息 {ts,channels,lines}；从未聚合返回 null。 */
    public static JsonObject aggInfo() {
        JsonObject meta = readMeta();
        return meta.has("agg") ? meta.getAsJsonObject("agg") : null;
    }

    /** 新增源入池（追加到末尾，默认启用）；已在池内时忽略。 */
    public static void addSource(Config config) {
        if (config == null || TextUtils.isEmpty(config.getUrl()) || containsSource(config.getUrl())) return;
        JsonObject meta = readMeta();
        if (!meta.has("sources")) meta.add("sources", new JsonArray());
        JsonObject item = new JsonObject();
        item.addProperty("url", config.getUrl());
        item.addProperty("name", config.getName());
        item.addProperty("order", meta.getAsJsonArray("sources").size());
        item.addProperty("ok", true);
        item.addProperty("ts", 0);
        item.addProperty("enabled", true);
        meta.getAsJsonArray("sources").add(item);
        writeMeta(meta);
        Prefers.put(KEY_AGG_TS, 0);
    }

    /** 移出源池（保留历史配置可再加回）；池因此变空时清盘聚合产物。 */
    public static void removeSource(String url) {
        JsonObject meta = readMeta();
        if (!meta.has("sources")) return;
        JsonArray kept = new JsonArray();
        for (JsonElement e : meta.getAsJsonArray("sources")) {
            if (!url.equals(e.getAsJsonObject().get("url").getAsString())) kept.add(e);
        }
        meta.add("sources", kept);
        writeMeta(meta);
        if (kept.size() == 0) reset();
        else Prefers.put(KEY_AGG_TS, 0);
    }

    public static void setEnabled(String url, boolean enabled) {
        JsonObject meta = readMeta();
        if (!meta.has("sources")) return;
        for (JsonElement e : meta.getAsJsonArray("sources")) {
            JsonObject item = e.getAsJsonObject();
            if (url.equals(item.get("url").getAsString())) {
                item.addProperty("enabled", enabled);
                writeMeta(meta);
                return;
            }
        }
    }

    /** 与相邻源交换顺序（delta 正下负上），并重排 order 字段。 */
    public static void move(String url, int delta) {
        JsonObject meta = readMeta();
        if (!meta.has("sources")) return;
        JsonArray sources = meta.getAsJsonArray("sources");
        int index = -1;
        for (int i = 0; i < sources.size(); i++) {
            if (url.equals(sources.get(i).getAsJsonObject().get("url").getAsString())) {
                index = i;
                break;
            }
        }
        int target = index + delta;
        if (index == -1 || target < 0 || target >= sources.size()) return;
        JsonObject item = sources.get(index).getAsJsonObject();
        JsonObject other = sources.get(target).getAsJsonObject();
        sources.set(index, other);
        sources.set(target, item);
        for (int i = 0; i < sources.size(); i++) sources.get(i).getAsJsonObject().addProperty("order", i);
        writeMeta(meta);
    }

    // ---------- 随仓自动聚合（LIVE6/LIVE7） ----------

    /** 池条目构造（纯函数，供单测）：prev 存在时复用状态字段；from/ua/header/epg 仅非空写入。 */
    static JsonObject poolItem(String url, String name, String from, String ua, Map<String, String> header, String epg, int order, JsonObject prev) {
        JsonObject item = new JsonObject();
        item.addProperty("url", url);
        item.addProperty("name", name == null || name.isEmpty() ? url : name);
        item.addProperty("order", order);
        item.addProperty("ok", prev == null || !prev.has("ok") || prev.get("ok").getAsBoolean());
        item.addProperty("ts", prev != null && prev.has("ts") ? prev.get("ts").getAsLong() : 0);
        item.addProperty("enabled", prev == null || !prev.has("enabled") || prev.get("enabled").getAsBoolean());
        if (prev != null && prev.has("chan")) item.addProperty("chan", prev.get("chan").getAsInt());
        if (from != null && !from.isEmpty()) item.addProperty("from", from);
        if (ua != null && !ua.isEmpty()) item.addProperty("ua", ua);
        if (header != null && !header.isEmpty()) item.add("header", GSON.toJsonTree(header));
        if (epg != null && !epg.isEmpty()) item.addProperty("epg", epg);
        return item;
    }

    /** 池条目携带的请求头（ua/header 字段），无则空 Map；聚合拉取时随源传递。 */
    static Map<String, String> sourceHeaders(JsonObject state) {
        Map<String, String> headers = new HashMap<>();
        if (state.has("ua")) headers.put(HttpHeaders.USER_AGENT, state.get("ua").getAsString());
        if (state.has("header")) {
            try {
                for (Map.Entry<String, JsonElement> e : state.getAsJsonObject("header").entrySet())
                    headers.put(e.getKey(), e.getValue().getAsString());
            } catch (Throwable ignored) {
            }
        }
        return headers;
    }

    /**
     * 点播配置 lives 自动入池（LIVE6 核心）：带 url 的直播源按 url 去重入池，
     * 带 from=点播配置 来源标记并保留 ua/header/epg 参数；spider 型（无 url）不参与。
     * LIVE7 修复：入池同步建 type=1 Config 行——pool() 只认配置行，缺行会导致随仓源永远不参与聚合。
     * 有新增才后台聚合并按策略自动启用；重复解析/来回切换无副作用。
     */
    public static void integrateFromVod(List<Live> lives, String from) {
        if (lives == null || lives.isEmpty()) return;
        Task.submit(() -> {
            try {
                JsonObject meta = readMeta();
                JsonArray sources = meta.has("sources") ? meta.getAsJsonArray("sources") : new JsonArray();
                Set<String> existing = new HashSet<>();
                for (JsonElement e : sources) existing.add(e.getAsJsonObject().get("url").getAsString());
                boolean changed = false;
                for (Live live : lives) {
                    String url = live.getUrl();
                    if (TextUtils.isEmpty(url) || !url.startsWith("http") || existing.contains(url)) continue;
                    existing.add(url);
                    sources.add(poolItem(url, live.getName(), from, live.getUa(), live.getHeader(), live.getEpg(), sources.size(), null));
                    if (AppDatabase.get().getConfigDao().find(url, 1) == null) {
                        String name = TextUtils.isEmpty(live.getName()) ? UrlUtil.getName(url) : live.getName();
                        Config.find(url, name, 1);
                    }
                    changed = true;
                }
                if (!changed) return;
                meta.add("sources", sources);
                writeMeta(meta);
                Prefers.put(KEY_AGG_TS, 0);
                if (aggregate()) applyAutoUse();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
    }

    /** 手动选择直播配置（会话换源汇入点）：非「聚合」即记手动优先，自动聚合不再抢切；选回「聚合」恢复自动。 */
    public static void onManualSelect(Config config) {
        Prefers.put(KEY_MANUAL, !isAggregate(config));
    }

    /** 聚合内容变化后的自动启用：聚合在用 → 静默重载；未手动选择过其他源 → 静默切到聚合（软刷新不断播）。 */
    private static void applyAutoUse() {
        App.post(() -> {
            try {
                Config current = LiveConfig.get().getConfig();
                if (isAggregate(current)) {
                    LiveConfig.get().reloadQuietly();
                    return;
                }
                if (Prefers.getBoolean(KEY_MANUAL)) return;
                LiveConfig.get().config(ensureConfig()).reloadQuietly();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
    }

    /** 点播配置删除联动：移除其带入（from 匹配）的池条目并重聚合；池删空则清盘聚合产物。 */
    public static void onVodConfigDeleted(String url) {
        if (TextUtils.isEmpty(url)) return;
        Task.submit(() -> {
            try {
                JsonObject meta = readMeta();
                if (!meta.has("sources")) return;
                JsonArray kept = new JsonArray();
                boolean removed = false;
                for (JsonElement e : meta.getAsJsonArray("sources")) {
                    JsonObject item = e.getAsJsonObject();
                    if (url.equals(item.has("from") ? item.get("from").getAsString() : "")) {
                        removed = true;
                        continue;
                    }
                    kept.add(e);
                }
                if (!removed) return;
                meta.add("sources", kept);
                writeMeta(meta);
                Prefers.put(KEY_AGG_TS, 0);
                if (kept.size() == 0) {
                    reset();
                    if (isAggregate(LiveConfig.get().getConfig())) App.post(() -> LiveConfig.load(ensureConfig(), new Callback()));
                    return;
                }
                if (aggregate()) applyAutoUse();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
    }

    // ---------- 合并与排序 ----------

    private static Live merge(List<Live> parsed) {
        Live merged = new Live(NAME, url());
        Map<String, Group> groupMap = new LinkedHashMap<>();
        Map<Group, Map<String, Channel>> chanMap = new LinkedHashMap<>();
        Set<String> epgs = new LinkedHashSet<>();
        for (Live live : parsed) {
            for (String epg : live.getEpg().split(",")) if (!epg.trim().isEmpty()) epgs.add(epg.trim());
            for (Group group : live.getGroups()) {
                String canonical = normalizeGroup(group.getName());
                if (canonical.isEmpty()) canonical = group.getName();
                Group target = groupMap.get(canonical);
                if (target == null) {
                    // 先建占位组再改名，避开构造器对 "_" 的密码拆分
                    target = new Group("-");
                    target.setName(canonical);
                    if (group.isHidden()) target.setPass(group.getPass());
                    groupMap.put(canonical, target);
                    chanMap.put(target, new LinkedHashMap<>());
                } else if (group.isHidden() && target.getPass().isEmpty()) {
                    // 任一来源隐藏即合并结果隐藏，避免后到来源把隐藏频道变可见
                    target.setPass(group.getPass());
                }
                Map<String, Channel> chans = chanMap.get(target);
                for (Channel channel : group.getChannel()) {
                    if (channel.getUrls().isEmpty()) continue;
                    String key = normalizeChannel(channel.getName());
                    if (key.isEmpty()) continue;
                    Channel exist = chans.get(key);
                    if (exist == null) {
                        chans.put(key, channel);
                        continue;
                    }
                    exist.mergeLines(channel);
                    fillIfEmpty(exist, channel);
                }
            }
        }
        List<Group> ordered = new ArrayList<>(groupMap.values());
        ordered.sort(Comparator.comparingInt((Group g) -> order(g.getName())).thenComparing(Group::getName));
        ordered.sort(Comparator.comparingInt(g -> g.isHidden() ? 1 : 0));
        for (Group group : ordered) {
            List<Channel> chans = new ArrayList<>(chanMap.get(group).values());
            chans.sort(Comparator.comparingInt((Channel c) -> num(c.getNumber())).thenComparing(Channel::getName));
            group.setChannel(chans);
            group.setPosition(0);
            merged.getGroups().add(group);
        }
        renumber(merged.getGroups());
        merged.setEpg(String.join(",", epgs));
        return merged;
    }

    /** 同名频道合并时按「有则补」回填播放/节目属性，避免来自需 UA/DRM 源的线路聚合后失效。 */
    private static void fillIfEmpty(Channel target, Channel other) {
        if (target.getTvgId().isEmpty()) target.setTvgId(other.getTvgId());
        if (target.getTvgName().isEmpty()) target.setTvgName(other.getTvgName());
        if (target.getLogo().isEmpty()) target.setLogo(other.getLogo());
        if (target.getEpg().isEmpty()) target.setEpg(other.getEpg());
        if (target.getUa().isEmpty()) target.setUa(other.getUa());
        if (target.getOrigin().isEmpty()) target.setOrigin(other.getOrigin());
        if (target.getReferer().isEmpty()) target.setReferer(other.getReferer());
        if (target.getClick().isEmpty()) target.setClick(other.getClick());
        if (target.getHeader().isEmpty() && !other.getHeader().isEmpty()) target.setHeader(new HashMap<>(other.getHeader()));
        if (target.getFormat() == null) target.setFormat(other.getFormat());
        if (target.getParse() == 0) target.setParse(other.getParse());
        if (target.getDrm() == null) target.setDrm(other.getDrm());
        if (target.getCatchup().isEmpty()) target.setCatchup(other.getCatchup());
    }

    /** 显式台号先到先得（统一 %03d，兼容数字选台的 %03d 查找），重复与缺失者按最终顺序补连续编号。 */
    private static void renumber(List<Group> ordered) {
        Set<Integer> used = new HashSet<>();
        for (Group group : ordered)
            for (Channel channel : group.getChannel()) {
                int n = num(channel.getNumber());
                if (n > 0 && used.add(n)) channel.setNumber(n);
                else channel.setNumber("");
            }
        int next = 1;
        for (Group group : ordered)
            for (Channel channel : group.getChannel()) {
                if (!channel.getNumber().isEmpty()) continue;
                while (used.contains(next)) next++;
                channel.setNumber(next);
                used.add(next);
            }
    }

    private static int num(String number) {
        try {
            return Integer.parseInt(number);
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- 序列化与落盘 ----------

    private static String toJson(Live merged) {
        // 反向引用置空避免 Gson 无限递归；内存态随后丢弃，展示数据由文件重载重建
        for (Group group : merged.getGroups()) for (Channel channel : group.getChannel()) channel.setGroup(null);
        JsonObject object = new JsonObject();
        JsonArray lives = new JsonArray();
        lives.add(App.gson().toJsonTree(merged));
        object.add("lives", lives);
        return object.toString();
    }

    private static void write(File file, String json) {
        File temp = null;
        try {
            // 唯一临时名：并发聚合不互写同一 .tmp；崩溃遗留孤儿由 aggregate() 开头按龄清理
            temp = new File(file.getParentFile(), file.getName() + "." + Thread.currentThread().getId() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Exception e) {
            if (temp != null) temp.delete();
        }
    }

    private static JsonObject readJson(File file) {
        try {
            String json = Path.read(file);
            return TextUtils.isEmpty(json) ? new JsonObject() : Json.parse(json).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    static JsonObject readMeta() {
        return readJson(metaFile());
    }

    static void writeMeta(JsonObject meta) {
        write(metaFile(), meta.toString());
    }

    // ---------- 刷新入口 ----------

    /** 自动刷新阈值（小时，Setting.stale 控制，0 = 关闭自动刷新；0 使手动聚合强制全量重拉）。 */
    private static long staleMs() {
        return Setting.getStale() * 3600_000L;
    }

    /** 进直播页检查：聚合配置激活且超龄时后台重聚合；内容变化则静默重载 + 广播软刷新。 */
    public static void refreshIfStale() {
        try {
            if (Setting.getStale() <= 0) return;
            if (!isAggregate(LiveConfig.get().getConfig())) return;
            if (!isStale()) return;
            Task.submit(() -> {
                boolean changed;
                try {
                    changed = aggregate();
                } catch (Throwable e) {
                    e.printStackTrace();
                    return;
                }
                if (changed) App.post(() -> LiveConfig.get().reloadQuietly());
            });
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /** 超龄判定（主线程可调用）：只读 Prefers 时间戳与文件存在性，单源粒度由 aggregate() 内部按 ts 增量拉取兜底。 */
    public static boolean isStale() {
        if (!file().exists()) return true;
        long ts = Prefers.getLong(KEY_AGG_TS);
        return ts <= 0 || System.currentTimeMillis() - ts >= staleMs();
    }
}
