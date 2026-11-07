package com.fongmi.android.tv.web;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.Source;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WebHomeInlineVodStore {

    public static final String KEY = "webhome_inline";
    private static final String HLS_FORMAT = "application/x-mpegURL";

    /** inline 记录索引：容量 8，LRU 淘汰；正在播放/正在浏览的那条被 pin 住不淘汰 */
    private static final InlineVodIndex<Entry> ITEMS = new InlineVodIndex<>();
    private static final Map<String, HeaderSpec> URL_HEADERS = new ConcurrentHashMap<>();
    private static final Map<String, EpisodeSpec> URL_EPISODES = new ConcurrentHashMap<>();

    public static String put(JsonObject payload) {
        return put(payload, null);
    }

    public static String put(JsonObject payload, Resolver resolver) {
        Vod vod = App.gson().fromJson(payload, Vod.class);
        if (vod == null) vod = new Vod();
        String id = first(vod.getId(), Json.safeString(payload, "vodId"), Json.safeString(payload, "id"), "webhome-" + UUID.randomUUID());
        Map<String, String> headers = HeaderPolicy.withDefaultUa(HeaderPolicy.parse(payload.get("headers")));
        HeaderSpec headerSpec = new HeaderSpec(headers, "include".equals(Json.safeString(payload, "credentials")));
        Set<String> urls = new LinkedHashSet<>();
        String playUrl = playUrl(payload.get("episodes"), headerSpec, resolver, urls);
        vod.setId(id);
        if (vod.getName().isEmpty()) vod.setName(first(Json.safeString(payload, "vodName"), Json.safeString(payload, "title"), id));
        if (vod.getPic().isEmpty()) vod.setPic(first(Json.safeString(payload, "vodPic"), Json.safeString(payload, "pic")));
        if (vod.getPlayFrom().isEmpty()) vod.setPlayFrom(first(Json.safeString(payload, "vodPlayFrom"), Json.safeString(payload, "playFrom"), "WebHome"));
        if (vod.getPlayUrl().isEmpty()) vod.setPlayUrl(playUrl);
        Map<String, Set<String>> dropped = ITEMS.put(id, new Entry(App.gson().toJson(vod), headerSpec, resolver), urls);
        drop(dropped);
        ITEMS.pin(id);
        SpiderDebug.log("webhome-inline", "put id=%s urls=%s cached=%s dropped=%s", id, urls.size(), ITEMS.size(), dropped.keySet());
        return id;
    }

    public static Result detail(String id) {
        Entry entry = ITEMS.get(id);
        if (entry == null || TextUtils.isEmpty(entry.vod)) return Result.error("WebHome inline VOD not found");
        // 正在浏览/即将重播的记录：钉住，避免下一次 put 把它当成最旧的一条淘汰
        ITEMS.pin(id);
        SpiderDebug.log("webhome-inline", "detail id=%s found=%s", id, true);
        return Result.vod(Vod.objectFrom(entry.vod));
    }

    public static Result player(String flag, String id) throws Exception {
        Entry entry = ITEMS.get(id);
        String owner = ITEMS.ownerOf(id);
        if (owner != null) ITEMS.pin(owner);
        HeaderSpec headerSpec = URL_HEADERS.get(id);
        EpisodeSpec episodeSpec = URL_EPISODES.get(id);
        String url = id;
        String format = "";
        long start = System.currentTimeMillis();
        SpiderDebug.log("webhome-inline", "player start flag=%s id=%s entry=%s episode=%s", flag, id, entry != null, episodeSpec != null);
        if (episodeSpec != null) {
            ResolveResult resolved = resolve(entry, id, episodeSpec);
            url = resolved.url;
            format = resolved.format;
            headerSpec = resolved.headerSpec;
        }
        if (headerSpec == null && entry != null) headerSpec = entry.headerSpec;
        if (headerSpec == null) headerSpec = new HeaderSpec(new HashMap<>(), false);
        Result result = new Result();
        result.setUrl(url);
        result.setParse(0);
        result.setFlag(flag);
        result.setHeader(headers(url, headerSpec));
        if (!TextUtils.isEmpty(format)) result.setFormat(format);
        else if (isHls(url)) result.setFormat(HLS_FORMAT);
        SpiderDebug.log("webhome-inline", "player resolved cost=%sms id=%s url=%s format=%s headers=%s", System.currentTimeMillis() - start, id, url, result.getFormat(), result.getHeader().keySet());
        result.setUrl(Source.get().fetch(result));
        SpiderDebug.log("webhome-inline", "player fetch ok cost=%sms id=%s url=%s", System.currentTimeMillis() - start, id, result.getUrl().v());
        return result;
    }

    private static Map<String, String> headers(String url, HeaderSpec headerSpec) {
        Map<String, String> result = HeaderPolicy.withDefaultUa(headerSpec.headers);
        boolean hasCookie = hasHeader(result, HttpHeaders.COOKIE);
        if (headerSpec.includeCookies && !hasCookie) {
            String cookie = CookieBridge.get(url);
            if (!TextUtils.isEmpty(cookie)) result.put("Cookie", cookie);
        }
        addBrowserCodeHeader(result);
        return result;
    }

    /**
     * 成组淘汰：同一条记录登记过的每个 key 都从 {@code URL_HEADERS} 与 {@code URL_EPISODES} 一起删掉。
     * 两张表按同一个 url 成组写入、按同一套 key 成组删除，因此不会留下「有 headers 没剧集」的半条记录。
     */
    private static void drop(Map<String, Set<String>> changes) {
        for (Map.Entry<String, Set<String>> entry : changes.entrySet()) {
            for (String url : entry.getValue()) {
                URL_HEADERS.remove(url);
                URL_EPISODES.remove(url);
            }
            SpiderDebug.log("webhome-inline", "drop id=%s urls=%s cached=%s", entry.getKey(), entry.getValue().size(), ITEMS.size());
        }
    }

    private static String playUrl(JsonElement element, HeaderSpec defaultSpec, Resolver resolver, Set<String> urls) {
        if (element == null || !element.isJsonArray()) return "";
        StringBuilder builder = new StringBuilder();
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement child = array.get(i);
            JsonObject object = child != null && child.isJsonObject() ? child.getAsJsonObject() : new JsonObject();
            String mediaUrl = Json.safeString(object, "mediaUrl");
            String pageUrl = first(Json.safeString(object, "pageUrl"), Json.safeString(object, "href"));
            String rawUrl = child != null && child.isJsonPrimitive() ? child.getAsString() : Json.safeString(object, "url");
            String url = first(mediaUrl, pageUrl, rawUrl);
            if (TextUtils.isEmpty(url)) continue;
            HeaderSpec episodeHeaders = episodeHeaders(object, defaultSpec);
            URL_HEADERS.put(url, episodeHeaders);
            urls.add(url);
            if (child != null && child.isJsonObject()) {
                boolean resolve = bool(object, "resolve") || (!TextUtils.isEmpty(pageUrl) && TextUtils.isEmpty(mediaUrl));
                String format = first(Json.safeString(object, "format"), isHls(mediaUrl) ? HLS_FORMAT : "");
                URL_EPISODES.put(url, new EpisodeSpec(object.deepCopy(), episodeHeaders, resolver, resolve, mediaUrl, format));
                if (!TextUtils.isEmpty(mediaUrl)) {
                    URL_HEADERS.put(mediaUrl, episodeHeaders);
                    urls.add(mediaUrl);
                }
            }
            String name = first(Json.safeString(object, "name"), Json.safeString(object, "label"), Json.safeString(object, "title"), String.format("%02d", i + 1));
            if (builder.length() > 0) builder.append("#");
            builder.append(name.replace("$", " ").replace("#", " ")).append("$").append(url);
        }
        return builder.toString();
    }

    private static HeaderSpec episodeHeaders(JsonObject object, HeaderSpec defaultSpec) {
        Map<String, String> result = new HashMap<>(defaultSpec.headers);
        if (object != null && object.has("headers")) result.putAll(HeaderPolicy.parse(object.get("headers")));
        String referer = object == null ? "" : Json.safeString(object, "referer");
        if (!TextUtils.isEmpty(referer)) result.put(HttpHeaders.REFERER, referer);
        boolean includeCookies = defaultSpec.includeCookies || (object != null && "include".equals(Json.safeString(object, "credentials")));
        return new HeaderSpec(result, includeCookies);
    }

    private static ResolveResult resolve(Entry entry, String id, EpisodeSpec episodeSpec) throws Exception {
        if (!TextUtils.isEmpty(episodeSpec.mediaUrl)) {
            SpiderDebug.log("webhome-inline", "resolve cached id=%s url=%s", id, episodeSpec.mediaUrl);
            return new ResolveResult(episodeSpec.mediaUrl, episodeSpec.headerSpec, episodeSpec.format);
        }
        Resolver resolver = entry != null ? entry.resolver : episodeSpec.resolver;
        if (!episodeSpec.resolve || resolver == null) {
            String url = Json.safeString(episodeSpec.payload, "url");
            SpiderDebug.log("webhome-inline", "resolve direct id=%s url=%s resolve=%s resolver=%s", id, url, episodeSpec.resolve, resolver != null);
            return new ResolveResult(url, episodeSpec.headerSpec, episodeSpec.format);
        }
        long start = System.currentTimeMillis();
        SpiderDebug.log("webhome-inline", "resolve episode start id=%s page=%s", id, Json.safeString(episodeSpec.payload, "pageUrl"));
        JsonObject resolved = resolver.resolve(episodeSpec.payload.deepCopy());
        String url = Json.safeString(resolved, "url");
        if (TextUtils.isEmpty(url)) throw new IllegalStateException("WebHome inline episode resolve failed");
        HeaderSpec headerSpec = resolvedHeaders(resolved, episodeSpec.headerSpec);
        String format = first(Json.safeString(resolved, "format"), isHls(url) ? HLS_FORMAT : episodeSpec.format);
        URL_HEADERS.put(url, headerSpec);
        URL_EPISODES.put(id, new EpisodeSpec(episodeSpec.payload.deepCopy(), headerSpec, resolver, false, url, format));
        // 解析出的直链 url 记到同一条记录下：记录被淘汰时它一起消失，不会留下孤儿 headers
        String owner = ITEMS.ownerOf(id);
        if (owner != null) ITEMS.claim(owner, url);
        else SpiderDebug.log("webhome-inline", "resolve owner missing id=%s url=%s", id, url);
        SpiderDebug.log("webhome-inline", "resolve episode ok cost=%sms id=%s url=%s", System.currentTimeMillis() - start, id, url);
        return new ResolveResult(url, headerSpec, format);
    }

    private static HeaderSpec resolvedHeaders(JsonObject resolved, HeaderSpec fallback) {
        Map<String, String> headers = new HashMap<>(fallback.headers);
        if (resolved != null && resolved.has("headers")) headers.putAll(HeaderPolicy.parse(resolved.get("headers")));
        String referer = resolved == null ? "" : Json.safeString(resolved, "referer");
        if (!TextUtils.isEmpty(referer)) headers.put(HttpHeaders.REFERER, referer);
        boolean includeCookies = fallback.includeCookies || (resolved != null && "include".equals(Json.safeString(resolved, "credentials")));
        return new HeaderSpec(headers, includeCookies);
    }

    private static void addBrowserCodeHeader(Map<String, String> headers) {
        if (hasHeader(headers, "browser-code")) return;
        String cookie = header(headers, HttpHeaders.COOKIE);
        if (TextUtils.isEmpty(cookie)) return;
        for (String part : cookie.split(";")) {
            String value = part.trim();
            if (!value.startsWith("browser-code=")) continue;
            headers.put("browser-code", value.substring("browser-code=".length()));
            return;
        }
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return "";
    }

    private static boolean isHls(String url) {
        String value = url == null ? "" : url.toLowerCase();
        return value.contains(".m3u8") || value.contains("/playlist/");
    }

    private static boolean bool(JsonObject object, String name) {
        try {
            return object != null && object.has(name) && object.get(name).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String first(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value.trim();
        return "";
    }

    public interface Resolver {
        JsonObject resolve(JsonObject payload) throws Exception;
    }

    private record Entry(String vod, HeaderSpec headerSpec, Resolver resolver) {
    }

    private record HeaderSpec(Map<String, String> headers, boolean includeCookies) {
    }

    private record EpisodeSpec(JsonObject payload, HeaderSpec headerSpec, Resolver resolver, boolean resolve, String mediaUrl, String format) {
    }

    private record ResolveResult(String url, HeaderSpec headerSpec, String format) {
    }
}
