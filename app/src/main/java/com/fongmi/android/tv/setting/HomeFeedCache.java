package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 首页内容缓存（L2）：按 配置URL|站点key 缓存最近一次首页 Result，
 * 冷启动先用缓存渲染、网络返回后静默替换。复刻 SiteHealthStore 的持久化模式：
 * 有界 LinkedHashMap + TTL 淘汰 + 防抖保存。
 */
public class HomeFeedCache {

    private static final String KEY = "home_feed_v1";
    private static final long SAVE_DELAY = 2000;
    private static final long TTL_MS = TimeUnit.MINUTES.toMillis(15);
    private static final int MAX_ENTRIES = 20;
    private static final Type TYPE = new TypeToken<Map<String, Entry>>() {}.getType();
    private static final Runnable SAVE = () -> Task.execute(HomeFeedCache::saveNow);

    private static final Map<String, Entry> items = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean dirty;

    private static class Entry {

        private long time;
        private String json;
    }

    public static Result get(String configUrl, String siteKey) {
        synchronized (HomeFeedCache.class) {
            ensureLoaded();
            String key = key(configUrl, siteKey);
            Entry entry = items.get(key);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.time > TTL_MS) {
                items.remove(key);
                markDirty();
                return null;
            }
            try {
                Result result = Result.objectFrom(entry.json);
                return isEmpty(result) ? null : result;
            } catch (Throwable e) {
                return null;
            }
        }
    }

    public static void put(String configUrl, String siteKey, Result result) {
        if (isEmpty(result)) return;
        synchronized (HomeFeedCache.class) {
            ensureLoaded();
            Entry entry = new Entry();
            entry.time = System.currentTimeMillis();
            entry.json = App.gson().toJson(result);
            items.put(key(configUrl, siteKey), entry);
            prune();
            markDirty();
        }
    }

    private static boolean isEmpty(Result result) {
        return result == null || (result.getList().isEmpty() && result.getTypes().isEmpty());
    }

    private static String key(String configUrl, String siteKey) {
        return (configUrl == null ? "" : configUrl) + "|" + (siteKey == null ? "" : siteKey);
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        items.values().removeIf(entry -> now - entry.time > TTL_MS);
        while (items.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<String, Entry>> iterator = items.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static void markDirty() {
        dirty = true;
        App.removeCallbacks(SAVE);
        App.post(SAVE, SAVE_DELAY);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        String raw = Prefers.getString(KEY, "");
        if (raw.isEmpty()) return;
        try {
            Map<String, Entry> result = App.gson().fromJson(raw, TYPE);
            if (result != null) items.putAll(result);
        } catch (Exception e) {
            items.clear();
        }
    }

    private static void saveNow() {
        synchronized (HomeFeedCache.class) {
            if (!dirty) return;
            dirty = false;
            Prefers.put(KEY, App.gson().toJson(items));
        }
    }
}
