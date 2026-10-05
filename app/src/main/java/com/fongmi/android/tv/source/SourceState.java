package com.fongmi.android.tv.source;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 源/仓的轻量状态存储（Prefers，无新增表、对上游零侵入）：
 * - 启用/禁用：被禁用的子源在展开选源（BaseConfig.firstHealthy）与 VOD 故障转移排序（InterfaceOrderStore.sortVodConfigs）中被跳过。
 */
public final class SourceState {

    private static final String KEY_DISABLED = "source_disabled_urls";

    private SourceState() {
    }

    public static boolean isEnabled(Config config) {
        return !isDisabled(config.getUrl());
    }

    public static boolean isDisabled(String url) {
        if (url == null || url.isEmpty()) return false;
        return read().contains(url);
    }

    public static void setEnabled(Config config, boolean enabled) {
        Set<String> set = read();
        if (enabled) set.remove(config.getUrl());
        else set.add(config.getUrl());
        write(set);
    }

    /** 删除配置时清理其禁用标记，避免遗留孤儿状态（A：删仓/删源不清理 SourceState）。 */
    public static void clear(String url) {
        if (url == null || url.isEmpty()) return;
        Set<String> set = read();
        if (set.remove(url)) write(set);
    }

    private static Set<String> read() {
        String json = Prefers.getString(KEY_DISABLED, "[]");
        try {
            Type type = new TypeToken<ArrayList<String>>() {
            }.getType();
            List<String> list = App.gson().fromJson(json, type);
            return new HashSet<>(list == null ? Collections.emptyList() : list);
        } catch (Throwable ignored) {
            return new HashSet<>();
        }
    }

    private static void write(Set<String> set) {
        if (set.isEmpty()) Prefers.remove(KEY_DISABLED);
        else Prefers.put(KEY_DISABLED, App.gson().toJson(new ArrayList<>(set)));
    }
}
