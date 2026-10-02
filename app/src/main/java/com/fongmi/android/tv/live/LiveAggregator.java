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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多仓聚合：把源池内各订阅源（txt/m3u）的频道按归一名合并成一份直播数据，
 * 落盘 filesDir/live/aggregate.json 并注册为 file:// 直播配置（type=1）。
 * 源池定义与每源状态存 aggregate.meta.json；lines 记录播放 url → 来源名，供溯源与 LIVE5 探测使用。
 * 加载走现有 JSON lives 配置路径，本类只在导入与超龄刷新时生成文件。
 */
public class LiveAggregator {

    private static final String NAME = "聚合";
    private static final long STALE_MS = 12L * 60 * 60 * 1000;
    private static final String KEY_FETCH_TS = "live_fetch_ts_";
    private static final long FETCH_TIMEOUT = 20000;
    // 全死频道隔离阈值：连续 2 轮全部线路失效才移出聚合文件（可自动复活）
    private static final int STREAK_LIMIT = 2;
    // 频道名归一时剔除的分隔符；刻意保留 +（CCTV5+）
    private static final String SEPARATORS = "-—–_·•.。:：,，、;；!！?？（）()[]【】「」『』《》<>｜|/／\\";
    private static final String[] THEMES = {"体育", "竞技", "赛事", "电影", "影院", "剧场", "纪录", "纪实", "少儿", "卡通", "动漫", "动画", "教育", "新闻", "资讯", "音乐", "财经", "生活", "科技", "文艺", "都市", "法治", "法制", "港澳", "国际", "海外", "戏曲", "旅游", "汽车", "购物", "广播", "电台"};

    // ---------- 探测策略（纯函数，供单测） ----------

    /** 线路排序：可用(延迟升序) → 未探测(原序) → 失效(原序)，稳定排序。 */
    static List<String> orderUrls(List<String> urls, JsonObject probe) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) order.add(i);
        order.sort((x, y) -> {
            int[] a = score(urls.get(x), x, probe);
            int[] b = score(urls.get(y), y, probe);
            return a[0] != b[0] ? a[0] - b[0] : a[1] != b[1] ? Long.compare(a[1], b[1]) : x - y;
        });
        List<String> result = new ArrayList<>(urls.size());
        for (int i : order) result.add(urls.get(i));
        return result;
    }

    /** [桶, 次键]：0=可用(次键延迟)，1=未探测/不可探测(次键原序)，2=失效(次键原序)。 */
    static int[] score(String url, int index, JsonObject probe) {
        if (!url.startsWith("http") || probe == null || !probe.has(url)) return new int[]{1, index};
        JsonObject state = probe.getAsJsonObject(url);
        boolean ok = state.has("ok") && state.get("ok").getAsBoolean();
        long latency = state.has("latency") ? state.get("latency").getAsLong() : index;
        return ok ? new int[]{0, (int) Math.min(latency, Integer.MAX_VALUE)} : new int[]{2, index};
    }

    /** 隔离判定：频道全部线路可探测、全部失效且连续失败达到阈值；混入不可探测协议即不隔离。 */
    static boolean allDead(List<String> urls, JsonObject probe, int limit) {
        if (urls.isEmpty()) return false;
        for (String url : urls) {
            if (!url.startsWith("http")) return false;
            if (probe == null || !probe.has(url)) return false;
            JsonObject state = probe.getAsJsonObject(url);
            if (state.has("ok") && state.get("ok").getAsBoolean()) return false;
            int streak = state.has("streak") ? state.get("streak").getAsInt() : 0;
            if (streak < limit) return false;
        }
        return true;
    }


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
        JsonArray sources = new JsonArray();
        int order = 0;
        for (Config config : configs) {
            if (TextUtils.isEmpty(config.getUrl())) continue;
            urls.add(config.getUrl());
            JsonObject item = new JsonObject();
            item.addProperty("url", config.getUrl());
            item.addProperty("name", config.getName());
            item.addProperty("order", order++);
            item.addProperty("ok", true);
            item.addProperty("ts", 0);
            sources.add(item);
        }
        JsonObject meta = readMeta();
        List<String> old = new ArrayList<>();
        if (meta.has("sources")) for (JsonElement e : meta.getAsJsonArray("sources")) old.add(e.getAsJsonObject().get("url").getAsString());
        if (old.equals(urls)) return false;
        meta.add("sources", sources);
        writeMeta(meta);
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
        List<Config> pool = pool();
        if (pool.isEmpty()) return false;
        if (notify) App.post(() -> Notify.show(ResUtil.getString(R.string.live_agg_start, pool.size())));
        JsonObject meta = readMeta();
        JsonArray sources = meta.getAsJsonArray("sources");
        JsonObject lines = meta.has("lines") ? meta.getAsJsonObject("lines") : new JsonObject();
        JsonObject probe = meta.has("probe") ? meta.getAsJsonObject("probe") : new JsonObject();
        Set<String> quarantine = quarantineKeys(meta);
        List<Live> parsed = new ArrayList<>();
        for (Config config : pool) {
            JsonObject state = findState(sources, config.getUrl());
            Live live = parse(config, state);
            if (live == null) continue;
            for (Group group : live.getGroups())
                for (Channel channel : group.getChannel())
                    for (String url : channel.getUrls()) lines.addProperty(url, config.getName());
            parsed.add(live);
        }
        if (parsed.isEmpty()) {
            if (notify) App.post(() -> Notify.show(R.string.live_agg_failed));
            return false;
        }
        Live merged = merge(parsed, probe, quarantine);
        if (notify) {
            int channels = 0, linesCount = 0;
            for (Group group : merged.getGroups())
                for (Channel channel : group.getChannel()) {
                    channels++;
                    linesCount += channel.getUrls().size();
                }
            int channelCount = channels, lineCount = linesCount;
            App.post(() -> Notify.show(ResUtil.getString(R.string.live_agg_done, channelCount, lineCount)));
        }
        String json = toJson(merged);
        String previous = file().exists() ? Path.read(file()) : "";
        meta.add("lines", lines);
        if (json.equals(previous)) {
            writeMeta(meta);
            return false;
        }
        write(file(), json);
        writeMeta(meta);
        // 新线路增量探测不阻塞加载流程
        Task.submit(LiveProbe::startMissing);
        return true;
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
        boolean stale = ts <= 0 || System.currentTimeMillis() - ts >= STALE_MS;
        String content = null;
        if (stale) {
            try {
                content = OkHttp.string(UrlUtil.convert(config.getUrl()), FETCH_TIMEOUT);
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
        try {
            LiveParser.text(live, content);
        } catch (Throwable e) {
            return null;
        }
        return live.getGroups().isEmpty() ? null : live;
    }

    // ---------- 合并与排序 ----------

    private static Live merge(List<Live> parsed, JsonObject probe, Set<String> quarantine) {
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
                }
                Map<String, Channel> chans = chanMap.get(target);
                for (Channel channel : group.getChannel()) {
                    if (channel.getUrls().isEmpty()) continue;
                    String key = normalizeChannel(channel.getName());
                    if (key.isEmpty()) continue;
                    // 隔离区频道不参与合并，防止重聚合复活
                    if (quarantine.contains(canonical + "\u0001" + key)) continue;
                    Channel exist = chans.get(key);
                    if (exist == null) {
                        chans.put(key, channel);
                        continue;
                    }
                    for (String url : channel.getUrls()) if (!exist.getUrls().contains(url)) exist.getUrls().add(url);
                    if (exist.getTvgId().isEmpty()) exist.setTvgId(channel.getTvgId());
                    if (exist.getTvgName().isEmpty()) exist.setTvgName(channel.getTvgName());
                    if (exist.getLogo().isEmpty()) exist.setLogo(channel.getLogo());
                }
            }
        }
        List<Group> ordered = new ArrayList<>(groupMap.values());
        ordered.sort(Comparator.comparingInt((Group g) -> order(g.getName())).thenComparing(Group::getName));
        ordered.sort(Comparator.comparingInt(g -> g.isHidden() ? 1 : 0));
        for (Group group : ordered) {
            List<Channel> chans = new ArrayList<>(chanMap.get(group).values());
            chans.sort(Comparator.comparingInt((Channel c) -> num(c.getNumber())).thenComparing(Channel::getName));
            for (Channel channel : chans) channel.setUrls(orderUrls(channel.getUrls(), probe));
            group.setChannel(chans);
            group.setPosition(0);
            merged.getGroups().add(group);
        }
        renumber(merged.getGroups());
        merged.setEpg(String.join(",", epgs));
        return merged;
    }

    /** 显式台号先到先得，重复与缺失者按最终顺序补连续编号，保证全树唯一。 */
    private static void renumber(List<Group> ordered) {
        Set<Integer> used = new HashSet<>();
        for (Group group : ordered)
            for (Channel channel : group.getChannel()) {
                int n = num(channel.getNumber());
                if (n > 0 && !used.contains(n)) used.add(n);
                else channel.setNumber("");
            }
        int next = 1;
        for (Group group : ordered)
            for (Channel channel : group.getChannel()) {
                if (!channel.getNumber().isEmpty()) continue;
                while (used.contains(next)) next++;
                channel.setNumber(String.valueOf(next));
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
            temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Exception e) {
            if (temp != null) temp.delete();
        }
    }

    static JsonObject readMeta() {
        try {
            String json = Path.read(metaFile());
            return TextUtils.isEmpty(json) ? new JsonObject() : Json.parse(json).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    static void writeMeta(JsonObject meta) {
        write(metaFile(), meta.toString());
    }

    // ---------- 刷新入口 ----------

    /** 进直播页检查：聚合配置激活且源池超龄时后台重聚合；内容变化则静默重载。 */
    public static void refreshIfStale() {
        try {
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
                if (changed) App.post(() -> LiveConfig.get().load());
            });
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /** 源池超龄判定：任一源无时间戳或超阈值；有池定义但尚无聚合文件时同样需要重试。 */
    public static boolean isStale() {
        JsonObject meta = readMeta();
        if (!meta.has("sources") || meta.getAsJsonArray("sources").size() == 0) return false;
        if (!file().exists()) return true;
        long now = System.currentTimeMillis();
        for (JsonElement e : meta.getAsJsonArray("sources")) {
            JsonObject item = e.getAsJsonObject();
            long ts = item.has("ts") ? item.get("ts").getAsLong() : 0;
            if (ts <= 0 || now - ts >= STALE_MS) return true;
        }
        return false;
    }

    // ---------- 探测结果应用（LIVE5） ----------

    static JsonObject probeMap() {
        JsonObject meta = readMeta();
        return meta.has("probe") ? meta.getAsJsonObject("probe") : new JsonObject();
    }

    static Set<String> quarantineKeys(JsonObject meta) {
        Set<String> keys = new HashSet<>();
        if (!meta.has("quarantine")) return keys;
        for (JsonElement e : meta.getAsJsonArray("quarantine")) {
            JsonObject item = e.getAsJsonObject();
            if (item.has("group") && item.has("key")) keys.add(item.get("group").getAsString() + "\u0001" + item.get("key").getAsString());
        }
        return keys;
    }

    /** 聚合文件中的全部频道（探测采集用；文件缺失/损坏返回空）。 */
    static List<Channel> fileChannels() {
        List<Channel> channels = new ArrayList<>();
        try {
            for (Group group : parseFile().getGroups()) channels.addAll(group.getChannel());
        } catch (Throwable ignored) {
        }
        return channels;
    }

    /** 隔离区频道（复活探测用）。 */
    static List<Channel> quarantined() {
        List<Channel> channels = new ArrayList<>();
        JsonObject meta = readMeta();
        if (!meta.has("quarantine")) return channels;
        for (JsonElement e : meta.getAsJsonArray("quarantine")) {
            try {
                channels.add(App.gson().fromJson(e.getAsJsonObject().getAsJsonObject("channel"), Channel.class));
            } catch (Throwable ignored) {
            }
        }
        return channels;
    }

    /** 轮末应用探测结果：更新 probe、重排线路、执行隔离/复活；文件有变才写盘。返回文件是否变化。 */
    static boolean applyProbe(Map<String, LiveProbe.Result> results) {
        JsonObject meta = readMeta();
        JsonObject probe = meta.has("probe") ? meta.getAsJsonObject("probe") : new JsonObject();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, LiveProbe.Result> e : results.entrySet()) {
            JsonObject state = probe.has(e.getKey()) ? probe.getAsJsonObject(e.getKey()) : new JsonObject();
            LiveProbe.Result result = e.getValue();
            if (result.ok) {
                state.addProperty("ok", true);
                state.addProperty("latency", result.latency);
                state.addProperty("streak", 0);
            } else {
                state.addProperty("ok", false);
                state.addProperty("streak", (state.has("streak") ? state.get("streak").getAsInt() : 0) + 1);
            }
            state.addProperty("ts", now);
            probe.add(e.getKey(), state);
        }
        // 清理已不在文件与隔离区的历史探测项
        Set<String> alive = new HashSet<>();
        for (Channel channel : fileChannels()) alive.addAll(channel.getUrls());
        for (Channel channel : quarantined()) alive.addAll(channel.getUrls());
        JsonObject pruned = new JsonObject();
        for (String url : probe.keySet()) if (alive.contains(url)) pruned.add(url, probe.get(url));
        meta.add("probe", pruned);
        boolean changed = rewrite(meta, pruned);
        writeMeta(meta);
        return changed;
    }

    /** 按探测状态重写聚合文件：线路重排 + 全死频道隔离 + 复活回插。 */
    private static boolean rewrite(JsonObject meta, JsonObject probe) {
        if (!file().exists()) return false;
        Live live;
        try {
            live = parseFile();
        } catch (Throwable e) {
            return false;
        }
        boolean changed = false;
        JsonArray quarantine = meta.has("quarantine") ? meta.getAsJsonArray("quarantine") : new JsonArray();
        // 复活：隔离区任一线路可用即回插原归一组
        List<JsonObject> released = new ArrayList<>();
        for (JsonElement e : quarantine) {
            JsonObject entry = e.getAsJsonObject();
            if (!revived(entry, probe)) continue;
            released.add(entry);
            insert(entry, live);
            changed = true;
        }
        if (!released.isEmpty()) {
            JsonArray remaining = new JsonArray();
            for (JsonElement e : quarantine) if (!released.contains(e.getAsJsonObject())) remaining.add(e);
            meta.add("quarantine", remaining);
            quarantine = remaining;
        }
        // 重排 + 隔离
        List<Group> empty = new ArrayList<>();
        for (Group group : live.getGroups()) {
            List<Channel> keep = new ArrayList<>();
            for (Channel channel : group.getChannel()) {
                List<String> ordered = orderUrls(channel.getUrls(), probe);
                if (!ordered.equals(channel.getUrls())) {
                    channel.setUrls(ordered);
                    changed = true;
                }
                if (allDead(channel.getUrls(), probe, STREAK_LIMIT)) {
                    quarantine.add(quarantineEntry(group, channel));
                    changed = true;
                } else {
                    keep.add(channel);
                }
            }
            if (keep.size() != group.getChannel().size()) {
                changed = true;
                if (keep.isEmpty()) empty.add(group);
                else group.setChannel(keep);
            }
        }
        live.getGroups().removeAll(empty);
        if (changed) write(file(), toJson(live));
        return changed;
    }

    private static boolean revived(JsonObject entry, JsonObject probe) {
        if (!entry.has("channel")) return false;
        for (JsonElement e : entry.getAsJsonObject("channel").getAsJsonArray("urls")) {
            String url = e.getAsString();
            if (url.startsWith("http") && probe.has(url)) {
                JsonObject state = probe.getAsJsonObject(url);
                if (state.has("ok") && state.get("ok").getAsBoolean()) return true;
            }
        }
        return false;
    }

    private static void insert(JsonObject entry, Live live) {
        Channel channel = App.gson().fromJson(entry.getAsJsonObject("channel"), Channel.class);
        String groupName = entry.get("group").getAsString();
        Group target = null;
        for (Group group : live.getGroups()) if (group.getName().equals(groupName)) target = group;
        if (target == null) {
            // 先建占位组再改名，避开构造器对 "_" 的密码拆分
            target = new Group("-");
            target.setName(groupName);
            if (entry.has("pass")) target.setPass(entry.get("pass").getAsString());
            live.getGroups().add(target);
        }
        channel.setGroup(target);
        target.getChannel().add(channel);
    }

    private static JsonObject quarantineEntry(Group group, Channel channel) {
        JsonObject entry = new JsonObject();
        entry.addProperty("group", group.getName());
        if (group.getPass() != null) entry.addProperty("pass", group.getPass());
        entry.addProperty("key", normalizeChannel(channel.getName()));
        channel.setGroup(null);
        entry.add("channel", App.gson().toJsonTree(channel));
        return entry;
    }

    private static Live parseFile() throws Exception {
        String json = Path.read(file());
        JsonObject root = Json.parse(json).getAsJsonObject();
        return Live.objectFrom(root.getAsJsonArray("lives").get(0), "");
    }
}
