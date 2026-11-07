package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LiveConfig extends BaseConfig {

    private static final String TAG = LiveConfig.class.getSimpleName();

    private Live home;
    private List<Live> lives;
    private List<Rule> rules;
    private List<String> ads;
    private volatile Config pendingDepot;
    private volatile Set<String> pendingNames;
    private final AtomicBoolean expanding = new AtomicBoolean();

    public static LiveConfig get() {
        return Loader.INSTANCE;
    }

    public static int getCid() {
        return get().getConfig().getId();
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static String getResp() {
        return get().getHome().getCore().getResp();
    }

    public static int getHomeIndex() {
        return get().getLives().indexOf(get().getHome());
    }

    public static boolean isOnly() {
        return get().getLives().size() == 1;
    }

    public static boolean isEmpty() {
        return get().getHome().isEmpty();
    }

    public static boolean hasLoadedLives() {
        return !get().getLives().isEmpty();
    }

    public static boolean hasUrl() {
        return !TextUtils.isEmpty(getUrl());
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public LiveConfig init() {
        return config(Config.live());
    }

    public LiveConfig config(Config config) {
        this.config = config;
        if (config.isEmpty()) return this;
        this.sync = config.getUrl().equals(VodConfig.getUrl());
        return this;
    }

    public LiveConfig clear() {
        ads = null;
        home = null;
        lives = null;
        rules = null;
        pendingDepot = null;
        pendingNames = null;
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected int getType() {
        return LIVE;
    }

    @Override
    protected Config defaultConfig() {
        return Config.live();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.live();
    }

    @Override
    protected void load(Config config) throws Throwable {
        if (config.isEmpty()) {
            initLive(config, new JsonObject());
            return;
        }
        String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
        if (Json.isObj(json)) checkJson(config, Json.parse(json).getAsJsonObject());
        else parseText(config, json);
        config.setJson(json);
    }

    @Override
    protected boolean loadFromCache(Config config, Callback callback) {
        try {
            String json = config.getJson();
            if (Json.isObj(json)) {
                JsonObject object = Json.parse(json).getAsJsonObject();
                if (object.has("urls") || object.has("msg")) return false;
                checkJson(config, object);
            } else {
                parseText(config, json);
            }
            App.post(callback::success);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    protected boolean isLoaded() {
        return !getLives().isEmpty() && !getHome().getGroups().isEmpty();
    }

    @Override
    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            super.ensureLoaded();
            LiveApi.parse(getHome());
            LiveApi.parseXml(getHome());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public void load() {
        if (sync) return;
        load(new Callback());
    }

    private void parseText(Config config, String text) {
        Live live = new Live(UrlUtil.getName(config.getUrl()), config.getUrl()).sourceId(config.getId()).sync(config.getId());
        lives = new ArrayList<>(List.of(live));
        LiveParser.text(live, text);
        finishLive(config);
    }

    private void checkJson(Config config, JsonObject object) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object);
        } else {
            parseConfig(config, object);
        }
    }

    private void parseDepot(Config config, JsonObject object) throws Throwable {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        // 多仓入口本身保留为 Config(kind=DEPOT)，直播侧聚合全部子仓直播源（不切单仓）
        // 复用已落库的多仓入口行，避免整行覆盖清掉 json/time/active
        Config depot = ConfigRepository.upsert(DepotRow.resolve(ConfigRepository.findByUrl(config.getUrl(), LIVE), config.getUrl(), LIVE, config.getName(), Config.DEPOT));
        ConfigService.registerDepot(depot, items);
        aggregate(depot);
    }

    /**
     * 多仓直播聚合（两段式）：
     * 第一段同步出「当前子仓」的 lives（通常命中 L1 缓存零请求），保证直播界面立刻可见；
     * 第二段后台并行展开其余子仓，原地追加并集（同名去重、按子仓顺序首次命中）。
     * 每个直播源仍是独立源（自己的 type/url/频道），不做频道级合并。
     */
    private void aggregate(Config depot) throws Throwable {
        Config primary = currentChild(depot);
        List<Live> lives = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        List<Rule> rules = new ArrayList<>();
        List<String> ads = new ArrayList<>();
        DepotExpander.Leaf leaf = DepotExpander.leaf(depot, primary);
        if (leaf != null) collect(depot, leaf.json, names, lives, rules, ads);
        if (lives.isEmpty()) {
            // 第一段取不到（无缓存且拉取失败）：回退同步全量展开，避免误判「无直播源」
            for (DepotExpander.Leaf item : DepotExpander.expand(depot)) collect(depot, item.json, names, lives, rules, ads);
        }
        if (lives.isEmpty()) throw new Exception("Depot lives is empty");
        setLives(lives);
        setRules(rules);
        setAds(ads);
        finishLive(depot);
        // 第二段不再随配置加载执行：先记录待补全，等用户真正进入直播页/打开直播源列表时再展开
        markPending(depot, names);
    }

    /**
     * 多仓直播第二段按需执行：用户进入直播页或打开直播源列表时才补全其余子仓。
     * 可重入，同一轮展开只提交一次；写入仍受 complete 内的入口失效保护约束。
     */
    public void expandPending() {
        Config depot = pendingDepot;
        Set<String> names = pendingNames;
        if (depot == null || names == null) return;
        if (!expanding.compareAndSet(false, true)) return;
        pendingDepot = null;
        pendingNames = null;
        // 第二段展开放到配置专用池：它会阻塞等待子仓，放进共享池会与首页/搜索/图片排队
        Task.config().submit(() -> {
            try {
                complete(depot, names);
            } finally {
                expanding.set(false);
            }
        });
    }

    private void markPending(Config depot, Set<String> names) {
        pendingDepot = depot;
        pendingNames = names;
    }

    /** 当前点播激活子源（若属于本多仓，则其 lives 作为第一段优先内容，且 json 刚拉过必命中缓存） */
    private Config currentChild(Config depot) {
        Config vod = VodConfig.get().getConfig();
        if (vod == null || vod.isEmpty()) return null;
        Config parent = ConfigRepository.findParentDepot(vod);
        return parent != null && parent.getUrl().equals(depot.getUrl()) ? vod : null;
    }

    /** 第二段：后台补全其余子仓，原地追加（LiveAdapter 持同一引用；不发事件避免重置播放器） */
    private void complete(Config depot, Set<String> names) {
        try {
            List<Live> fresh = new ArrayList<>();
            List<Rule> rules = new ArrayList<>();
            List<String> ads = new ArrayList<>();
            for (DepotExpander.Leaf leaf : DepotExpander.expand(depot)) collect(depot, leaf.json, names, fresh, rules, ads);
            if (fresh.isEmpty()) return;
            App.post(() -> {
                if (getConfig() == null || getLives().isEmpty()) return;
                if (!getConfig().getUrl().equals(depot.getUrl())) return;
                getLives().addAll(fresh);
                List<Rule> allRules = new ArrayList<>(getRules());
                for (Rule rule : rules) if (!allRules.contains(rule)) allRules.add(rule);
                setRules(allRules);
                List<String> allAds = new ArrayList<>(getAds());
                for (String ad : ads) if (!allAds.contains(ad)) allAds.add(ad);
                setAds(allAds);
                // 通知已打开的直播源对话框刷新（不重置播放器）；未打开则下次打开即见全量
                ConfigEvent.liveList();
            });
        } catch (Throwable ignored) {
        }
    }

    private void collect(Config depot, JsonObject object, Set<String> names, List<Live> lives, List<Rule> rules, List<String> ads) {
        String spider = Json.safeString(object, "spider");
        BaseLoader.get().parseJar(spider, false);
        List<Live> items = Json.safeListElement(object, "lives").stream().map(e -> Live.objectFrom(e, spider).sourceId(depot.getId())).filter(live -> !live.getName().isEmpty()).collect(Collectors.toCollection(ArrayList::new));
        CustomCspSetting.inject(items, spider);
        for (Live live : items) if (names.add(live.getName())) lives.add(live);
        // 网络规则入池：各子仓并集去重、永久保留
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setHosts(Json.safeListString(object, "hosts"));
        for (Rule rule : Rule.arrayFrom(fetchArray(object, "rules"))) if (!rules.contains(rule)) rules.add(rule);
        for (String ad : Json.safeListString(object, "ads")) if (!ads.contains(ad)) ads.add(ad);
    }

    private void parseConfig(Config config, JsonObject object) {
        CustomCspSetting.inject(object);
        initList(object);
        initLive(config, object);
    }

    public void parse(JsonObject object) {
        initLive(getConfig(), object);
    }

    private void initList(JsonObject object) {
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initLive(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        BaseLoader.get().parseJar(spider, false);
        List<Live> items = Json.safeListElement(object, "lives").stream().map(e -> Live.objectFrom(e, spider).sourceId(config.getId())).distinct().collect(Collectors.toCollection(ArrayList::new));
        CustomCspSetting.inject(items, spider);
        setLives(items);
        finishLive(config);
    }

    private void finishLive(Config config) {
        getLives().removeIf(Live::isEmpty);
        Map<String, Live> items = Live.findBySource(config.getId()).stream().collect(Collectors.toMap(Live::getName, Function.identity()));
        getLives().forEach(live -> live.sync(items.get(live.getName())));
        setHome(config, getLives().isEmpty() ? new Live() : getLives().stream().filter(item -> item.getName().equals(config.getHome())).findFirst().orElse(getLives().get(0)), false);
    }

    public void setKeep(Channel channel) {
        if (home != null && !channel.getGroup().isHidden()) home.keep(channel).save();
    }

    public void applyKeepsToGroups(List<Group> items) {
        Set<String> key = Keep.getLive().stream().map(Keep::getKey).collect(Collectors.toSet());
        items.stream().filter(group -> !group.isKeep())
                .flatMap(group -> group.getChannel().stream())
                .filter(channel -> key.contains(channel.getName()))
                .forEach(channel -> items.get(0).add(channel));
    }

    public int[] findKeepPosition(List<Group> items) {
        String[] splits = getHome().getKeep().split(AppDatabase.SYMBOL);
        if (splits.length < 3) return new int[]{1, 0};
        for (int i = 0; i < items.size(); i++) {
            Group group = items.get(i);
            if (group.getName().equals(splits[0])) {
                int j = group.find(splits[1]);
                if (j != -1) {
                    group.getChannel().get(j).setIndex(splits[2]);
                    return new int[]{i, j};
                }
            }
        }
        return new int[]{1, 0};
    }

    public int[] findByChannelNumber(String number, List<Group> items) {
        int num = Integer.parseInt(number);
        for (int i = 0; i < items.size(); i++) {
            int j = items.get(i).find(num);
            if (j != -1) return new int[]{i, j};
        }
        return new int[]{-1, -1};
    }

    public List<Live> getLives() {
        return lives == null ? lives = new ArrayList<>() : lives;
    }

    private void setLives(List<Live> lives) {
        this.lives = lives;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = RulePool.mergeRules(rules);
        RuleConfig.get().invalidate();
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = RulePool.mergeAds(ads);
        RuleConfig.get().invalidate();
    }

    public Live getHome() {
        return home == null ? new Live() : home;
    }

    public void setHome(Live home) {
        setHome(getConfig(), home, true);
    }

    public Live getLive(String key) {
        return getLives().stream().filter(item -> item.getName().equals(key)).findFirst().orElse(new Live());
    }

    private void setHome(Config config, Live live, boolean save) {
        home = live;
        home.setSelected(true);
        config.setHome(home.getName());
        if (save) config.save();
        getLives().forEach(item -> item.setSelected(home));
        if (!save && (home.isBoot() || LiveSetting.isBoot())) ConfigEvent.boot();
    }

    private static class Loader {
        static volatile LiveConfig INSTANCE = new LiveConfig();
    }
}
