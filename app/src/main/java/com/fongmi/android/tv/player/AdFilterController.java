package com.fongmi.android.tv.player;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.RuleConfig;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches a VOD playlist before playback, runs M3u8Purifier and serves the
 * cleaned text through a local route. Results are cached per source URL
 * (QA1-A3): a fresh cache hit reuses the same route token without refetching
 * or re-notifying; a poisoned entry (route playback failed and fell back)
 * suppresses re-filtering of that URL until it expires. Fail-open: any
 * fetch, parse or purify problem returns no rewrite and playback keeps the
 * original URL. Only media playlists are rewritten; master and wrapper
 * playlists stay direct to preserve ABR.
 */
public final class AdFilterController {

    private static final Pattern M3U8_HINT = Pattern.compile("\\.m3u8([?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_CACHE = 8;
    private static final AdFilterCache CACHE = new AdFilterCache(MAX_CACHE, CACHE_TTL_MS);

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

    public record Result(String rewritten, int removed, String original, boolean cached) {
    }

    public static Result process(String url, Map<String, String> headers) {
        try {
            AdFilterCache.Entry cached = CACHE.peek(url);
            if (cached != null && cached.poisoned()) return null;
            if (cached != null) return new Result(routeUrl(cached.token()), cached.removed(), url, true);
            Request.Builder builder = new Request.Builder().url(url);
            if (headers != null) for (Map.Entry<String, String> header : headers.entrySet()) {
                if ("Range".equalsIgnoreCase(header.getKey())) continue;
                builder.header(header.getKey(), header.getValue());
            }
            Response response = OkHttp.player().newCall(builder.build()).execute();
            try (response; ResponseBody body = response.body()) {
                if (!response.isSuccessful() || body == null) return null;
                String text = body.string();
                if (!text.trim().startsWith("#EXTM3U")) return null;
                M3u8Purifier.Result result = M3u8Purifier.purify(url, text, RuleConfig.get().getAds());
                if (result == null || result.removed() <= 0 || TextUtils.isEmpty(result.content())) return null;
                AdFilterCache.Entry stored = CACHE.put(url, result.content(), result.removed(), newToken());
                return new Result(routeUrl(stored.token()), stored.removed(), url, false);
            }
        } catch (Throwable e) {
            SpiderDebug.log("adfilter", e);
            return null;
        }
    }

    /** 幂等供数：同一 token 在 TTL 内可重复响应（Exo 重试/重放不再 400）。 */
    public static String serve(String token) {
        AdFilterCache.Entry entry = CACHE.findByToken(token);
        return entry == null ? null : entry.content();
    }

    /** 路由播放失败回退时调用：TTL 内同 URL 不再过滤、不再复用该路由。 */
    public static void poison(String url) {
        if (!TextUtils.isEmpty(url)) CACHE.poison(url);
    }

    private static String routeUrl(String token) {
        return Server.get().getAddress("vodm3u8?t=" + token);
    }

    private static String newToken() {
        return Long.toHexString(System.nanoTime()) + Long.toHexString(UUID.randomUUID().getMostSignificantBits());
    }
}
