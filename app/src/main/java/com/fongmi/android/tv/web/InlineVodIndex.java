package com.fongmi.android.tv.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * inline 记录的 LRU 索引：容量上限 + 按记录 id 成组淘汰。
 * <p>
 * {@code URL_HEADERS} / {@code URL_EPISODES} 是按 episode url 存的两张表，同一条 inline 记录会占用多个 key。
 * 这里登记「记录 → 它占用的全部 key」，淘汰时把整套 key 交回调用方一起删除，
 * 保证不会留下「有 headers 没剧集」或「有剧集没 headers」的半条记录。
 * <p>
 * {@link #pin(String)} 标记当前正在播放/正在浏览的记录：它永远不被淘汰，容量因此最多超出 {@link #MAX} 一条。
 */
final class InlineVodIndex<T> {

    /** 8 条：详情页 → 播放 → 返回详情页 → 再播的流程远用不到 8 条，同时内存留在几十 KB 量级 */
    static final int MAX = 8;

    private final LinkedHashMap<String, T> items = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Set<String>> owned = new HashMap<>();
    private String pinned;

    synchronized T get(String id) {
        return id == null ? null : items.get(id);
    }

    synchronized void pin(String id) {
        if (id != null) pinned = id;
    }

    /**
     * 写入记录（同 id 覆盖写时一并换掉它占用的 key）并淘汰最旧的记录。
     *
     * @param urls 这条记录在两张 URL 表里占用的 key
     * @return 「被淘汰/被覆盖的记录 id → 可以删掉的 url」，调用方据此成组清掉两张 URL 表
     */
    synchronized Map<String, Set<String>> put(String id, T value, Collection<String> urls) {
        Map<String, Set<String>> changes = new LinkedHashMap<>();
        Set<String> previous = owned.put(id, new LinkedHashSet<>(urls));
        if (previous != null) {
            // 覆盖写：仍在新记录里使用的 key 保留，只交出作废的部分
            previous.removeAll(urls);
            Map<String, Set<String>> stale = exclusive(id, previous);
            if (!stale.isEmpty()) changes.putAll(stale);
        }
        items.put(id, value);
        Iterator<Map.Entry<String, T>> iterator = items.entrySet().iterator();
        while (items.size() > MAX && iterator.hasNext()) {
            String key = iterator.next().getKey();
            if (key.equals(id) || key.equals(pinned)) continue;
            iterator.remove();
            changes.putAll(exclusive(key, owned.remove(key)));
        }
        return changes;
    }

    /** 解析出来的直链 url 属于某条已有记录：登记回去，淘汰时一起消失 */
    synchronized void claim(String id, String url) {
        Set<String> keys = id == null ? null : owned.get(id);
        if (keys != null && url != null && !url.isEmpty()) keys.add(url);
    }

    /** url 归属的记录；播放时用来钉住正在播放的那条 */
    synchronized String ownerOf(String url) {
        if (url == null) return null;
        for (Map.Entry<String, Set<String>> entry : owned.entrySet()) {
            if (entry.getValue().contains(url)) return entry.getKey();
        }
        return null;
    }

    synchronized int size() {
        return items.size();
    }

    /** 只保留仍被其它记录独占的 key，避免删掉别人还要用的 url */
    private Map<String, Set<String>> exclusive(String id, Set<String> keys) {
        if (keys == null || keys.isEmpty()) return Collections.emptyMap();
        Set<String> result = new LinkedHashSet<>(keys);
        for (Set<String> other : owned.values()) result.removeAll(other);
        if (result.isEmpty()) return Collections.emptyMap();
        Map<String, Set<String>> changes = new HashMap<>();
        changes.put(id, result);
        return changes;
    }
}
