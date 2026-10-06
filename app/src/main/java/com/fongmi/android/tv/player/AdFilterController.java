package com.fongmi.android.tv.player;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.RuleConfig;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches a VOD playlist before playback, runs M3u8Purifier and serves the
 * cleaned text through a short-lived local route. Fail-open: any fetch,
 * parse or purify problem returns no rewrite and playback keeps the original
 * URL. Only media playlists are rewritten; master and wrapper playlists stay
 * direct to preserve ABR.
 */
public final class AdFilterController {

    private static final Pattern M3U8_HINT = Pattern.compile("\\.m3u8([?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_CACHE = 4;
    private static final LinkedHashMap<String, String> PURIFIED = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(LinkedHashMap.Entry<String, String> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private AdFilterController() {
    }

    public static boolean shouldProcess(String url) {
        if (!Setting.isAdblock()) return false;
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        if (lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost")) return false;
        return M3U8_HINT.matcher(url).find();
    }

    public static boolean isRouteUrl(String url) {
        return url != null && url.contains("/vodm3u8?t=");
    }

    public record Result(String rewritten, int removed, String original) {
    }

    public static Result process(String url, Map<String, String> headers) {
        try {
            Request.Builder builder = new Request.Builder().url(url);
            if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) {
                if ("Range".equalsIgnoreCase(entry.getKey())) continue;
                builder.header(entry.getKey(), entry.getValue());
            }
            Response response = OkHttp.player().newCall(builder.build()).execute();
            try (response; ResponseBody body = response.body()) {
                if (!response.isSuccessful() || body == null) return null;
                String text = body.string();
                if (!text.trim().startsWith("#EXTM3U")) return null;
                M3u8Purifier.Result result = M3u8Purifier.purify(url, text, RuleConfig.get().getAds());
                if (result == null || result.removed() <= 0 || TextUtils.isEmpty(result.content())) return null;
                String token = newToken();
                synchronized (PURIFIED) {
                    PURIFIED.put(token, result.content());
                }
                return new Result(routeUrl(token), result.removed(), url);
            }
        } catch (Throwable e) {
            SpiderDebug.log("adfilter", e);
            return null;
        }
    }

    public static String consume(String token) {
        if (TextUtils.isEmpty(token)) return null;
        synchronized (PURIFIED) {
            return PURIFIED.remove(token);
        }
    }

    private static String routeUrl(String token) {
        return Server.get().getAddress("vodm3u8?t=" + token);
    }

    private static String newToken() {
        return Long.toHexString(System.nanoTime()) + Long.toHexString(java.util.UUID.randomUUID().getMostSignificantBits());
    }
}
