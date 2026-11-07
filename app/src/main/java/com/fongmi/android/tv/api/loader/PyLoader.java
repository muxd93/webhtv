package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final SpiderRetryGuard retry;
    private final Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
        retry = new SpiderRetryGuard();
        loader = new Loader();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        retry.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        Spider cached = spiders.get(key);
        if (cached != null) return cached;
        long now = System.currentTimeMillis();
        if (retry.shouldSkip(key, now)) {
            SpiderDebug.log("PyLoader", "python spider retry skip key=%s api=%s", key, api);
            return new SpiderNull();
        }
        Spider created = spiders.computeIfAbsent(key, k -> {
            try {
                Spider spider = loader.spider(api);
                spider.siteKey = key;
                spider.init(App.get(), normalizeExt(ext));
                retry.succeeded(k);
                return spider;
            } catch (Throwable e) {
                SpiderDebug.log("PyLoader", "python spider init failed key=%s api=%s error=%s", key, api, e.getClass().getSimpleName() + ":" + e.getMessage());
                SpiderDebug.log("PyLoader", e);
                e.printStackTrace();
                retry.failed(k, System.currentTimeMillis());
                return null;
            }
        });
        return created == null ? new SpiderNull() : created;
    }

    private String normalizeExt(String ext) {
        String value = TextUtils.isEmpty(ext) ? "" : ext.trim();
        // Many live Python spiders treat ext as an option object and call .get().
        // TV-style configs commonly express an empty extension as [], which would
        // otherwise deserialize to a list and crash those spiders during init.
        return "[]".equals(value) ? "{}" : ext;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
