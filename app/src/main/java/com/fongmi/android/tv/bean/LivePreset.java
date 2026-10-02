package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.ConfigCache;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

/**
 * 内置推荐直播源。assets 清单兜底，远端清单成功拉取后经 ConfigCache 覆盖，
 * 使清单可随仓库更新而不必升级 APK。
 */
public class LivePreset {

    @SerializedName("name")
    private String name;
    @SerializedName("url")
    private String url;
    @SerializedName("epg")
    private String epg;
    @SerializedName("note")
    private String note;

    private static final String ASSET = "live_presets.json";
    private static final String REMOTE = "https://raw.githubusercontent.com/muxd93/webhtv/main/other/live_presets.json";

    public static List<LivePreset> get() {
        String cached = ConfigCache.get(REMOTE);
        List<LivePreset> items = arrayFrom(cached == null ? bundled() : cached);
        return items.isEmpty() && cached != null ? arrayFrom(bundled()) : items;
    }

    /** 静默更新远端清单；内容非法或拉取失败时保留现有清单。 */
    public static void refresh() {
        Task.submit(() -> {
            String json = OkHttp.string(REMOTE);
            if (arrayFrom(json).isEmpty()) return;
            ConfigCache.put(REMOTE, json);
        });
    }

    public static List<LivePreset> arrayFrom(String str) {
        if (TextUtils.isEmpty(str)) return Collections.emptyList();
        try {
            Type type = TypeToken.getParameterized(List.class, LivePreset.class).getType();
            List<LivePreset> items = App.gson().fromJson(str, type);
            if (items == null) return Collections.emptyList();
            items.removeIf(item -> TextUtils.isEmpty(item.url) || TextUtils.isEmpty(item.name));
            return items;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String bundled() {
        try (InputStream input = App.get().getAssets().open(ASSET)) {
            return Path.read(input);
        } catch (Exception e) {
            return "";
        }
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public String getUrl() {
        return TextUtils.isEmpty(url) ? "" : url;
    }

    public String getEpg() {
        return TextUtils.isEmpty(epg) ? "" : epg;
    }

    public String getNote() {
        return TextUtils.isEmpty(note) ? "" : note;
    }

    /** 列表展示名：备注非空时附在名称后。 */
    public String getTitle() {
        return getNote().isEmpty() ? getName() : getName() + "（" + getNote() + "）";
    }
}
