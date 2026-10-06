package com.fongmi.android.tv.live;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按频道记忆直播播放器偏好{内核,解码,画面比例}。键=频道名(与 Keep/Live.keep 的
 * "频道名即身份"一致,多源同台共享记忆);值=Prefers 单 key Gson map,延迟落盘,
 * LRU 上限 MAX_ITEMS。控制条调整只写当前频道记忆,全局默认(内核/比例)仍由设置页管理
 * ——记忆条目只在用户显式调过该频道时存在,无"等于默认即删"的歧义。
 * 存储结构镜像 SiteHealthStore;仅 leanback 接线,store 置于 main 供未来复用。
 */
public class ChannelPrefStore {

    private static final String KEY = "live_channel_pref";
    private static final long SAVE_DELAY = 2000;
    private static final int MAX_ITEMS = 500;
    private static final Type TYPE = new TypeToken<Map<String, Pref>>() {
    }.getType();
    private static final Runnable SAVE = () -> Task.execute(ChannelPrefStore::saveNow);

    private static final Map<String, Pref> items = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean dirty;

    public static class Pref {

        public int player = -1;
        public int decode = -1;
        public int scale = -1;
        long updatedAt;
    }

    @Nullable
    public static Pref find(String channel) {
        if (TextUtils.isEmpty(channel)) return null;
        synchronized (ChannelPrefStore.class) {
            ensureLoaded();
            Pref pref = items.get(channel);
            return pref == null ? null : copy(pref);
        }
    }

    /** 字段传 -1 表示保持该维度已有记忆不变。 */
    public static void update(String channel, int player, int decode, int scale) {
        if (TextUtils.isEmpty(channel)) return;
        synchronized (ChannelPrefStore.class) {
            ensureLoaded();
            Pref pref = items.get(channel);
            if (pref == null) items.put(channel, pref = new Pref());
            if (player != -1) pref.player = player;
            if (decode != -1) pref.decode = decode;
            if (scale != -1) pref.scale = scale;
            pref.updatedAt = System.currentTimeMillis();
            evict();
            markDirty();
        }
    }

    public static void flush() {
        App.removeCallbacks(SAVE);
        Task.execute(ChannelPrefStore::saveNow);
    }

    private static Pref copy(Pref pref) {
        Pref copy = new Pref();
        copy.player = pref.player;
        copy.decode = pref.decode;
        copy.scale = pref.scale;
        copy.updatedAt = pref.updatedAt;
        return copy;
    }

    private static void evict() {
        if (items.size() <= MAX_ITEMS) return;
        String oldest = null;
        long time = Long.MAX_VALUE;
        for (Map.Entry<String, Pref> entry : items.entrySet()) {
            if (entry.getValue().updatedAt < time) {
                time = entry.getValue().updatedAt;
                oldest = entry.getKey();
            }
        }
        if (oldest != null) items.remove(oldest);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Map<String, Pref> restored = App.gson().fromJson(Prefers.getString(KEY), TYPE);
            if (restored != null) items.putAll(restored);
        } catch (Throwable ignored) {
        }
    }

    private static void markDirty() {
        dirty = true;
        App.post(SAVE, SAVE_DELAY);
    }

    private static void saveNow() {
        Map<String, Pref> copy;
        synchronized (ChannelPrefStore.class) {
            ensureLoaded();
            if (!dirty) return;
            copy = new LinkedHashMap<>(items);
            dirty = false;
        }
        Prefers.put(KEY, App.gson().toJson(copy));
    }
}
