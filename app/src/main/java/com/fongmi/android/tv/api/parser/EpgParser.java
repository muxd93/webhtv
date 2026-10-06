package com.fongmi.android.tv.api.parser;

import android.util.Log;
import android.util.Xml;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Tv;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.Formatters;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.EpgNames;
import com.github.catvod.utils.Path;

import org.simpleframework.xml.core.Persister;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public class EpgParser {

    private static final String TAG = EpgParser.class.getSimpleName();

    private static ZoneId zoneIdOf(String tz) {
        if (tz.isEmpty()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(tz);
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    private static OffsetDateTime parseFull(String source, ZoneId zoneId) {
        String s = source.trim();
        int len = s.length();
        try {
            if (len >= 20) return OffsetDateTime.parse(s, s.charAt(len - 3) == ':' ? Formatters.EPG_FULL_COLON : Formatters.EPG_FULL);
            return LocalDateTime.parse(len > 14 ? s.substring(0, 14) : s, Formatters.EPG_FULL_NO_TZ).atZone(zoneId).toOffsetDateTime();
        } catch (Exception e) {
            Log.w(TAG, "parseFull failed: " + s + " -> " + e.getMessage());
            return OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        }
    }

    public static void start(Live live, String url) throws Exception {
        long t0 = System.currentTimeMillis();
        File file = Path.epg(cacheFileName(url));
        String reason = refreshReason(file);
        boolean refresh = reason != null;
        Log.i(TAG, "start url=" + url + " file=" + file.getName() + " refresh=" + refresh + (refresh ? " reason=" + reason : ""));
        if (refresh) Download.create(url, file).get();
        readXml(live, file);
        Log.i(TAG, "start done elapsed=" + (System.currentTimeMillis() - t0) + "ms");
    }

    private static String cacheFileName(String url) {
        String name = UrlUtil.path(url);
        if (!name.isEmpty()) return name;
        String host = UrlUtil.host(url);
        if (host.isEmpty()) host = "epg";
        return host.replaceAll("[^A-Za-z0-9._-]", "_") + "_" + Integer.toHexString(url.hashCode()) + ".xml";
    }

    public static Epg getEpg(String xml, String key, ZoneId zoneId) {
        try {
            Tv tv = new Persister().read(Tv.class, xml, false);
            String rawDate = tv.getDate();
            String date = rawDate.isEmpty() ? LocalDate.now(zoneId).format(Formatters.DATE) : parseFull(rawDate, zoneId).atZoneSameInstant(zoneId).format(Formatters.DATE);
            Epg epg = Epg.create(key, date);
            tv.getProgramme().forEach(programme -> epg.getList().add(getEpgData(parseFull(programme.getStart(), zoneId), parseFull(programme.getStop(), zoneId), zoneId, programme.getTitle())));
            return epg;
        } catch (Exception e) {
            Log.w(TAG, "getEpg parse failed key=" + key + ": " + e.getMessage());
            return new Epg();
        }
    }

    private static String refreshReason(File file) {
        if (!Path.exists(file)) return "file-missing";
        if (!isToday(file.lastModified())) return "not-today";
        if (System.currentTimeMillis() - file.lastModified() > TimeUnit.HOURS.toMillis(6)) return "older-than-6h";
        return null;
    }

    private static boolean isGzip(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return (fis.read() | (fis.read() << 8)) == 0x8B1F;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isToday(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).equals(LocalDate.now());
    }

    private static void readXml(Live live, File file) throws Exception {
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        LiveChannelIndex index = prepareLiveChannels(live);
        ProgrammeResult result = streamParse(file, index, zoneId);
        bindResultsToLive(live, result);
    }

    /** 直播频道候选索引:exact 保持原精确语义;normalized 为归一化兜底键(空归一化键不入表)。 */
    private static class LiveChannelIndex {

        final Map<String, Channel> exact = new HashMap<>();
        final Map<String, Channel> normalized = new HashMap<>();
    }

    private static LiveChannelIndex prepareLiveChannels(Live live) {
        LiveChannelIndex index = new LiveChannelIndex();
        live.getGroups().stream()
                .flatMap(group -> group.getChannel().stream())
                .forEach(channel -> {
                    putCandidate(index, channel.getTvgId(), channel);
                    putCandidate(index, channel.getTvgName(), channel);
                    putCandidate(index, channel.getName(), channel);
                });
        return index;
    }

    private static void putCandidate(LiveChannelIndex index, String key, Channel channel) {
        if (key.isEmpty()) return;
        index.exact.putIfAbsent(key, channel);
        String normalized = EpgNames.normalize(key);
        if (!normalized.isEmpty()) index.normalized.putIfAbsent(normalized, channel);
    }

    /**
     * XMLTV 两遍流式解析(XmlPullParser):第一遍收 <channel>(display-name/icon),第二遍流式归桶
     * <programme>。替代 simpleframework 整树读取,内存只驻留派生 epgMap 与小型频道表;
     * gz 直接以 GZIPInputStream 流读,不再解压落第二份盘。模板 EPG 小文件路径(getEpg)仍走 Persister。
     */
    private static ProgrammeResult streamParse(File file, LiveChannelIndex index, ZoneId zoneId) throws Exception {
        boolean gzip = isGzip(file);
        Map<String, List<XmlChannel>> xmlChannels = new HashMap<>();
        try (InputStream input = open(file, gzip)) {
            readChannels(input, xmlChannels);
        }
        Map<String, Map<String, Epg>> epgMap = new HashMap<>();
        Map<String, String> srcMap = new HashMap<>();
        try (InputStream input = open(file, gzip)) {
            readProgrammes(input, xmlChannels, index, zoneId, epgMap, srcMap);
        }
        return new ProgrammeResult(epgMap, srcMap);
    }

    private static InputStream open(File file, boolean gzip) throws IOException {
        InputStream input = new FileInputStream(file);
        return gzip ? new GZIPInputStream(input, 8192) : new BufferedInputStream(input, 8192);
    }

    private static XmlPullParser newPullParser(InputStream input) throws XmlPullParserException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(input, null);
        return parser;
    }

    private static void readChannels(InputStream input, Map<String, List<XmlChannel>> out) throws Exception {
        XmlPullParser parser = newPullParser(input);
        XmlChannel current = null;
        StringBuilder text = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case "channel":
                        current = new XmlChannel(parser.getAttributeValue(null, "id"));
                        break;
                    case "display-name":
                        if (current != null) text = new StringBuilder();
                        break;
                    case "icon":
                        if (current != null && current.src.isEmpty()) current.src = parser.getAttributeValue(null, "src");
                        break;
                }
            } else if (event == XmlPullParser.TEXT) {
                if (text != null) text.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG) {
                switch (parser.getName()) {
                    case "display-name":
                        if (current != null && text != null) {
                            String name = text.toString().trim();
                            if (!name.isEmpty()) current.displayNames.add(name);
                        }
                        text = null;
                        break;
                    case "channel":
                        if (current != null && !current.id.isEmpty()) out.computeIfAbsent(current.id, k -> new ArrayList<>()).add(current);
                        current = null;
                        break;
                }
            }
        }
    }

    private static void readProgrammes(InputStream input, Map<String, List<XmlChannel>> xmlChannels, LiveChannelIndex index, ZoneId zoneId, Map<String, Map<String, Epg>> epgMap, Map<String, String> srcMap) throws Exception {
        XmlPullParser parser = newPullParser(input);
        Map<String, Channel> channelCache = new HashMap<>();
        Set<String> channelMiss = new HashSet<>();
        String id = null;
        String start = null;
        String stop = null;
        String firstTitle = null;
        StringBuilder titleBuf = null;
        int skipped = 0;
        int normalizedHits = 0;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case "programme":
                        id = parser.getAttributeValue(null, "channel");
                        start = parser.getAttributeValue(null, "start");
                        stop = parser.getAttributeValue(null, "stop");
                        firstTitle = null;
                        titleBuf = null;
                        break;
                    case "title":
                        titleBuf = new StringBuilder();
                        break;
                }
            } else if (event == XmlPullParser.TEXT) {
                if (titleBuf != null) titleBuf.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG) {
                if ("title".equals(parser.getName())) {
                    // 对齐旧语义:取首个非空 title 元素文本;实体/分片文本在元素内拼接
                    if (firstTitle == null && titleBuf != null) {
                        String text = titleBuf.toString().trim();
                        if (!text.isEmpty()) firstTitle = text;
                    }
                    titleBuf = null;
                } else if ("programme".equals(parser.getName()) && id != null) {
                    String xmlChannelId = id;
                    Channel targetChannel;
                    if (channelCache.containsKey(xmlChannelId)) {
                        targetChannel = channelCache.get(xmlChannelId);
                    } else if (channelMiss.contains(xmlChannelId)) {
                        targetChannel = null;
                    } else {
                        targetChannel = findTargetChannel(xmlChannelId, index, xmlChannels);
                        if (targetChannel != null) channelCache.put(xmlChannelId, targetChannel);
                        else channelMiss.add(xmlChannelId);
                    }
                    if (targetChannel == null) {
                        skipped++;
                    } else {
                        OffsetDateTime startDate = parseFull(start == null ? "" : start, zoneId);
                        OffsetDateTime endDate = parseFull(stop == null ? "" : stop, zoneId);
                        String liveTvgId = targetChannel.getTvgId();
                        String programmeDate = startDate.atZoneSameInstant(zoneId).format(Formatters.DATE);
                        epgMap.computeIfAbsent(liveTvgId, k -> new HashMap<>())
                                .computeIfAbsent(programmeDate, d -> Epg.create(liveTvgId, d))
                                .getList().add(getEpgData(startDate, endDate, zoneId, firstTitle == null ? "" : firstTitle));
                        if (!index.exact.containsKey(xmlChannelId)) normalizedHits++;
                        if (!srcMap.containsKey(liveTvgId)) {
                            List<XmlChannel> channels = xmlChannels.get(xmlChannelId);
                            if (channels != null) {
                                for (XmlChannel ch : channels) {
                                    if (!ch.src.isEmpty()) {
                                        srcMap.put(liveTvgId, ch.src);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    id = null;
                    start = null;
                    stop = null;
                    firstTitle = null;
                    titleBuf = null;
                }
            }
        }
        Log.i(TAG, "processProgramme skipped(no match)=" + skipped + " normalizedHits=" + normalizedHits + " matched channels=" + epgMap.size());
    }

    private static Channel findTargetChannel(String xmlChannelId, LiveChannelIndex index, Map<String, List<XmlChannel>> xmlChannelIdMap) {
        Channel targetChannel = index.exact.get(xmlChannelId);
        if (targetChannel != null) return targetChannel;
        targetChannel = index.normalized.get(EpgNames.normalize(xmlChannelId));
        if (targetChannel != null) return targetChannel;
        List<XmlChannel> channels = xmlChannelIdMap.get(xmlChannelId);
        if (channels == null) return null;
        for (XmlChannel channel : channels) {
            for (String name : channel.displayNames) {
                Channel hit = index.exact.containsKey(name) ? index.exact.get(name) : index.normalized.get(EpgNames.normalize(name));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void bindResultsToLive(Live live, ProgrammeResult result) {
        int[] counts = {0, 0};
        live.getGroups().stream()
                .flatMap(group -> group.getChannel().stream())
                .forEach(channel -> {
                    String tvgId = channel.getTvgId();
                    Map<String, Epg> dateMap = result.epgMap.get(tvgId);
                    if (dateMap != null) {
                        channel.setDataList(new ArrayList<>(dateMap.values()));
                        counts[0]++;
                    } else {
                        counts[1]++;
                    }
                    if (channel.getLogo().isEmpty()) {
                        String src = result.srcMap.get(tvgId);
                        if (src != null) channel.setLogo(src);
                    }
                });
        Log.i(TAG, "bindResultsToLive with-epg=" + counts[0] + " without-epg=" + counts[1]);
    }

    private static EpgData getEpgData(OffsetDateTime startDate, OffsetDateTime endDate, ZoneId zoneId, String title) {
        try {
            EpgData epgData = new EpgData();
            epgData.setTitle(title);
            epgData.setStart(startDate.atZoneSameInstant(zoneId).format(Formatters.TIME));
            epgData.setEnd(endDate.atZoneSameInstant(zoneId).format(Formatters.TIME));
            epgData.setStartTime(startDate.toInstant().toEpochMilli());
            epgData.setEndTime(endDate.toInstant().toEpochMilli());
            epgData.trans();
            return epgData;
        } catch (Exception e) {
            return new EpgData();
        }
    }

    private static class XmlChannel {

        final String id;
        final List<String> displayNames = new ArrayList<>();
        String src = "";

        XmlChannel(String id) {
            this.id = id == null ? "" : id;
        }
    }

    private static class ProgrammeResult {

        Map<String, Map<String, Epg>> epgMap;
        Map<String, String> srcMap;

        public ProgrammeResult(Map<String, Map<String, Epg>> epgMap, Map<String, String> srcMap) {
            this.epgMap = epgMap;
            this.srcMap = srcMap;
        }
    }
}
