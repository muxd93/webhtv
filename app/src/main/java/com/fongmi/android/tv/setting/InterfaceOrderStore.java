package com.fongmi.android.tv.setting;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.source.SourceState;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InterfaceOrderStore {

    public static final String KEY_VOD = "interface_order_vod";
    public static final String KEY_VOD_HEALTH = "interface_health_vod";
    private static final Type STRING_LIST = new TypeToken<List<String>>() {}.getType();
    private static final Type HEALTH_MAP = new TypeToken<Map<String, HealthEntry>>() {}.getType();

    private InterfaceOrderStore() {
    }

    /** 健康采样（DEPOT2）：ok=false 的子源在容灾候选与首个加载源选择中沉底。 */
    public static class HealthSample {

        public final String url;
        public final boolean ok;
        public final long ts;
        public final long ms;

        public HealthSample(String url, boolean ok, long ts, long ms) {
            this.url = url;
            this.ok = ok;
            this.ts = ts;
            this.ms = ms;
        }
    }

    private static class HealthEntry {

        boolean ok;
        long ts;
        long ms;
    }

    @NonNull
    public static List<String> getVodOrder() {
        List<String> values = read();
        return dedupe(values);
    }

    public static void putVodOrder(@Nullable List<String> urls) {
        List<String> normalized = urls == null ? new ArrayList<>() : dedupe(urls);
        if (normalized.isEmpty()) Prefers.remove(KEY_VOD);
        else Prefers.put(KEY_VOD, App.gson().toJson(normalized));
    }

    public static void saveVodConfigs(@NonNull List<Config> configs) {
        List<String> order = new ArrayList<>();
        for (Config config : configs) if (config != null) order.add(config.getUrl());
        for (String url : getVodOrder()) if (!order.contains(url)) order.add(url);
        putVodOrder(order);
    }

    /** 记录子源健康采样（同 url 以最新为准）；仓展开预检时调用。 */
    public static void recordVodHealth(@Nullable List<HealthSample> samples) {
        if (samples == null || samples.isEmpty()) return;
        Map<String, HealthEntry> entries = readHealth();
        long now = System.currentTimeMillis();
        for (HealthSample sample : samples) {
            if (sample == null || isEmpty(sample.url)) continue;
            HealthEntry entry = new HealthEntry();
            entry.ok = sample.ok;
            entry.ts = sample.ts > 0 ? sample.ts : now;
            entry.ms = sample.ms;
            entries.put(sample.url, entry);
        }
        Prefers.put(KEY_VOD_HEALTH, App.gson().toJson(entries));
    }

    /** 健康布尔视图（无记录视为健康/未知；仅显式 false 沉底）。 */
    @NonNull
    public static Map<String, Boolean> getVodHealth() {
        Map<String, Boolean> result = new HashMap<>();
        for (Map.Entry<String, HealthEntry> item : readHealth().entrySet()) result.put(item.getKey(), item.getValue().ok);
        return result;
    }

    @NonNull
    private static Map<String, HealthEntry> readHealth() {
        String json = Prefers.getString(KEY_VOD_HEALTH, "{}");
        if (isEmpty(json)) return new HashMap<>();
        try {
            Map<String, HealthEntry> map = App.gson().fromJson(json, HEALTH_MAP);
            return map == null ? new HashMap<>() : map;
        } catch (Throwable ignored) {
            return new HashMap<>();
        }
    }

    @NonNull
    public static List<Config> sortVodConfigs(@NonNull List<Config> configs) {
        List<Config> usable = new ArrayList<>();
        for (Config config : configs) if (config != null && SourceState.isEnabled(config)) usable.add(config);
        List<String> available = new ArrayList<>();
        for (Config config : usable) available.add(config.getUrl());
        List<String> order = sortUrls(available, getVodOrder(), getVodHealth());
        List<Config> result = new ArrayList<>();
        for (String url : order) {
            Config match = find(usable, url);
            if (match != null && !contains(result, match)) result.add(match);
        }
        for (Config item : usable) if (!contains(result, item)) result.add(item);
        return result;
    }

    @Nullable
    public static Config findVodConfig(@NonNull List<Config> configs, @Nullable String url) {
        return find(configs, url);
    }

    @Nullable
    private static Config find(@NonNull List<Config> configs, @Nullable String url) {
        if (isEmpty(url)) return null;
        for (Config item : configs) if (Objects.equals(item.getUrl(), url)) return item;
        return null;
    }

    private static boolean contains(@NonNull List<Config> items, @NonNull Config target) {
        for (Config item : items) if (Objects.equals(item.getUrl(), target.getUrl())) return true;
        return false;
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }

    @NonNull
    private static List<String> read() {
        String json = Prefers.getString(KEY_VOD, "[]");
        if (isEmpty(json)) return new ArrayList<>();
        try {
            List<String> values = App.gson().fromJson(json, STRING_LIST);
            return values == null ? new ArrayList<>() : values;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    @NonNull
    private static List<String> dedupe(@NonNull List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) if (!isEmpty(value)) result.add(value);
        return new ArrayList<>(result);
    }

    @NonNull
    static List<String> normalize(@NonNull List<String> values) {
        return Collections.unmodifiableList(dedupe(values));
    }

    @NonNull
    static List<String> sortUrls(@NonNull List<String> available, @NonNull List<String> savedOrder) {
        List<String> result = new ArrayList<>();
        List<String> known = dedupe(available);
        for (String url : dedupe(savedOrder)) {
            if (known.contains(url) && !result.contains(url)) result.add(url);
        }
        for (String url : known) if (!result.contains(url)) result.add(url);
        return result;
    }

    /**
     * 健康感知排序（纯函数，供单测）：健康档为空时与双参版本一致；否则健康子源
     * （未知视为健康）按 savedOrder 稳定排序在前，显式不健康子源稳定排序在后。
     */
    @NonNull
    static List<String> sortUrls(@NonNull List<String> available, @NonNull List<String> savedOrder, @NonNull Map<String, Boolean> health) {
        if (health.isEmpty()) return sortUrls(available, savedOrder);
        List<String> healthy = new ArrayList<>(), unhealthy = new ArrayList<>();
        for (String url : dedupe(available)) (Boolean.FALSE.equals(health.get(url)) ? unhealthy : healthy).add(url);
        List<String> result = new ArrayList<>(sortUrls(healthy, savedOrder));
        result.addAll(sortUrls(unhealthy, savedOrder));
        return result;
    }
}
