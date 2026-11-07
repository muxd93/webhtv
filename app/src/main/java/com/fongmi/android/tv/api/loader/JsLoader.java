package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final SpiderRetryGuard retry;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        retry = new SpiderRetryGuard();
        loader = new Loader();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        retry.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        Spider cached = spiders.get(key);
        if (cached != null) return cached;
        long now = System.currentTimeMillis();
        if (retry.shouldSkip(key, now)) {
            SpiderDebug.log("JsLoader", "JS spider retry skip key=%s api=%s", key, api);
            return new SpiderNull();
        }
        Spider created = spiders.computeIfAbsent(key, k -> {
            try {
                Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
                spider.siteKey = key;
                spider.init(App.get(), ext);
                retry.succeeded(k);
                return spider;
            } catch (Throwable e) {
                SpiderDebug.log("JsLoader", "JS spider init failed: key=%s api=%s error=%s", key, api, e.getClass().getSimpleName() + ":" + e.getMessage());
                SpiderDebug.log("JsLoader", e);
                e.printStackTrace();
                retry.failed(k, System.currentTimeMillis());
                return null;
            }
        });
        return created == null ? new SpiderNull() : created;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
