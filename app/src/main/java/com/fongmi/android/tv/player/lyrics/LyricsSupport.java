package com.fongmi.android.tv.player.lyrics;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 歌词子系统的两端共用纯逻辑：歌手推断、内嵌歌词过滤、搜索结果标识与偏移格式化。
 * 不持有 View / Context / Handler，可在 JVM 单测里直接驱动。
 * 只收敛 leanback 与 mobile 逐字相同的部分；flavor 特有的焦点、手势、PiP 与布局留在各自 Activity。
 */
public final class LyricsSupport {

    private static final String[] ARTIST_SEPARATORS = {" - ", " – ", " — ", "-"};
    private static final int SUGGESTION_LIMIT = 8;

    private LyricsSupport() {
    }

    /** 从剧集名还原歌手：支持 "歌手 - 歌名"、 "歌名 - 歌手" 及破折号变体，无法分离时整串视为歌手 */
    public static String artistFromEpisode(String title, String episode) {
        String name = Objects.toString(title, "").trim();
        String value = Objects.toString(episode, "").trim();
        if (name.isEmpty() || value.isEmpty() || TextUtils.equals(name, value)) return "";
        for (String separator : ARTIST_SEPARATORS) {
            if (value.startsWith(name + separator) && value.length() > name.length() + separator.length()) {
                return value.substring(name.length() + separator.length()).trim();
            }
            if (value.endsWith(separator + name) && value.length() > name.length() + separator.length()) {
                return value.substring(0, value.length() - name.length() - separator.length()).trim();
            }
        }
        return value;
    }

    /** 多段剧集名（"源|集名"）只取最后一段作为歌手推断输入 */
    public static String cleanEpisodeForArtist(String episode) {
        String value = Objects.toString(episode, "").trim();
        if (value.isEmpty()) return "";
        String[] parts = value.split("[|｜]");
        return parts.length == 0 ? value : parts[parts.length - 1].trim();
    }

    /** 只接受带时间轴的歌词文本，其余一律视为无内嵌歌词 */
    public static String timedLyrics(String text) {
        return LyricsController.hasTimedLyrics(text) ? text : "";
    }

    /** 搜索结果身份：选择态跨重新搜索时仍能对齐同一结果 */
    public static String resultKey(LyricsResult result) {
        if (result == null) return "";
        return TextUtils.join("|", new String[]{
                String.valueOf(result.getSource()),
                String.valueOf(result.getTrackName()),
                String.valueOf(result.getArtistName()),
                String.valueOf(Math.round(result.getDurationMs() / 1000.0)),
                String.valueOf(result.hasWordTiming()),
                String.valueOf(result.getLyrics() == null ? 0 : result.getLyrics().hashCode())
        });
    }

    /** 当前选中项在结果列表中的下标，找不到或未选中返回 -1 */
    public static int selectedIndex(List<LyricsResult> results, String selectedKey) {
        if (TextUtils.isEmpty(selectedKey) || results == null) return -1;
        for (int i = 0; i < results.size(); i++) {
            if (TextUtils.equals(selectedKey, resultKey(results.get(i)))) return i;
        }
        return -1;
    }

    /** leanback 的搜索建议身份：标题/歌手/时长都没拿到时退回传入的兜底值 */
    public static String suggestionSignature(@Nullable LyricsRequest request, String fallback) {
        if (request == null || TextUtils.isEmpty(request.getTitle())) return Objects.toString(fallback, "");
        return "lyrics-ui|" + request.getTitle() + "|" + request.getArtist() + "|" + request.getDurationSec();
    }

    /** 把上一次手输关键词提到建议列表首位（仅当仍是同一首歌），去重并限制在 8 条内 */
    public static List<String> withLastSuggestion(List<String> suggestions, String lastKeyword, String lastSignature, String signature) {
        String keyword = Objects.toString(lastKeyword, "").trim();
        if (TextUtils.isEmpty(keyword) || !TextUtils.equals(lastSignature, signature)) return suggestions;
        List<String> values = new ArrayList<>();
        values.add(keyword);
        for (String suggestion : suggestions) {
            String value = Objects.toString(suggestion, "").trim();
            if (TextUtils.isEmpty(value) || containsSuggestion(values, value)) continue;
            values.add(value);
            if (values.size() >= SUGGESTION_LIMIT) break;
        }
        return values;
    }

    /** 忽略大小写与重复空白后的建议去重 */
    public static boolean containsSuggestion(List<String> suggestions, String keyword) {
        for (String suggestion : suggestions) if (suggestion.equalsIgnoreCase(keyword)) return true;
        return false;
    }

    /** 歌词/卡拉 OK 偏移的统一展示格式：0 显示 "0s"，其余带符号保留一位小数 */
    public static String formatOffset(long valueMs) {
        if (valueMs == 0) return "0s";
        return String.format(Locale.getDefault(), "%+.1fs", valueMs / 1000f);
    }

    /** 播放位置叠加全局歌词偏移，负数按 0 处理 */
    public static long adjust(long positionMs, long offsetMs) {
        return Math.max(0, positionMs + offsetMs);
    }
}
