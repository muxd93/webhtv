package com.fongmi.android.tv.api;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.parser.EpgParser;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.setting.LiveEpgSetting;
import com.fongmi.android.tv.utils.Formatters;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LiveApi {

    public static void parse(@NonNull Live item) throws Exception {
        LiveParser.start(item.recent());
        item.getGroups().removeIf(Group::isEmpty);
        if (item.getGroups().isEmpty() || item.getGroups().get(0).isKeep()) return;
        item.getGroups().add(0, Group.create(R.string.keep));
        LiveConfig.get().applyKeepsToGroups(item.getGroups());
    }

    public static boolean parseXml(@NonNull Live item) {
        return LiveEpgSetting.getXmlUrls(item).stream().map(url -> startXml(item, url)).reduce(false, Boolean::logicalOr);
    }

    @NonNull
    public static Epg getEpg(@NonNull Channel item, @NonNull ZoneId zoneId) {
        String today = LocalDate.now(zoneId).format(Formatters.DATE);
        for (int offset : new int[]{-1, 0, 1}) fetchEpgDay(item, zoneId, offset);
        Epg current = item.getDataList().stream().filter(epg -> epg.equal(today)).findFirst().orElse(null);
        if (current != null) current.setNextDayFirst(nextDayFirst(item, zoneId));
        return current == null ? new Epg() : current.selected();
    }

    /** 模板 EPG 是否支持按日期请求({date} 占位);XML 全量源日期由文件决定,不可懒取。 */
    public static boolean isDateRequestable(@NonNull Channel item) {
        return item.getEpg().startsWith("http") && item.getEpg().contains("{date}");
    }

    /** 节目单懒加载:按需抓取单日(复用 fetchEpgDay 的 TTL/负缓存/磁盘缓存);无数据返回当日空槽。 */
    @NonNull
    public static Epg getEpgDay(@NonNull Channel item, @NonNull ZoneId zoneId, int offset) {
        fetchEpgDay(item, zoneId, offset);
        String date = LocalDate.now(zoneId).plusDays(offset).format(Formatters.DATE);
        return item.getDataList().stream().filter(epg -> epg.equal(date)).findFirst().orElseGet(() -> Epg.create(item.getTvgId(), date));
    }

    /** 跨天"下一档"预取：次日首条节目文本（供 nowNext 在当天最后一档后显示）。 */
    private static String nextDayFirst(@NonNull Channel item, @NonNull ZoneId zoneId) {
        String tomorrow = LocalDate.now(zoneId).plusDays(1).format(Formatters.DATE);
        return item.getDataList().stream().filter(epg -> epg.equal(tomorrow)).findFirst()
                .filter(epg -> !epg.getList().isEmpty())
                .map(epg -> {
                    EpgData first = epg.getList().get(0);
                    return first.getStart().isEmpty() ? first.getTitle() : first.getStart() + " " + first.getTitle();
                }).orElse("");
    }

    @NonNull
    public static Result getUrl(@NonNull Channel item) throws Exception {
        Source.get().stop();
        Result result = item.result();
        result.setUrl(Source.get().fetch(result));
        return result;
    }

    @NonNull
    public static Result getUrl(@NonNull Channel item, @NonNull EpgData data) throws Exception {
        Result result = getUrl(item);
        result.setUrl(item.getCatchup().format(result.getRealUrl(), data));
        if (item.isRtsp()) result.getHeader().put("rtsp_range", data.getRange());
        return result;
    }

    private static boolean startXml(Live item, String url) {
        try {
            EpgParser.start(item, url);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 模板 EPG SWR（LIVE9）：新鲜数据零网络；失败短窗负缓存；磁盘缓存跨会话免拉。
    // TTL 与 XML 全量文件缓存（EpgParser.refreshReason）同语义（当天 + 6h）。
    private static final long TTL_MS = TimeUnit.HOURS.toMillis(6);
    private static final long FAILED_MS = TimeUnit.SECONDS.toMillis(60);
    private static final long CACHE_MAX_MS = TimeUnit.DAYS.toMillis(3);
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();

    private static void fetchEpgDay(@NonNull Channel item, @NonNull ZoneId zoneId, int offset) {
        String date = LocalDate.now(zoneId).plusDays(offset).format(Formatters.DATE);
        String url = item.getEpg().replace("{date}", date);
        if (!url.startsWith("http")) return;
        Epg existing = item.getDataList().stream().filter(epg -> epg.equal(date)).findFirst().orElse(null);
        if (existing != null && System.currentTimeMillis() - existing.getFetchedAt() < TTL_MS) return;
        Long failedAt = FAILED.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_MS) return;
        Epg cached = readCache(url);
        if (cached != null && cached.equal(date) && System.currentTimeMillis() - cached.getFetchedAt() < TTL_MS) {
            item.setData(cached);
            return;
        }
        String str = OkHttp.string(url);
        if (str.isEmpty()) {
            FAILED.put(url, System.currentTimeMillis());
            return;
        }
        Epg parsed = Epg.objectFrom(str, item.getTvgId(), zoneId);
        // 解析为空且已有旧数据：保留旧值不清空（失败降级而非擦除）
        if (parsed.getList().isEmpty() && existing != null) return;
        parsed.setFetchedAt(System.currentTimeMillis());
        item.setData(parsed);
        writeCache(url, parsed);
    }

    private static File cacheFile(String url) {
        return Path.epg("tpl_" + Util.md5(url) + ".json");
    }

    private static Epg readCache(String url) {
        try {
            String json = Path.read(cacheFile(url));
            if (json == null || json.isEmpty()) return null;
            return App.gson().fromJson(json, Epg.class);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeCache(String url, Epg epg) {
        try {
            File file = cacheFile(url);
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(App.gson().toJson(epg).getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) temp.delete();
            cleanCache();
        } catch (Throwable ignored) {
        }
    }

    /** 模板缓存清理：仅回收本类写入的 tpl_*.json，XML 全量缓存不归本类管。 */
    private static void cleanCache() {
        File[] files = cacheFile("_").getParentFile().listFiles();
        if (files == null) return;
        long limit = System.currentTimeMillis() - CACHE_MAX_MS;
        for (File file : files) if (file.getName().startsWith("tpl_") && file.getName().endsWith(".json") && file.lastModified() < limit) Path.clear(file);
    }
}
