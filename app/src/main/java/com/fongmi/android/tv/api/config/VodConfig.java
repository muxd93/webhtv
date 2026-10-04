package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.CspWarmup;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.setting.InterfaceFailoverPolicy;
import com.fongmi.android.tv.setting.InterfaceFailoverState;
import com.fongmi.android.tv.setting.InterfaceOrderStore;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.github.catvod.bean.Doh;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Json;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    private volatile FailoverRound failoverRound;
    private volatile AlertDialog failoverDialog;

    public static VodConfig get() {
        return Loader.INSTANCE;
    }

    public static int getCid() {
        return get().getConfig().getId();
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    /**
     * 配置（站点列表）是否已就绪。老人模式点击收藏/历史卡片依赖此状态，
     * 未就绪时若直接拉起播放会因找不到站点而失败。
     */
    public static boolean isReady() {
        return get().isLoaded();
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
        get().abandonFailover();
        get().clear().config(config).load(callback);
    }

    /** 外部终止进行中的容灾轮次（如设置页把模式切到关闭）。 */
    public static void cancelFailover() {
        get().stopFailover();
    }

    @Override
    public void load(Callback callback) {
        abandonFailover();
        super.load(callback);
    }

    @Override
    void loadSilent(Callback callback) {
        // SWR 静默刷新会作废在途加载（taskId++ + 取消 future）：先终止进行中的容灾轮次，
        // 否则轮次终态永不到达，VOD 事件抑制通道被无限期占用
        abandonFailover();
        super.loadSilent(callback);
    }

    public VodConfig init() {
        return config(Config.vod());
    }

    public VodConfig config(Config config) {
        this.config = config;
        return this;
    }

    public VodConfig clear() {
        invalidateJson();
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
    protected String fetchTsPrefix() {
        return "vod_fetch_ts_";
    }

    @Override
    protected Config defaultConfig() {
        return Config.vod();
    }

    @Override
    protected void postEvent() {
        // 轮次进行中抑制 VOD 事件：中间候选失败不得触发首页重建，终态由 finish* 统一补发
        if (failoverRound != null) return;
        super.postEvent();
        ConfigEvent.vod();
    }

    @Override
    protected void parse(Config config, String json) throws Throwable {
        checkJson(config, Json.parse(json).getAsJsonObject());
        // 与源分叉一致：解析成功但站点为空视为加载失败。在 super.load 的 try 保护内抛出，
        // 网络返回异常 payload 时仍可回退到缓存内容，而不是跳过回退直接报错/切源
        if (!isLoaded()) throw new Exception("VOD sites is empty");
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
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, VOD));
        if (configs.isEmpty()) throw new Exception("Depot urls is empty");
        // 与直播多仓（LIVE4）对齐：全部子源落库进历史供切换，仅加载第一个；点播不做跨源聚合
        if (configs.size() > 1) App.post(() -> Notify.show(ResUtil.getString(R.string.vod_depot_imported, configs.size())));
        load(this.config = configs.get(0));
        Config.delete(config.getUrl());
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

    void onConfigFailure(Config config, Callback callback, Throwable error) {
        FailoverRound current = failoverRound;
        String message = Notify.getError(R.string.error_config_get, error);
        if (current != null && callback == current.attemptCallback) {
            current.lastError = message;
            App.post(() -> onAttemptFailure(current));
            return;
        }

        int mode = Setting.getInterfaceFailoverMode();
        if (!InterfaceFailoverPolicy.shouldFailover(mode)) {
            App.post(() -> callback.error(message));
            return;
        }

        List<Config> configs = InterfaceOrderStore.sortVodConfigs(Config.getAll(VOD));
        String originUrl = config.getUrl();
        int index = indexOfUrl(configs, originUrl);
        int limit = InterfaceFailoverPolicy.fallbackLimit(configs.size());
        List<Config> remaining = new ArrayList<>();
        for (int i = Math.max(index + 1, 0); i < configs.size() && remaining.size() < limit; i++) {
            Config candidate = configs.get(i);
            if (!TextUtils.equals(candidate.getUrl(), originUrl)) remaining.add(candidate);
        }
        if (remaining.isEmpty()) {
            App.post(() -> callback.error(message));
            return;
        }

        FailoverRound round = new FailoverRound(config.getDesc(), remaining, callback, message,
                new InterfaceFailoverState(mode, originUrl, urls(remaining)));
        failoverRound = round;
        if (InterfaceFailoverPolicy.isConfirm(mode)) {
            App.post(() -> showConfirmDialog(round));
        } else {
            App.post(this::startNextAttempt);
        }
    }

    private void onAttemptFailure(FailoverRound round) {
        if (failoverRound != round) return;
        if (!InterfaceFailoverPolicy.shouldFailover(Setting.getInterfaceFailoverMode())
                || !round.state.shouldContinueAfterFailure()) {
            finishFailure(round, round.lastError);
            return;
        }
        startNextAttempt();
    }

    private void startNextAttempt() {
        FailoverRound round = failoverRound;
        if (round == null) return;
        if (!InterfaceFailoverPolicy.shouldFailover(Setting.getInterfaceFailoverMode())) {
            finishFailure(round, round.lastError);
            return;
        }
        String nextUrl = round.state.nextAutomatic();
        if (nextUrl == null) {
            finishFailure(round, round.lastError);
            return;
        }
        Config next = findCandidate(round.candidates, nextUrl);
        if (next == null) {
            finishFailure(round, round.lastError);
            return;
        }
        Notify.show(ResUtil.getString(R.string.interface_failover_next, next.getDesc()));
        round.attemptCallback = new Callback() {
            @Override
            public void success() {
                if (round.attemptCallback != this) return;
                finishSuccess(round);
            }

            @Override
            public void error(String msg) {
                // 常规失败统一经 BaseConfig.onConfigFailure 回流（callback 身份匹配后走 onAttemptFailure）
                if (round.attemptCallback != this) return;
                round.lastError = msg;
                App.post(() -> {
                    if (round.attemptCallback == this) onAttemptFailure(round);
                });
            }
        };
        loadFailoverAttempt(next, round.attemptCallback);
    }

    private void loadFailoverAttempt(Config config, Callback callback) {
        clear().config(config);
        super.load(callback);
    }

    private void finishSuccess(FailoverRound round) {
        if (failoverRound != round) return;
        failoverRound = null;
        super.postEvent();
        ConfigEvent.vod();
        round.callback.success();
    }

    private void finishFailure(FailoverRound round, String message) {
        if (failoverRound != round) return;
        failoverRound = null;
        super.postEvent();
        ConfigEvent.vod();
        round.callback.error(errorMessage(message));
    }

    private String errorMessage(String message) {
        return TextUtils.isEmpty(message)
                ? Notify.getError(R.string.error_config_get, new Exception("Configuration get failed"))
                : message;
    }

    private void abandonFailover() {
        if (failoverRound != null) {
            failoverRound.state.cancel();
            cancelLoad(false);
        }
        failoverRound = null;
        AlertDialog dialog = failoverDialog;
        failoverDialog = null;
        if (dialog != null) App.post(dialog::dismiss);
    }

    /** 用户在容灾确认弹窗中选定的候选开始尝试；一轮只允许一次选择。 */
    private void startSelectedAttempt(FailoverRound round, int index) {
        if (failoverRound != round || index < 0 || index >= round.candidates.size()) return;
        if (!InterfaceFailoverPolicy.shouldFailover(Setting.getInterfaceFailoverMode())) {
            finishFailure(round, round.lastError);
            return;
        }
        Config selected = round.candidates.get(index);
        String selectedUrl = round.state.select(index);
        if (selectedUrl == null) {
            finishFailure(round, round.lastError);
            return;
        }
        Notify.show(ResUtil.getString(R.string.interface_failover_next, selected.getDesc()));
        round.attemptCallback = new Callback() {
            @Override
            public void success() {
                if (round.attemptCallback != this) return;
                finishSuccess(round);
            }

            @Override
            public void error(String msg) {
                if (round.attemptCallback != this) return;
                round.lastError = msg;
                App.post(() -> {
                    if (round.attemptCallback == this) onAttemptFailure(round);
                });
            }
        };
        loadFailoverAttempt(selected, round.attemptCallback);
    }

    private void showConfirmDialog(FailoverRound round) {
        if (failoverRound != round) return;
        android.app.Activity activity = App.activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            finishFailure(round, round.lastError);
            return;
        }
        CharSequence[] labels = new CharSequence[round.candidates.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = round.candidates.get(i).getDesc();
        final int[] selected = {0};
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.interface_failover_title)
                .setMessage(ResUtil.getString(R.string.interface_failover_message, round.originDesc))
                .setSingleChoiceItems(labels, 0, (dialog1, which) -> selected[0] = which)
                .setPositiveButton(R.string.interface_failover_switch, (dialog1, which) -> startSelectedAttempt(round, selected[0]))
                .setNegativeButton(R.string.dialog_cancel, (dialog1, which) -> cancelFailover(round))
                .create();
        failoverDialog = dialog;
        dialog.setOnCancelListener(dialog1 -> cancelFailover(round));
        dialog.setOnDismissListener(dialog1 -> {
            if (failoverDialog == dialog) failoverDialog = null;
        });
        dialog.show();
    }

    private void cancelFailover(FailoverRound round) {
        if (failoverRound != round) return;
        cancelRound(round);
        Notify.show(R.string.interface_failover_cancelled);
    }

    private void stopFailover() {
        FailoverRound round = failoverRound;
        if (round == null) return;
        round.state.cancel();
        if (round.attemptCallback != null) cancelLoad(true);
        failoverRound = null;
        AlertDialog dialog = failoverDialog;
        failoverDialog = null;
        if (dialog != null) dialog.dismiss();
        round.callback.error(errorMessage(round.lastError));
    }

    private void cancelRound(FailoverRound round) {
        if (failoverRound != round) return;
        round.state.cancel();
        if (round.attemptCallback != null) cancelLoad(true);
        failoverRound = null;
        AlertDialog dialog = failoverDialog;
        failoverDialog = null;
        if (dialog != null) dialog.dismiss();
        round.callback.error(errorMessage(round.lastError));
    }

    private int indexOfUrl(List<Config> configs, String url) {
        for (int i = 0; i < configs.size(); i++) if (TextUtils.equals(configs.get(i).getUrl(), url)) return i;
        return -1;
    }

    private List<String> urls(List<Config> configs) {
        List<String> urls = new ArrayList<>();
        for (Config item : configs) urls.add(item.getUrl());
        return urls;
    }

    private Config findCandidate(List<Config> configs, String url) {
        for (Config item : configs) if (TextUtils.equals(item.getUrl(), url)) return item;
        return null;
    }

    private static final class FailoverRound {

        private final String originDesc;
        private final List<Config> candidates;
        private final Callback callback;
        private final InterfaceFailoverState state;
        private String lastError;
        private Callback attemptCallback;

        private FailoverRound(String originDesc, List<Config> candidates, Callback callback, String lastError,
                              InterfaceFailoverState state) {
            this.originDesc = originDesc;
            this.candidates = candidates;
            this.callback = callback;
            this.lastError = lastError;
            this.state = state;
        }
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
        Config temp = Config.find(config, LIVE).save();
        boolean sync = LiveConfig.get().needSync(config.getUrl());
        if (sync) LiveConfig.get().config(temp.update()).parse(object);
    }

    private void initWall(Config config, JsonObject object) {
        if (Json.isEmpty(object, "wallpaper")) return;
        this.wall = Json.safeString(object, "wallpaper");
        Config temp = Config.find(wall, config.getName(), WALL).save();
        boolean sync = WallConfig.get().needSync(wall);
        if (sync) WallConfig.get().config(temp.update());
    }

    private void initSite(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        try {
            BaseLoader.get().parseJar(spider, true);
        } catch (Throwable e) {
            // jar 失败不阻断配置本体生效：站点列表照常渲染，依赖该 jar 的站点运行时再报错
            SpiderDebug.log(TAG, "spider jar load failed url=%s error=%s", config.getUrl(), e.getMessage());
            App.post(() -> Notify.show(R.string.jar_failed));
        }
        setSites(Json.safeListElement(object, "sites").stream().map(e -> Site.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new)));
        Map<String, Site> items = Site.findAll().stream().collect(Collectors.toMap(Site::getKey, Function.identity()));
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
        this.doh = doh;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
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
        this.ads = ads;
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
