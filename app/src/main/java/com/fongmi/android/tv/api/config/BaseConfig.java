package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
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

    private final AtomicInteger taskId = new AtomicInteger(0);

    protected boolean sync;
    protected volatile Config config;
    private volatile Future<?> future;
    private List<Header> pendingHeaders;
    private List<Proxy> pendingProxy;
    private List<String> pendingHosts;

    protected abstract String getTag();

    protected abstract int getType();

    protected abstract Config defaultConfig();

    protected abstract void load(Config config) throws Throwable;

    protected abstract boolean isLoaded();

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
        if (headers == null || headers.isEmpty()) return;
        this.pendingHeaders = headers;
        NetworkRules.setHeaders(getType(), headers);
        OkHttp.responseInterceptor().addAll(headers);
    }

    protected void setProxy(List<Proxy> proxy) {
        if (proxy == null || proxy.isEmpty()) return;
        this.pendingProxy = proxy;
        NetworkRules.setProxies(getType(), proxy);
        OkHttp.selector().addAll(proxy);
    }

    protected void setHosts(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) return;
        this.pendingHosts = hosts;
        NetworkRules.setHosts(getType(), hosts);
        OkHttp.dns().addAll(hosts);
    }

    /** 用本地缓存的配置正文同步渲染（无网络），成功回调一次 success；子类按格式覆写 */
    protected boolean loadFromCache(Config config, Callback callback) {
        return false;
    }

    /** 提交本类型网络规则快照并全量重建；加载失败时回滚到上次提交状态 */
    protected void commitRules() {
        NetworkRules.rebuild();
    }

    protected void rollbackRules() {
        NetworkRules.rebuild();
    }

    public void load(Callback callback) {
        beforeLoad();
        int id = taskId.incrementAndGet();
        if (future != null && !future.isDone()) future.cancel(true);
        future = Task.submit(() -> loadConfig(id, config, callback));
        callback.start();
    }

    protected void loadConfig(int id, Config config, Callback callback) {
        boolean cacheShown = false;
        try {
            Server.get().start();
            OkHttp.cancel(getTag());
            // L1：有缓存正文且尚未加载时，先同步渲染一次（弱网/断网也能立刻出内容）
            if (!isLoaded() && this.config != null && !TextUtils.isEmpty(this.config.getJson())) cacheShown = loadFromCache(this.config, callback);
            pendingHeaders = null;
            pendingProxy = null;
            pendingHosts = null;
            load(config);
            if (taskId.get() != id) return;
            // 激活 this.config 而非形参：多仓导入时 load() 内部会把 this.config 改写为选中的子源
            ConfigRepository.activate(this.config);
            commitRules();
            onLoadSuccess();
            App.post(() -> Notify.show(this.config.getNotice()));
            if (!cacheShown) App.post(callback::success);
        } catch (Throwable e) {
            e.printStackTrace();
            if (isCanceled(e)) return;
            if (taskId.get() != id) return;
            // 已注入过部分规则：回滚到上次提交的快照，避免半套规则残留
            if (pendingHeaders != null || pendingProxy != null || pendingHosts != null) rollbackRules();
            if (cacheShown) return;
            if (this.config == null || TextUtils.isEmpty(this.config.getUrl())) App.post(() -> callback.error(""));
            else App.post(() -> callback.error(Notify.getError(R.string.error_config_get, e)));
        } finally {
            if (taskId.get() == id) postEvent();
        }
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
