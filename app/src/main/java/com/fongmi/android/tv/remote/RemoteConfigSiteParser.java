package com.fongmi.android.tv.remote;

import android.text.TextUtils;

import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

public final class RemoteConfigSiteParser {

    private static final String TAG = RemoteConfigSiteParser.class.getSimpleName();

    private RemoteConfigSiteParser() {
    }

    public static JsonObject parse(Config config, String selectedKey, String selectedName) throws Exception {
        if (config == null || config.isEmpty()) throw new IllegalArgumentException("Missing vod config url");
        JsonElement element = Json.parse(Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG));
        if (element == null || !element.isJsonObject()) throw new IllegalStateException("Invalid config response");
        JsonObject object = element.getAsJsonObject();
        if (object.has("msg")) throw new IllegalStateException(object.get("msg").getAsString());
        if (object.has("urls")) return parseDepot(config, object, selectedKey, selectedName);
        return sitesData(config, object, selectedKey, selectedName);
    }

    /** 多仓：聚合全部子源的站点（按 key 去重），与本地导入行为一致 */
    private static JsonObject parseDepot(Config config, JsonObject object, String selectedKey, String selectedName) throws Exception {
        JsonObject data = new JsonObject();
        JsonArray sites = new JsonArray();
        Set<String> keys = new HashSet<>();
        for (Depot depot : Depot.arrayFrom(object.getAsJsonArray("urls").toString())) {
            if (depot == null || TextUtils.isEmpty(depot.getUrl())) continue;
            JsonObject child = parse(new Config().type(0).url(depot.getUrl()).name(depot.getName()), selectedKey, selectedName);
            for (JsonElement element : child.getAsJsonArray("sites")) {
                if (!element.isJsonObject()) continue;
                String key = Json.safeString(element.getAsJsonObject(), "key");
                if (TextUtils.isEmpty(key) || !keys.add(key)) continue;
                sites.add(element);
            }
        }
        if (keys.isEmpty()) throw new IllegalStateException("Depot urls is empty");
        data.add("sites", sites);
        data.addProperty("url", config.getUrl());
        data.addProperty("name", config.getName());
        return data;
    }

    private static JsonObject sitesData(Config config, JsonObject object, String selectedKey, String selectedName) {
        JsonObject data = new JsonObject();
        JsonArray sites = new JsonArray();
        Set<String> keys = new HashSet<>();
        String spider = Json.safeString(object, "spider");
        for (JsonElement element : Json.safeListElement(object, "sites")) {
            Site site = Site.objectFrom(element, spider);
            if (site == null || TextUtils.isEmpty(site.getKey())) continue;
            if (!keys.add(site.getKey())) continue;
            JsonObject item = new JsonObject();
            item.addProperty("key", site.getKey());
            item.addProperty("name", site.getName());
            item.addProperty("homePage", site.hasHomePage());
            item.addProperty("selected", isSelected(site, selectedKey, selectedName));
            sites.add(item);
        }
        data.add("sites", sites);
        data.addProperty("url", config.getUrl());
        data.addProperty("name", config.getName());
        return data;
    }

    private static boolean isSelected(Site site, String selectedKey, String selectedName) {
        if (!TextUtils.isEmpty(selectedKey)) return TextUtils.equals(site.getKey(), selectedKey);
        return !TextUtils.isEmpty(selectedName) && TextUtils.equals(site.getName(), selectedName);
    }
}
