package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.setting.InterfaceOrderStore;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.source.SourceState;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LiveConfig extends BaseConfig {

    private static final String TAG = LiveConfig.class.getSimpleName();

    private Live home;
    private List<Live> lives;
    private List<Rule> rules;
    private List<String> ads;
    /** 同仓容灾已尝试的源（SRCUI6/S4）：成功渲染后重置，防止循环重试。 */
    private final List<String> triedUrls = new ArrayList<>();

    public static LiveConfig get() {
        return Loader.INSTANCE;
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
        invalidateJson();
        ads = null;
        home = null;
        lives = null;
        rules = null;
        triedUrls.clear();
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected String fetchTsPrefix() {
        // 直播配置的拉取时间戳命名空间（ConfigCache 缓存优先策略共用）
        return "live_fetch_ts_";
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
        super.load(config);
    }

    @Override
    protected void parse(Config config, String json) throws Throwable {
        if (Json.isObj(json)) checkJson(config, Json.parse(json).getAsJsonObject());
        else parseText(config, json);
    }

    @Override
    protected boolean isLoaded() {
        return !getLives().isEmpty() && !getHome().getGroups().isEmpty();
    }

    @Override
    protected void onLoadSuccess() {
        super.onLoadSuccess();
        triedUrls.clear(); // 成功渲染后重置同仓容灾预算（SRCUI6/S4）
    }

    /** 同仓容灾（SRCUI6/S4）：仅"同仓兄弟子源 + 加载阶段 + 非 sync"自动重试；跨仓/独立源/sync 走既有回退与提示。 */
    @Override
    protected void onConfigFailure(Config config, Callback callback, Throwable error) {
        if (!sync && config != null && !TextUtils.isEmpty(config.getParentUrl())) {
            Config sibling = nextSibling(config);
            if (sibling != null) {
                triedUrls.add(config.getUrl());
                App.post(() -> Notify.show(ResUtil.getString(R.string.live_failover_next, config.getDesc(), sibling.getDesc())));
                config(sibling).load(callback);
                return;
            }
        }
        super.onConfigFailure(config, callback, error);
    }

    /** 同仓下一个候选：同 parentUrl、未试过、未禁用，持久健康优先 + 仓内声明顺序；耗尽返回 null。 */
    private Config nextSibling(Config failed) {
        List<Config> siblings = Config.getChildren(failed.getParentUrl(), failed.getType());
        Map<String, Boolean> health = InterfaceOrderStore.getHealth(failed.getType());
        List<Config> healthy = new ArrayList<>(), unhealthy = new ArrayList<>();
        for (Config child : siblings) {
            if (TextUtils.equals(child.getUrl(), failed.getUrl()) || triedUrls.contains(child.getUrl())) continue;
            if (SourceState.isDisabled(child.getType(), child.getUrl())) continue;
            (Boolean.FALSE.equals(health.get(child.getUrl())) ? unhealthy : healthy).add(child);
        }
        if (healthy.isEmpty() && unhealthy.isEmpty()) return null;
        healthy.addAll(unhealthy);
        return healthy.get(0);
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
        Live live = new Live(UrlUtil.getName(config.getUrl()), config.getUrl()).sync();
        lives = new ArrayList<>(List.of(live));
        LiveParser.text(live, text);
        finishLive(config, "");
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
        // 与 VodConfig 共用 BaseConfig.expandDepot：落库子源、对账、并发探测、首个健康子源
        Expansion ex = expandDepot(config, object, LIVE);
        InterfaceOrderStore.recordLiveHealth(samples(ex.probe));
        load(this.config = ex.chosen);
        this.config.update();
    }

    private List<InterfaceOrderStore.HealthSample> samples(List<DepotProbe.Result> probe) {
        List<InterfaceOrderStore.HealthSample> samples = new ArrayList<>();
        for (DepotProbe.Result result : probe) samples.add(new InterfaceOrderStore.HealthSample(result.url, result.ok, System.currentTimeMillis(), result.latency));
        return samples;
    }

    private void parseConfig(Config config, JsonObject object) {
        clearStaleDepot(config);
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
        setLives(Json.safeListElement(object, "lives").stream().map(e -> Live.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new)));
        finishLive(config, spider);
    }

    private void finishLive(Config config, String spider) {
        CustomCspSetting.inject(getLives(), spider);
        getLives().removeIf(Live::isEmpty);
        Map<String, Live> items = Live.findAll().stream().collect(Collectors.toMap(Live::getName, Function.identity()));
        getLives().forEach(live -> live.sync(items.get(live.getName())));
        setHome(config, getLives().isEmpty() ? new Live() : getLives().stream().filter(item -> item.getName().equals(config.getHome())).findFirst().orElse(getLives().get(0)), false);
    }

    public void setKeep(Channel channel) {
        if (home != null && !channel.getGroup().isHidden()) home.keep(channel).save();
    }

    public void applyKeepsToGroups(List<Group> items) {
        // 收藏频道只能并入首位的收藏组；来源自带「收藏」组时 parse 走早退路径，不会进入这里
        if (items.isEmpty() || !items.get(0).isKeep()) return;
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
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
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
