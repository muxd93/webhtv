package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.CspWarmup;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.github.catvod.bean.Doh;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class VodConfig extends BaseConfig {

    private static final String TAG = VodConfig.class.getSimpleName();

    private Site home;
    private String wall;
    private Parse parse;
    private List<Doh> doh;
    private List<Rule> rules;
    private List<Site> sites;
    private List<String> ads;
    private List<String> flags;
    private List<Parse> parses;

    public static VodConfig get() {
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

    public static int getHomeIndex() {
        return get().getSites().indexOf(get().getHome());
    }

    public static boolean hasParse() {
        return !get().getParses().isEmpty();
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public VodConfig init() {
        return config(Config.vod());
    }

    public VodConfig config(Config config) {
        this.config = config;
        return this;
    }

    public VodConfig clear() {
        ads = null;
        doh = null;
        home = null;
        wall = null;
        parse = null;
        sites = null;
        flags = null;
        rules = null;
        parses = null;
        WebHomeExtensionRegistry.get().setGlobalSources(null, "");
        BaseLoader.get().clear();
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected int getType() {
        return VOD;
    }

    @Override
    protected Config defaultConfig() {
        return Config.vod();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.vod();
    }

    @Override
    protected void load(Config config) throws Throwable {
        String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
        checkJson(config, Json.parse(json).getAsJsonObject());
        config.setJson(json);
    }

    @Override
    protected boolean loadFromCache(Config config, Callback callback) {
        try {
            JsonObject object = Json.parse(config.getJson()).getAsJsonObject();
            if (object.has("urls") || object.has("msg")) return false;
            checkJson(config, object);
            App.post(callback::success);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    protected boolean isLoaded() {
        return !getSites().isEmpty();
    }

    @Override
    protected void beforeLoad() {
        CspWarmup.reset();
    }

    @Override
    protected void onLoadSuccess() {
        CspWarmup.schedule("vod-config-loaded");
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
        // 多仓入口本身保留为 Config(kind=DEPOT)，子源平铺但可溯源、可整组刷新/删除
        // 复用已落库的多仓入口行，避免整行覆盖清掉 json/time/active
        Config depot = ConfigRepository.upsert(DepotRow.resolve(ConfigRepository.findByUrl(config.getUrl(), VOD), config.getUrl(), VOD, config.getName(), Config.DEPOT));
        List<Config> children = ConfigService.registerDepot(depot, items);
        boolean[] active = new boolean[children.size()];
        for (int i = 0; i < active.length; i++) active[i] = children.get(i).isActive();
        // 首个 active（无 active 时第一个）优先，失败才按 urls 顺序顺延，全部失败才抛给上层报错
        int selected = DepotFallback.run(active, this::isCanceled, index -> {
            Config target = children.get(index);
            SpiderDebug.log(TAG, "depot child try name=%s url=%s active=%s", target.getName(), target.getUrl(), target.isActive());
            try {
                load(this.config = target);
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "depot child skip name=%s url=%s stage=%s error=%s", target.getName(), target.getUrl(), DepotFallback.stage(e), String.valueOf(e.getMessage()));
                throw e;
            }
        });
        SpiderDebug.log(TAG, "depot child selected name=%s url=%s", children.get(selected).getName(), children.get(selected).getUrl());
    }

    private void parseConfig(Config config, JsonObject object) {
        CustomCspSetting.inject(object);
        initList(object);
        initLive(config, object);
        initWall(config, object);
        initSite(config, object);
        initParse(config, object);
        WebHomeExtensionRegistry.get().setGlobalSources(object.get("webHomeExtensions"), config.getUrl());
        config.setLogo(Json.safeString(object, "logo"));
        config.setNotice(Json.safeString(object, "notice"));
        config.setDanmaku(Json.safeString(object, "danmaku"));
    }

    private void initList(JsonObject object) {
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setDoh(Doh.arrayFrom(fetchArray(object, "doh")));
        setFlags(Json.safeListString(object, "flags"));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initLive(Config config, JsonObject object) {
        if (Json.isEmpty(object, "lives")) return;
        // 多仓子源：直播侧指向多仓本体，打开直播时聚合全部子仓直播源（无需切单仓）
        Config depot = ConfigRepository.findParentDepot(config);
        String url = depot != null ? depot.getUrl() : config.getUrl();
        String name = depot != null ? depot.getName() : config.getName();
        // 复用已落库的直播行：整行新建会让 upsert 清掉 json/time/active 等缓存字段
        Config temp = ConfigRepository.upsert(liveRow(ConfigRepository.findByUrl(url, LIVE), url, name));
        if (depot != null) {
            // 用户手选了独立直播源（既不是这个多仓、也不跟随点播）：不覆盖、也不清空已解析的直播内容
            if (!LiveConfig.get().needSync(temp.getUrl())) return;
            Config current = LiveConfig.get().getConfig();
            boolean same = current != null && !current.isEmpty() && current.getUrl().equals(temp.getUrl());
            LiveConfig.get().config(ConfigRepository.activate(temp));
            if (!same) LiveConfig.get().clear();
            return;
        }
        boolean sync = LiveConfig.get().needSync(url);
        if (sync) LiveConfig.get().config(ConfigRepository.activate(temp)).parse(object);
    }

    private void initWall(Config config, JsonObject object) {
        if (Json.isEmpty(object, "wallpaper")) return;
        this.wall = Json.safeString(object, "wallpaper");
        Config temp = ConfigRepository.upsert(wallRow(ConfigRepository.findByUrl(wall, WALL), wall, config.getName()));
        boolean sync = WallConfig.get().needSync(wall);
        if (sync) WallConfig.get().config(ConfigRepository.activate(temp));
    }

    /** 直播行：已有行只更新 url/name，缓存字段与 kind 原样保留（不降级已登记的多仓） */
    static Config liveRow(Config existing, String url, String name) {
        return DepotRow.resolve(existing, url, LIVE, name, Config.SINGLE);
    }

    /** 壁纸行：同上，避免每次加载点播配置都清掉壁纸行的 json/time */
    static Config wallRow(Config existing, String url, String name) {
        return DepotRow.resolve(existing, url, WALL, name, Config.SINGLE);
    }

    private void initSite(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        BaseLoader.get().parseJar(spider, true);
        setSites(Json.safeListElement(object, "sites").stream().map(e -> Site.objectFrom(e, spider).sourceId(config.getId())).distinct().collect(Collectors.toCollection(ArrayList::new)));
        Map<String, Site> items = Site.findBySource(config.getId()).stream().collect(Collectors.toMap(Site::getKey, Function.identity()));
        getSites().forEach(site -> site.sync(items.get(site.getKey())));
        CustomCspSetting.Result custom = CustomCspSetting.inject(getSites());
        Site home = !custom.home().isEmpty() ? custom.home() : getSites().stream().filter(item -> item.getKey().equals(config.getHome())).findFirst().orElse(getSites().isEmpty() ? new Site() : getSites().get(0));
        setHome(config, home, false);
    }

    private void initParse(Config config, JsonObject object) {
        setParses(Json.safeListElement(object, "parses").stream().map(Parse::objectFrom).distinct().collect(Collectors.toCollection(ArrayList::new)));
        setParse(config, getParses().isEmpty() ? new Parse() : getParses().stream().filter(item -> item.getName().equals(config.getParse())).findFirst().orElse(getParses().get(0)), false);
    }

    public List<Site> getSites() {
        return sites == null ? Collections.emptyList() : sites;
    }

    private void setSites(List<Site> sites) {
        this.sites = sites;
    }

    public List<Parse> getParses() {
        return parses == null ? Collections.emptyList() : parses;
    }

    private void setParses(List<Parse> parses) {
        if (!parses.isEmpty()) parses.add(0, Parse.god());
        this.parses = parses;
    }

    public List<Doh> getDoh() {
        List<Doh> items = Doh.get(App.get());
        if (doh == null) return items;
        items.removeAll(doh);
        items.addAll(doh);
        return items;
    }

    private void setDoh(List<Doh> doh) {
        // doh 入规则池：返回自身 + 池中其他仓的并集（DNS 解析器，全局并集不影响内容）
        this.doh = RulePool.mergeDoh(doh);
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = RulePool.mergeRules(rules);
        RuleConfig.get().invalidate();
    }

    public List<Parse> getParses(int type) {
        return getParses().stream().filter(item -> item.getType() == type).toList();
    }

    public List<Parse> getParses(int type, String flag) {
        List<Parse> items = getParses(type);
        List<Parse> filter = items.stream().filter(item -> item.getExt().getFlag().contains(flag)).toList();
        return filter.isEmpty() ? items : filter;
    }

    public List<String> getFlags() {
        return flags == null ? Collections.emptyList() : flags;
    }

    private void setFlags(List<String> flags) {
        this.flags = flags;
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = RulePool.mergeAds(ads);
        RuleConfig.get().invalidate();
    }

    public Parse getParse() {
        return parse == null ? new Parse() : parse;
    }

    public void setParse(Parse parse) {
        setParse(getConfig(), parse, true);
    }

    public Site getHome() {
        return home == null ? new Site() : home;
    }

    public void setHome(Site site) {
        setHome(getConfig(), site, true);
        RefreshEvent.home();
    }

    public String getWall() {
        return TextUtils.isEmpty(wall) ? "" : wall;
    }

    public Parse getParse(String name) {
        return getParses().stream().filter(item -> item.getName().equals(name)).findFirst().orElse(new Parse());
    }

    public Site getSite(String key) {
        return getSites().stream().filter(item -> item.getKey().equals(key)).findFirst().orElse(new Site());
    }

    private void setParse(Config config, Parse parse, boolean save) {
        this.parse = parse;
        this.parse.setSelected(true);
        config.setParse(parse.getName());
        getParses().forEach(item -> item.setSelected(parse));
        if (save) config.save();
    }

    private void setHome(Config config, Site site, boolean save) {
        home = site;
        home.setSelected(true);
        config.setHome(home.getKey());
        if (save) config.save();
        getSites().forEach(item -> item.setSelected(home));
    }

    private static class Loader {
        static volatile VodConfig INSTANCE = new VodConfig();
    }
}
