package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

abstract class BaseConfig {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    // 订阅源超龄刷新周期（小时）由 Setting.stale 控制（0 = 关闭自动刷新），默认 12h 对齐主流聚合源（iptv-api 等）

    private final AtomicInteger taskId = new AtomicInteger(0);

    protected boolean sync;
    protected volatile Config config;
    private volatile Future<?> future;
    // 当前内存状态对应的配置原文，用于静默刷新时的变化检测；clear() 时置空
    private volatile String loadedJson;
    // 内容未变化时抑制事件与公告，避免缓存先行后二次刷新导致首页重载、焦点跳动
    private volatile boolean suppressEvent;

    protected abstract String getTag();

    protected abstract Config defaultConfig();

    protected abstract boolean isLoaded();

    protected void invalidateJson() {
        loadedJson = null;
    }

    protected void beforeLoad() {
    }

    protected void onLoadSuccess() {
    }

    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            beforeLoad();
            if (config == null) config = defaultConfig();
            Server.get().start();
            load(config);
            onLoadSuccess();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    protected void postEvent() {
        ConfigEvent.common();
    }

    public boolean needSync(String url) {
        return sync || config == null || TextUtils.isEmpty(config.getUrl()) || url.equals(config.getUrl());
    }

    public Config getConfig() {
        return config == null ? defaultConfig() : config;
    }

    protected void setHeaders(List<Header> headers) {
        OkHttp.responseInterceptor().addAll(headers);
    }

    protected void setProxy(List<Proxy> proxy) {
        OkHttp.selector().addAll(proxy);
    }

    protected void setHosts(List<String> hosts) {
        OkHttp.dns().addAll(hosts);
    }

    public void load(Callback callback) {
        load(callback, false);
    }

    /** SWR 静默刷新等后台路径专用：失败直接报错，不进入故障转移轮次，避免后台静默切换接口。 */
    void loadSilent(Callback callback) {
        load(callback, true);
    }

    private void load(Callback callback, boolean silent) {
        beforeLoad();
        int id = taskId.incrementAndGet();
        if (future != null && !future.isDone()) future.cancel(true);
        suppressEvent = false;
        Config target = config == null ? defaultConfig() : config;
        future = Task.submit(() -> loadDispatch(id, target, callback, silent));
        callback.start();
    }

    /** 使当前加载失效；interrupt=false 只作代次失效，不打断在途请求。 */
    protected void cancelLoad(boolean interrupt) {
        taskId.incrementAndGet();
        if (interrupt) {
            if (future != null && !future.isDone()) future.cancel(true);
            OkHttp.cancel(getTag());
        }
    }

    /** 配置加载失败统一出口；VodConfig 覆写以接入接口故障转移轮次。 */
    void onConfigFailure(Config config, Callback callback, Throwable error) {
        App.post(() -> callback.error(Notify.getError(R.string.error_config_get, error)));
    }

    // 缓存判定在后台线程做，避免启动路径在主线程读文件
    private void loadDispatch(int id, Config config, Callback callback, boolean silent) {
        String cached = ConfigCache.get(config.getUrl());
        if (cached == null) loadConfig(id, config, callback, silent);
        else loadCachedConfig(id, config, cached, callback, silent);
    }

    /** 缓存先行：本地缓存立即渲染，成功后再静默拉网络；内容未变则不重复发事件。 */
    private void loadCachedConfig(int id, Config config, String cached, Callback callback, boolean silent) {
        try {
            Server.get().start();
            OkHttp.cancel(getTag());
            parseAndCache(config, cached);
            if (taskId.get() != id) return;
            onLoadSuccess();
            if (!suppressEvent) App.post(() -> Notify.show(config.getNotice()));
            App.post(callback::success);
        } catch (Throwable e) {
            e.printStackTrace();
            if (taskId.get() != id) return;
            // 缓存损坏：删掉后直接走网络路径，行为等同无缓存
            ConfigCache.delete(config.getUrl());
            suppressEvent = true;
            App.post(() -> loadFromNetwork(callback, false));
            return;
        } finally {
            if (taskId.get() == id && !suppressEvent) postEvent();
        }
        if (taskId.get() == id) App.post(() -> loadFromNetwork(new Callback(), true));
    }

    private void loadFromNetwork(Callback callback, boolean silent) {
        beforeLoad();
        int id = taskId.incrementAndGet();
        if (future != null && !future.isDone()) future.cancel(true);
        suppressEvent = false;
        Config target = config == null ? defaultConfig() : config;
        future = Task.submit(() -> loadConfig(id, target, callback, silent));
        callback.start();
    }

    protected void loadConfig(int id, Config config, Callback callback, boolean silent) {
        try {
            Server.get().start();
            OkHttp.cancel(getTag());
            load(config);
            if (taskId.get() != id) return;
            if (config.equals(this.config)) config.update();
            onLoadSuccess();
            if (!suppressEvent) App.post(() -> Notify.show(config.getNotice()));
            App.post(callback::success);
        } catch (Throwable e) {
            e.printStackTrace();
            if (isCanceled(e)) return;
            if (taskId.get() != id) return;
            if (TextUtils.isEmpty(config.getUrl())) {
                App.post(() -> callback.error(""));
                return;
            }
            if (silent) App.post(() -> callback.error(Notify.getError(R.string.error_config_get, e)));
            else onConfigFailure(config, callback, e);
        } finally {
            if (taskId.get() == id && !suppressEvent) postEvent();
        }
    }

    /** 网络拉取，失败时回退到上次成功的缓存内容；解析失败同样回退。 */
    protected void load(Config config) throws Throwable {
        try {
            parseAndCache(config, fetchJson(config));
            onFetched(config);
        } catch (Throwable e) {
            String cached = ConfigCache.get(config.getUrl());
            if (cached == null) throw e;
            if (cached.equals(loadedJson)) {
                suppressEvent = true;
                return;
            }
            SpiderDebug.log(getTag(), "config fetch failed, fallback to cache url=%s", config.getUrl());
            App.post(() -> Notify.show("网络异常，已加载缓存配置"));
            parseAndCache(config, cached);
        }
    }

    protected String fetchJson(Config config) throws Throwable {
        return Decoder.getJson(UrlUtil.convert(config.getUrl()), getTag());
    }

    /** 子类把 JSON 解析进内存状态；默认空实现供未迁移的子类（如 WallConfig）继续重写 load 使用。 */
    protected void parse(Config config, String json) throws Throwable {
    }

    /** 网络内容拉取并生效后回调（含内容未变的成功拉取）；回退缓存的失败路径不回调。 */
    protected void onFetched(Config config) {
        if (config == null || TextUtils.isEmpty(config.getUrl())) return;
        Prefers.put(fetchTsPrefix() + Util.md5(config.getUrl()), System.currentTimeMillis());
    }

    /** onFetched 时间戳键前缀；子类沿用历史键名（如 live_fetch_ts_）以保持既有数据兼容。 */
    protected String fetchTsPrefix() {
        return "fetch_ts_";
    }

    /** 内容超龄时静默重拉（SWR）：已有加载进行中则跳过，避免与启动加载重复拉网络；内容变化经既有事件生效。 */
    public void refreshIfStale() {
        try {
            int hours = Setting.getStale();
            if (hours <= 0) return;
            if (future != null && !future.isDone()) return;
            Config config = getConfig();
            if (config.isEmpty() || sync) return;
            if (!config.getUrl().startsWith("http")) return;
            long ts = Prefers.getLong(fetchTsPrefix() + Util.md5(config.getUrl()));
            if (ts > 0 && System.currentTimeMillis() - ts < hours * 3600_000L) return;
            App.post(() -> loadSilent(new Callback()));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void parseAndCache(Config config, String json) throws Throwable {
        String previous = loadedJson;
        if (json.equals(previous)) {
            ConfigCache.put(config.getUrl(), json);
            suppressEvent = true;
            return;
        }
        parse(config, json);
        // Depot 模式下 parse 内部会递归加载真实配置并刷新 loadedJson，此时以外层为准不覆盖
        if (loadedJson == previous) loadedJson = json;
        ConfigCache.put(config.getUrl(), json);
    }

    protected boolean isCanceled(Throwable e) {
        if ("Canceled".equals(e.getMessage())) return true;
        if (e instanceof InterruptedException) return true;
        if (e instanceof InterruptedIOException) return true;
        return e.getCause() instanceof InterruptedIOException;
    }

    protected JsonArray fetchArray(JsonObject object, String key) {
        if (!object.has(key)) return new JsonArray();
        JsonElement element = object.get(key);
        if (element.isJsonObject()) return new JsonArray();
        if (element.isJsonPrimitive()) element = fetch(element.getAsString());
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) result.addAll(fetch(item.getAsString()));
            else if (item.isJsonObject()) result.add(item);
        }
        return result;
    }

    private JsonArray fetch(String url) {
        try {
            JsonElement parsed = Json.parse(OkHttp.string(UrlUtil.convert(url)));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
