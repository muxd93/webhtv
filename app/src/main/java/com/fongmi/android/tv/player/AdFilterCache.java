package com.fongmi.android.tv.player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * URL 级净化清单缓存（QA1-A3）：同源地址一次净化多次复用，路由 token 幂等。
 * 命中未过期条目复用同一 token（不重新拉取、不重新提示）；poisoned 条目由
 * 回退路径标记，TTL 内同 URL 不再过滤、不再供数，防止"过滤→回退→重过滤"
 * 回环。过期惰性回收，容量 LRU 淘汰。无 Android 依赖，纯 JVM 可测。
 */
final class AdFilterCache {

    record Entry(String url, String content, int removed, String token, long createdAt, boolean poisoned) {

        boolean fresh(long now, long ttlMs) {
            return now - createdAt <= ttlMs;
        }
    }

    private final int capacity;
    private final long ttlMs;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Entry> entries;

    AdFilterCache(int capacity, long ttlMs) {
        this(capacity, ttlMs, System::currentTimeMillis);
    }

    AdFilterCache(int capacity, long ttlMs, LongSupplier clock) {
        this.capacity = capacity;
        this.ttlMs = ttlMs;
        this.clock = clock;
        this.entries = new LinkedHashMap<String, AdFilterCache.Entry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, AdFilterCache.Entry> eldest) {
                return size() > capacity;
            }
        };
    }

    /** 未过期的可用条目；poisoned 不算命中。 */
    synchronized Entry find(String url) {
        Entry entry = freshEntry(url);
        return entry == null || entry.poisoned() ? null : entry;
    }

    /** 未过期条目（含 poisoned），供调用方区分"中毒跳过"与"无条目可重新过滤"。 */
    synchronized Entry peek(String url) {
        return freshEntry(url);
    }

    synchronized Entry put(String url, String content, int removed, String token) {
        purge();
        Entry entry = new Entry(url, content, removed, token, clock.getAsLong(), false);
        entries.put(url, entry);
        return entry;
    }

    /** 幂等供数：token 在 TTL 内可重复响应；poisoned/过期/未知一律拒绝。 */
    synchronized Entry findByToken(String token) {
        if (token == null || token.isEmpty()) return null;
        long now = clock.getAsLong();
        for (Entry entry : entries.values()) {
            if (!entry.token().equals(token)) continue;
            return !entry.fresh(now, ttlMs) || entry.poisoned() ? null : entry;
        }
        return null;
    }

    /** 路由播放失败回退时调用：仅标记既有未过期条目，TTL 后可重新过滤。 */
    synchronized void poison(String url) {
        Entry entry = entries.get(url);
        if (entry == null || !entry.fresh(clock.getAsLong(), ttlMs) || entry.poisoned()) return;
        entries.put(url, new Entry(entry.url(), entry.content(), entry.removed(), entry.token(), entry.createdAt(), true));
    }

    synchronized void invalidate(String url) {
        entries.remove(url);
    }

    private Entry freshEntry(String url) {
        Entry entry = entries.get(url);
        if (entry == null) return null;
        if (!entry.fresh(clock.getAsLong(), ttlMs)) {
            entries.remove(url);
            return null;
        }
        return entry;
    }

    private void purge() {
        long now = clock.getAsLong();
        entries.values().removeIf(entry -> !entry.fresh(now, ttlMs));
    }
}
