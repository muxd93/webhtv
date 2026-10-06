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
 * - 键为 "type|url" 复合键（SRCUI1）：配置身份是 (type,url)，同一 URL 的点播/直播配置禁用状态互不影响。
 * - 启用/禁用：被禁用的子源在展开选源（BaseConfig.firstHealthy）与 VOD 故障转移排序（InterfaceOrderStore.sortVodConfigs）中被跳过。
 * - 旧版按 URL 全局禁用（source_disabled_urls）在首次读取时按现存配置表展开迁移。
 */
public final class SourceState {

    private static final String KEY_DISABLED = "source_disabled_keys";
    private static final String KEY_DISABLED_LEGACY = "source_disabled_urls";

    private SourceState() {
    }

    public static boolean isEnabled(Config config) {
        return !isDisabled(config.getType(), config.getUrl());
    }

    public static boolean isDisabled(int type, String url) {
        if (url == null || url.isEmpty()) return false;
        return read().contains(key(type, url));
    }

    public static void setEnabled(Config config, boolean enabled) {
        Set<String> set = read();
        String k = key(config.getType(), config.getUrl());
        if (enabled) set.remove(k);
        else set.add(k);
        write(set);
    }

    /** 删除配置时清理其禁用标记，避免遗留孤儿状态。 */
    public static void clear(int type, String url) {
        if (url == null || url.isEmpty()) return;
        Set<String> set = read();
        if (set.remove(key(type, url))) write(set);
    }

    private static String key(int type, String url) {
        return type + "|" + url;
    }

    private static Set<String> read() {
        String json = Prefers.getString(KEY_DISABLED, null);
        if (json == null) return migrate();
        return parse(json);
    }

    /** 旧版 URL 单键条目按现存配置展开为 type|url（同 URL 多 type 各自成键），仅执行一次。 */
    private static Set<String> migrate() {
        Set<String> migrated = new HashSet<>();
        try {
            Set<String> urls = parse(Prefers.getString(KEY_DISABLED_LEGACY, "[]"));
            if (!urls.isEmpty()) {
                for (int t = 0; t <= 2; t++) {
                    for (Config c : Config.getAll(t)) {
                        if (urls.contains(c.getUrl())) migrated.add(key(t, c.getUrl()));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        Prefers.put(KEY_DISABLED, App.gson().toJson(new ArrayList<>(migrated)));
        Prefers.remove(KEY_DISABLED_LEGACY);
        return migrated;
    }

    private static Set<String> parse(String json) {
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
        Prefers.put(KEY_DISABLED, App.gson().toJson(new ArrayList<>(set)));
    }
}
