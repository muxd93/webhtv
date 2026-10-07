package com.fongmi.android.tv.player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/**
 * VOD playlist ad remover. Heuristic layers: configured keyword match, segment
 * URL patterns, ad CDN domains, SCTE35/CUE ad-break markers, minority URL path,
 * EXTINF decimal precision and frame-rate alignment. Every layer is bounded so
 * a false positive can never strip the majority of a playlist. Master playlists
 * and playlists without media segments are passed through untouched.
 */
public final class M3u8Purifier {

    private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String TAG_MEDIA_DURATION = "#EXTINF";
    private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
    private static final String TAG_KEY = "#EXT-X-KEY";
    private static final String TAG_MAP = "#EXT-X-MAP";
    private static final String TAG_CUE_OUT = "#EXT-X-CUE-OUT";
    private static final String TAG_CUE_IN = "#EXT-X-CUE-IN";
    private static final String TAG_DATERANGE = "#EXT-X-DATERANGE";

    private static final Pattern REGEX_MEDIA_DURATION = Pattern.compile(TAG_MEDIA_DURATION + ":([\\d\\.]+)\\b");
    private static final Pattern REGEX_URI = Pattern.compile("URI=\"(.+?)\"");
    private static final Pattern REGEX_AD_SEGMENT_URI = Pattern.compile("(?i)(^|[/?&=_.-])(ads?|adv|advert(ise(ment)?)?|commercial|preroll|pre-roll|midroll|mid-roll|postroll|post-roll|sponsor|scte|vast|vmap|interstitial|bumper)([/?&=_.-]|$)");

    private static final String[] AD_DOMAIN_KEYWORDS = {
            "adservice", "adserver", "adsystem", "doubleclick", "googlesyndication",
            "advertising", "2mdn.net", "moatads", "scorecardresearch", "quantserve"
    };

    private static final int MAX_FRAME_RATE_AD_BLOCK_SIZE = 12;
    private static final int LAYER_SKIP_RATIO_PERCENT = 30;
    private static final int PURIFY_ABORT_RATIO_PERCENT = 50;
    private static final double MAJORITY_PREFIX_RATIO = 0.8;
    private static final double MAJOR_PRECISION_RATIO = 0.7;
    private static final double MAIN_DURATION_RATIO = 0.18;
    private static final int MINORITY_DOMAIN_KEEP_THRESHOLD = 15;
    private static final Map<Integer, Set<BigDecimal>> FRAME_RATE_FEATURES = prepareFrameRateFeatures();

    private int removed;
    private final HttpUrl base;
    private final List<String> configAds;

    private M3u8Purifier(String baseUrl, List<String> configAds) {
        this.base = HttpUrl.parse(baseUrl);
        this.configAds = configAds == null ? List.of() : configAds;
    }

    public record Result(String content, int removed) {
    }

    public static Result purify(String baseUrl, String content, List<String> configAds) {
        if (content == null || content.isEmpty() || !content.trim().startsWith("#EXTM3U")) return null;
        if (content.contains("#EXT-X-STREAM-INF")) return null;
        if (!hasExtInf(content)) return null;
        // Dynamic/live playlists must never be purified: serving a snapshot
        // through the local route would freeze the stream until the cache TTL
        // expires. VOD-only scope, same ENDLIST convention as HlsAdsParser.
        if (!hasEndList(content)) return null;
        M3u8Purifier purifier = new M3u8Purifier(baseUrl, configAds);
        String result = purifier.purify(content);
        return result == null ? null : new Result(result, purifier.removed);
    }

    private static boolean hasExtInf(String content) {
        for (String raw : content.split("\n")) if (raw.trim().startsWith(TAG_MEDIA_DURATION)) return true;
        return false;
    }

    private String purify(String m3u8content) {
        if (m3u8content.startsWith("\ufeff")) m3u8content = m3u8content.substring(1);
        int totalSegments = countSegments(m3u8content);
        String minority = removeMinorityUrl(m3u8content);
        String result = minority != null && removed > 0 ? get(minority) : get(m3u8content);
        result = keepVodEndList(m3u8content, result);
        if (totalSegments > 0 && removed > totalSegments * PURIFY_ABORT_RATIO_PERCENT / 100.0) {
            removed = 0;
            return m3u8content;
        }
        if (removed > 0 && !isPlayableMediaPlaylist(result)) {
            removed = 0;
            return m3u8content;
        }
        return result;
    }

    private int countSegments(String content) {
        int total = 0;
        for (String line : content.split(content.contains("\r\n") ? "\r\n" : "\n")) {
            if (line.length() > 0 && line.charAt(0) != '#') total += 1;
        }
        return total;
    }

    private double maxPercent(HashMap<String, Integer> preUrlMap) {
        int maxTimes = 0, totalTimes = 0;
        for (Map.Entry<String, Integer> entry : preUrlMap.entrySet()) {
            if (entry.getValue() > maxTimes) maxTimes = entry.getValue();
            totalTimes += entry.getValue();
        }
        return maxTimes * 1.0 / (totalTimes * 1.0);
    }

    private String removeMinorityUrl(String m3u8content) {
        String linesplit = m3u8content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = m3u8content.split(linesplit);
        int totalSegments = countSegments(m3u8content);

        HashMap<String, Integer> preUrlMap = new HashMap<>();
        for (String line : lines) {
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            String absoluteUrl = toAbsoluteUrl(line);
            int ilast = absoluteUrl.lastIndexOf('.');
            if (ilast <= 4) continue;
            String preUrl = absoluteUrl.substring(0, ilast - 4);
            preUrlMap.merge(preUrl, 1, Integer::sum);
        }
        if (preUrlMap.size() <= 1) return null;
        boolean domainFiltering = false;
        if (maxPercent(preUrlMap) < MAJORITY_PREFIX_RATIO) {
            preUrlMap.clear();
            for (String line : lines) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                String absoluteUrl = toAbsoluteUrl(line);
                if (!absoluteUrl.startsWith("http://") && !absoluteUrl.startsWith("https://")) return null;
                int ifirst = absoluteUrl.indexOf('/', 9);
                if (ifirst <= 0) continue;
                preUrlMap.merge(absoluteUrl.substring(0, ifirst), 1, Integer::sum);
            }
            if (preUrlMap.size() <= 1) return null;
            if (maxPercent(preUrlMap) < MAJORITY_PREFIX_RATIO) return null;
            boolean allDomainsExceedThreshold = true;
            for (Integer count : preUrlMap.values()) {
                if (count <= MINORITY_DOMAIN_KEEP_THRESHOLD) {
                    allDomainsExceedThreshold = false;
                    break;
                }
            }
            if (allDomainsExceedThreshold) return null;
            domainFiltering = true;
        }

        int maxTimes = 0;
        String maxTimesPreUrl = "";
        for (Map.Entry<String, Integer> entry : preUrlMap.entrySet()) {
            if (entry.getValue() > maxTimes) {
                maxTimesPreUrl = entry.getKey();
                maxTimes = entry.getValue();
            }
        }
        if (maxTimes == 0) return null;

        StringBuilder filtered = new StringBuilder();
        List<String> pendingSegmentTags = new ArrayList<>();
        for (String item : lines) {
            String trimmed = item.trim();
            if (trimmed.length() == 0) {
                if (pendingSegmentTags.isEmpty()) appendLine(filtered, item, linesplit);
                else pendingSegmentTags.add(item);
                continue;
            }
            if (trimmed.charAt(0) == '#') {
                String output = hasUriAttribute(trimmed) ? resolveUriLine(item) : item;
                if (isSegmentTag(trimmed)) pendingSegmentTags.add(output);
                else {
                    flush(filtered, pendingSegmentTags, linesplit);
                    appendLine(filtered, output, linesplit);
                }
                continue;
            }
            String absoluteUrl = toAbsoluteUrl(item);
            if (shouldKeepMediaUrl(absoluteUrl, domainFiltering, maxTimesPreUrl, preUrlMap)) {
                flush(filtered, pendingSegmentTags, linesplit);
                appendLine(filtered, absoluteUrl, linesplit);
            } else {
                pendingSegmentTags.clear();
                removed += 1;
            }
        }

        if (totalSegments > 0 && removed > totalSegments * LAYER_SKIP_RATIO_PERCENT / 100.0) {
            removed = 0;
            return null;
        }
        return normalizeMediaPlaylist(filtered.toString());
    }

    private String get(String m3u8Content) {
        String line = resolveContent(m3u8Content);
        line = cleanCommonAdMarkers(line);
        if (hasEndList(line) && line.contains(TAG_DISCONTINUITY)) {
            line = cleanDecimalPrecisionGroups(line);
            line = cleanFrameRateGroups(line);
        }
        return cleanDiscontinuityGroups(line);
    }

    private String cleanDecimalPrecisionGroups(String m3u8Content) {
        List<Group> groups = buildDiscontinuityGroups(m3u8Content.split("\n"));
        if (groups.size() < 2) return m3u8Content;

        Map<Integer, Integer> precisionCounts = new HashMap<>();
        int totalSegments = 0;
        for (Group group : groups) {
            for (String raw : group.lines) {
                int precision = getDecimalPrecision(raw);
                if (precision < 0) continue;
                totalSegments += 1;
                precisionCounts.merge(precision, 1, Integer::sum);
            }
        }
        if (totalSegments < 8 || precisionCounts.size() < 2) return m3u8Content;

        int majorPrecision = -1;
        int majorCount = 0;
        for (Map.Entry<Integer, Integer> entry : precisionCounts.entrySet()) {
            if (entry.getValue() > majorCount) {
                majorPrecision = entry.getKey();
                majorCount = entry.getValue();
            }
        }
        if (majorPrecision < 0 || majorCount * 1.0 / totalSegments < MAJOR_PRECISION_RATIO) return m3u8Content;

        boolean[] removeGroups = new boolean[groups.size()];
        int removableSegments = 0;
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            if (i == groups.size() - 1 || group.segmentCount == 0 || group.segmentCount > MAX_FRAME_RATE_AD_BLOCK_SIZE) continue;
            DecimalPrecisionStats stats = getDecimalPrecisionStats(group, majorPrecision);
            if (stats.total > 0 && stats.mismatched == stats.total) {
                removeGroups[i] = true;
                removableSegments += group.segmentCount;
            }
        }

        if (removableSegments == 0 || removableSegments > getAdSegmentLimit(m3u8Content)
                || removableSegments > totalSegments * LAYER_SKIP_RATIO_PERCENT / 100.0) return m3u8Content;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            if (removeGroups[i]) removed += groups.get(i).segmentCount;
            else groups.get(i).appendTo(sb);
        }
        return normalizeMediaPlaylist(sb.toString());
    }

    private DecimalPrecisionStats getDecimalPrecisionStats(Group group, int majorPrecision) {
        DecimalPrecisionStats stats = new DecimalPrecisionStats();
        for (String raw : group.lines) {
            int precision = getDecimalPrecision(raw);
            if (precision < 0) continue;
            stats.total += 1;
            if (precision != majorPrecision) stats.mismatched += 1;
        }
        return stats;
    }

    private int getDecimalPrecision(String line) {
        int start = getExtInfValueStart(line);
        if (start < 0) return -1;
        int end = getExtInfValueEnd(line, start);
        int dot = line.indexOf('.', start);
        return dot < 0 || dot >= end ? 0 : end - dot - 1;
    }

    private String cleanFrameRateGroups(String m3u8Content) {
        List<Group> groups = buildDiscontinuityGroups(m3u8Content.split("\n"));
        if (groups.size() < 2) return m3u8Content;

        int masterFrameRate = findDominantFrameRate(groups);
        if (masterFrameRate == 0) return m3u8Content;

        int removableSegments = 0;
        boolean[] removeGroups = new boolean[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            if (i == groups.size() - 1 || group.segmentCount == 0 || group.segmentCount > MAX_FRAME_RATE_AD_BLOCK_SIZE) continue;
            FrameRateStats stats = getFrameRateStats(group, masterFrameRate);
            if (stats.mismatched > 0 && stats.mismatched >= stats.matched) {
                removeGroups[i] = true;
                removableSegments += group.segmentCount;
            }
        }

        if (removableSegments == 0 || removableSegments > getAdSegmentLimit(m3u8Content)) return m3u8Content;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            if (removeGroups[i]) removed += groups.get(i).segmentCount;
            else groups.get(i).appendTo(sb);
        }
        return normalizeMediaPlaylist(sb.toString());
    }

    private int findDominantFrameRate(List<Group> groups) {
        int count30 = 0;
        int count25 = 0;
        int count24 = 0;
        for (Group group : groups) {
            for (String raw : group.lines) {
                if (getExtInfValueStart(raw) < 0) continue;
                int frameRate = getExclusiveFrameRate(parseExtInfDuration(raw));
                if (frameRate == 30) count30 += 1;
                else if (frameRate == 25) count25 += 1;
                else if (frameRate == 24) count24 += 1;
            }
        }

        int max = Math.max(count30, Math.max(count25, count24));
        if (max < 2) return 0;
        if ((count30 == max ? 1 : 0) + (count25 == max ? 1 : 0) + (count24 == max ? 1 : 0) != 1) return 0;
        return count30 == max ? 30 : (count25 == max ? 25 : 24);
    }

    private FrameRateStats getFrameRateStats(Group group, int masterFrameRate) {
        FrameRateStats stats = new FrameRateStats();
        for (String raw : group.lines) {
            if (getExtInfValueStart(raw) < 0) continue;
            int frameRate = getExclusiveFrameRate(parseExtInfDuration(raw));
            if (frameRate == masterFrameRate) stats.matched += 1;
            else if (frameRate != 0) stats.mismatched += 1;
        }
        return stats;
    }

    private int getExclusiveFrameRate(BigDecimal duration) {
        boolean is30 = isFrameAligned(duration, 30);
        boolean is25 = isFrameAligned(duration, 25);
        boolean is24 = isFrameAligned(duration, 24);
        if (is30 && !is25 && !is24) return 30;
        if (is25 && !is30 && !is24) return 25;
        if (is24 && !is30 && !is25) return 24;
        return 0;
    }

    private boolean isFrameAligned(BigDecimal duration, int frameRate) {
        if (duration == null) return false;
        Set<BigDecimal> features = FRAME_RATE_FEATURES.get(frameRate);
        if (features == null) return false;
        BigDecimal fraction = duration.remainder(BigDecimal.ONE).abs().stripTrailingZeros();
        return features.contains(fraction);
    }

    private static Map<Integer, Set<BigDecimal>> prepareFrameRateFeatures() {
        Map<Integer, Set<BigDecimal>> features = new HashMap<>();
        features.put(30, createFrameRateFeatures(30, true));
        features.put(25, createFrameRateFeatures(25, false));
        features.put(24, createFrameRateFeatures(24, true));
        return features;
    }

    private static Set<BigDecimal> createFrameRateFeatures(int frameRate, boolean includeNtsc) {
        Set<BigDecimal> features = new HashSet<>();
        addFrameRateFeatures(features, frameRate, frameRate);
        if (includeNtsc) addFrameRateFeatures(features, frameRate / 1.001d, frameRate * 10);
        return features;
    }

    private static void addFrameRateFeatures(Set<BigDecimal> features, double frameRate, int maxFrames) {
        BigDecimal rate = BigDecimal.valueOf(frameRate);
        for (int frame = 1; frame <= maxFrames; frame++) {
            BigDecimal fraction = BigDecimal.valueOf(frame).divide(rate, 10, RoundingMode.HALF_UP).remainder(BigDecimal.ONE);
            for (int scale = 3; scale <= 6; scale++) {
                BigDecimal value = fraction.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
                if (value.compareTo(BigDecimal.ZERO) != 0) features.add(value);
            }
        }
    }

    private BigDecimal parseExtInfDuration(String line) {
        int start = getExtInfValueStart(line);
        if (start < 0) return BigDecimal.ZERO;
        int end = getExtInfValueEnd(line, start);
        try {
            return new BigDecimal(line.substring(start, end)).stripTrailingZeros();
        } catch (Exception ignored) {
        }
        return BigDecimal.ZERO;
    }

    private static int getExtInfValueStart(String line) {
        if (line == null) return -1;
        int length = line.length();
        int start = 0;
        while (start < length && line.charAt(start) <= ' ') start += 1;
        if (!line.startsWith(TAG_MEDIA_DURATION, start)) return -1;
        start += TAG_MEDIA_DURATION.length();
        if (start >= length || line.charAt(start) != ':') return -1;
        start += 1;
        while (start < length && line.charAt(start) <= ' ') start += 1;
        return start < length ? start : -1;
    }

    private static int getExtInfValueEnd(String line, int start) {
        int end = line.indexOf(',', start);
        if (end < 0) end = line.length();
        while (end > start && line.charAt(end - 1) <= ' ') end -= 1;
        return end;
    }

    private static int getAdSegmentLimit(String m3u8Content) {
        BigDecimal totalDuration = BigDecimal.ZERO;
        for (String raw : m3u8Content.split("\n")) totalDuration = totalDuration.add(parseDuration(raw));
        double totalMinutes = totalDuration.doubleValue() / 60;
        if (totalMinutes <= 30) return 18;
        if (totalMinutes <= 60) return 24;
        if (totalMinutes <= 90) return 30;
        return 36;
    }

    private static BigDecimal parseDuration(String line) {
        int start = getExtInfValueStart(line);
        if (start < 0) return BigDecimal.ZERO;
        int end = getExtInfValueEnd(line, start);
        try {
            return new BigDecimal(line.substring(start, end)).stripTrailingZeros();
        } catch (Exception ignored) {
        }
        return BigDecimal.ZERO;
    }

    private String resolveContent(String m3u8Content) {
        m3u8Content = m3u8Content.replaceAll("\r\n", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : m3u8Content.split("\n")) {
            sb.append(shouldResolve(line) ? resolve(line.trim()) : line).append("\n");
        }
        return sb.toString();
    }

    private String cleanCommonAdMarkers(String line) {
        StringBuilder sb = new StringBuilder();
        List<String> pending = new ArrayList<>();
        boolean inAdBreak = false;
        boolean changed = false;

        for (String raw : line.split("\n", -1)) {
            String item = raw.trim();
            if (item.length() == 0) {
                if (pending.isEmpty()) sb.append(raw).append("\n");
                else pending.add(raw);
                continue;
            }
            if (item.startsWith("#")) {
                if (item.startsWith(TAG_CUE_IN)) {
                    if (inAdBreak || hasAdSignal(pending)) {
                        inAdBreak = false;
                        pending.clear();
                        changed = true;
                        continue;
                    }
                }
                if (isAdBreakStart(item)) {
                    flush(sb, pending);
                    inAdBreak = true;
                    pending.add(raw);
                    changed = true;
                    continue;
                }
                if (inAdBreak) {
                    pending.add(raw);
                    changed = true;
                    continue;
                }
                if (isStandaloneAdTag(item)) {
                    flush(sb, pending);
                    removed += 1;
                    changed = true;
                    continue;
                }
                if (isSegmentTag(item) || isAdSignalTag(item)) {
                    pending.add(raw);
                } else {
                    flush(sb, pending);
                    sb.append(raw).append("\n");
                }
                continue;
            }

            if (inAdBreak || hasAdSignal(pending) || isAdSegmentUri(item) || hasAdDomain(item) || isConfigAdUri(item)) {
                pending.clear();
                removed += 1;
                changed = true;
                continue;
            }
            flush(sb, pending);
            sb.append(raw).append("\n");
        }

        if (!inAdBreak) flush(sb, pending);
        return changed ? sb.toString() : line;
    }

    private static void flush(StringBuilder sb, List<String> pending) {
        for (String line : pending) sb.append(line).append("\n");
        pending.clear();
    }

    private static void flush(StringBuilder sb, List<String> pending, String linesplit) {
        for (String line : pending) appendLine(sb, line, linesplit);
        pending.clear();
    }

    private static void appendLine(StringBuilder sb, String line, String linesplit) {
        sb.append(line).append(linesplit);
    }

    private boolean hasAdSignal(List<String> pending) {
        for (String line : pending) {
            String trimmed = line.trim();
            if (isAdBreakStart(trimmed) || isAdSignalTag(trimmed)) return true;
        }
        return false;
    }

    private static boolean isAdBreakStart(String line) {
        return line.startsWith(TAG_CUE_OUT);
    }

    private static boolean isAdSignalTag(String line) {
        if (line.startsWith("#EXT-OATCLS-SCTE35")) return true;
        if (line.startsWith("#EXT-X-SCTE35")) return true;
        if (line.startsWith("#EXT-X-SPLICEPOINT-SCTE35")) return true;
        if (line.startsWith("#EXT-X-CUE")) return true;
        if (line.startsWith("#EXT-X-ASSET")) return true;
        if (line.startsWith("#EXT-X-VMAP-AD-BREAK")) return true;
        if (line.startsWith("#EXT-X-AD")) return true;
        return false;
    }

    private static boolean isSegmentTag(String line) {
        if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) return false;
        return line.startsWith(TAG_MEDIA_DURATION) || line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME") || line.startsWith(TAG_DISCONTINUITY) || line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT");
    }

    private static boolean isStandaloneAdTag(String line) {
        if (!line.startsWith(TAG_DATERANGE)) return false;
        return isAdLikeText(line) || line.contains("X-ASSET-URI") || line.contains("X-ASSET-LIST");
    }

    private static boolean isAdLikeText(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("scte") || lower.contains("cue") || lower.contains("interstitial") ||
                lower.contains("vmap") || lower.contains("vast") || lower.contains("advert") ||
                lower.contains("commercial") || lower.contains("ad-") || lower.contains("ad_") ||
                lower.contains("ad.") || lower.contains("preroll") || lower.contains("midroll") ||
                lower.contains("postroll") || lower.contains("bumper");
    }

    private static boolean isAdSegmentUri(String line) {
        return REGEX_AD_SEGMENT_URI.matcher(line).find();
    }

    private boolean isConfigAdUri(String line) {
        if (configAds.isEmpty()) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        for (String ad : configAds) {
            if (ad == null || ad.isEmpty()) continue;
            if (lower.contains(ad.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean hasAdDomain(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String keyword : AD_DOMAIN_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private String cleanDiscontinuityGroups(String m3u8Content) {
        String[] lines = m3u8Content.split("\n");
        List<Group> groups = buildDiscontinuityGroups(lines);
        if (groups.size() < 3) return m3u8Content;
        Group main = findMainGroup(groups);
        if (main == null || main.segmentCount < 3) return m3u8Content;

        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (Group group : groups) {
            if (shouldDropGroup(group, main)) {
                removed += group.segmentCount;
                changed = true;
                continue;
            }
            group.appendTo(sb);
        }
        return changed ? sb.toString() : m3u8Content;
    }

    private List<Group> buildDiscontinuityGroups(String[] lines) {
        List<Group> groups = new ArrayList<>();
        Group group = new Group();
        for (String raw : lines) {
            String line = raw.trim();
            if (isDiscontinuityTag(line) && group.hasMedia()) {
                groups.add(group);
                group = new Group();
            }
            group.add(raw);
        }
        if (group.hasMedia() || !group.lines.isEmpty()) groups.add(group);
        return groups;
    }

    private Group findMainGroup(List<Group> groups) {
        Group main = null;
        for (Group group : groups) {
            if (group.segmentCount == 0) continue;
            if (main == null || group.score() > main.score()) main = group;
        }
        return main;
    }

    private boolean shouldDropGroup(Group group, Group main) {
        if (group == main || group.segmentCount == 0) return false;

        boolean shortGroup = group.segmentCount <= 2 ||
                (main.totalDuration > 0 && group.totalDuration > 0 &&
                        group.totalDuration < main.totalDuration * MAIN_DURATION_RATIO);

        boolean differentHost = main.host.length() > 0 && group.host.length() > 0 &&
                !main.host.equals(group.host);

        boolean differentPath = main.pathPrefix.length() > 0 && group.pathPrefix.length() > 0 &&
                !main.pathPrefix.equals(group.pathPrefix);

        boolean hasAdFeature = group.adLikeCount > 0 || hasAdDomain(group.host) ||
                isAdSegmentUri(group.pathPrefix) || isConfigAdUri(group.pathPrefix);

        boolean adLike = hasAdFeature || differentHost || (group.segmentCount <= 2 && differentPath);

        return shortGroup && adLike;
    }

    private static String hostOf(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "";
        int start = url.indexOf("://") + 3;
        int end = url.indexOf('/', start);
        return end > start ? url.substring(start, end) : url.substring(start);
    }

    private static String pathPrefixOf(String url) {
        String clean = url;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int slash = clean.lastIndexOf('/');
        return slash > 0 ? clean.substring(0, slash + 1) : "";
    }

    private String toAbsoluteUrl(String url) {
        String line = url == null ? "" : url.trim();
        if (line.length() == 0 || line.startsWith("http://") || line.startsWith("https://")) return line;
        if (base == null) return line;
        HttpUrl resolved = base.resolve(line);
        return resolved == null ? line : resolved.toString();
    }

    private boolean shouldKeepMediaUrl(String absoluteUrl, boolean domainFiltering, String maxTimesPreUrl, HashMap<String, Integer> preUrlMap) {
        if (!domainFiltering) return absoluteUrl.startsWith(maxTimesPreUrl);
        int ifirst = absoluteUrl.indexOf('/', 9);
        String domain = (ifirst > 0) ? absoluteUrl.substring(0, ifirst) : absoluteUrl;
        Integer cnt = preUrlMap.get(domain);
        return domain.equals(maxTimesPreUrl) || (cnt != null && cnt > MINORITY_DOMAIN_KEEP_THRESHOLD);
    }

    private boolean hasUriAttribute(String line) {
        return line.startsWith(TAG_KEY) || line.startsWith(TAG_MAP);
    }

    private String resolveUriLine(String line) {
        Matcher matcher = REGEX_URI.matcher(line);
        String value = matcher.find() ? matcher.group(1) : null;
        if (value == null || base == null) return line;
        HttpUrl resolved = base.resolve(value);
        return resolved == null ? line : line.replace(value, resolved.toString());
    }

    private String resolve(String line) {
        if (hasUriAttribute(line)) return resolveUriLine(line);
        return toAbsoluteUrl(line);
    }

    private static String normalizeMediaPlaylist(String content) {
        StringBuilder sb = new StringBuilder();
        boolean seenMedia = false;
        boolean hasPendingDiscontinuity = false;
        String pendingDiscontinuity = "";
        for (String raw : content.replaceAll("\r\n", "\n").split("\n", -1)) {
            String item = raw.trim();
            if (isDiscontinuityTag(item)) {
                if (seenMedia && !hasPendingDiscontinuity) {
                    pendingDiscontinuity = raw;
                    hasPendingDiscontinuity = true;
                }
                continue;
            }
            if (hasPendingDiscontinuity) {
                if (item.length() == 0) continue;
                if (!item.startsWith(TAG_ENDLIST)) sb.append(pendingDiscontinuity).append("\n");
                hasPendingDiscontinuity = false;
            }
            if (item.length() == 0 && sb.length() == 0) continue;
            sb.append(raw).append("\n");
            if (isMediaUriLine(item)) seenMedia = true;
        }
        return sb.toString();
    }

    private static boolean isPlayableMediaPlaylist(String content) {
        if (content == null || !content.startsWith("#EXTM3U")) return false;
        int mediaCount = 0;
        boolean pendingExtInf = false;
        for (String raw : content.replaceAll("\r\n", "\n").split("\n")) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith(TAG_MEDIA_DURATION)) {
                if (pendingExtInf) return false;
                pendingExtInf = true;
            } else if (isMediaUriLine(line)) {
                mediaCount += 1;
                pendingExtInf = false;
            } else if (line.startsWith(TAG_ENDLIST) && pendingExtInf) {
                return false;
            }
        }
        return mediaCount > 0 && !pendingExtInf;
    }

    private String keepVodEndList(String original, String result) {
        if (result == null) return null;
        if (!hasEndList(original) || hasEndList(result)) return result;
        return result + (result.endsWith("\n") ? "" : "\n") + TAG_ENDLIST + "\n";
    }

    private static boolean hasEndList(String content) {
        if (content == null) return false;
        for (String raw : content.replaceAll("\r\n", "\n").split("\n")) {
            if (raw.trim().startsWith(TAG_ENDLIST)) return true;
        }
        return false;
    }

    private static boolean isMediaUriLine(String line) {
        return line.length() > 0 && !line.startsWith("#");
    }

    private static boolean isDiscontinuityTag(String line) {
        return line.startsWith(TAG_DISCONTINUITY) && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE");
    }

    private boolean shouldResolve(String line) {
        String item = line.trim();
        if (item.length() == 0) return false;
        return (!item.startsWith("#") && !item.startsWith("http")) || hasUriAttribute(item);
    }

    private static class FrameRateStats {
        private int matched;
        private int mismatched;
    }

    private static class DecimalPrecisionStats {
        private int total;
        private int mismatched;
    }

    private class Group {
        private final List<String> lines = new ArrayList<>();
        private int segmentCount = 0;
        private int adLikeCount = 0;
        private double totalDuration = 0;
        private String host = "";
        private String pathPrefix = "";

        private void add(String raw) {
            lines.add(raw);
            String line = raw.trim();
            int durationStart = getExtInfValueStart(line);
            if (durationStart >= 0) {
                int durationEnd = getExtInfValueEnd(line, durationStart);
                try {
                    totalDuration += Double.parseDouble(line.substring(durationStart, durationEnd));
                } catch (Exception ignored) {
                }
            }
            if (line.length() == 0 || line.startsWith("#")) {
                if (isAdSignalTag(line) || isStandaloneAdTag(line)) adLikeCount += 1;
                return;
            }
            segmentCount += 1;
            if (isAdSegmentUri(line) || hasAdDomain(line) || isConfigAdUri(line)) adLikeCount += 1;
            if (host.length() == 0) host = hostOf(toAbsoluteUrl(line));
            if (pathPrefix.length() == 0) pathPrefix = pathPrefixOf(toAbsoluteUrl(line));
        }

        private boolean hasMedia() {
            return segmentCount > 0;
        }

        private void appendTo(StringBuilder sb) {
            for (String line : lines) sb.append(line).append("\n");
        }

        private double score() {
            return totalDuration > 0 ? totalDuration : segmentCount;
        }
    }
}
