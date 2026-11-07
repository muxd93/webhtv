package com.fongmi.android.tv.player.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 两端共用歌词纯逻辑：歌手推断、内嵌歌词过滤、结果身份/选中态、建议合并、偏移格式。
 * 依赖 TextUtils，用 Robolectric 提供 Android 实现，不创建任何 View。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LyricsSupportTest {

    @Test
    public void artistFromEpisodeHandlesBothOrders() {
        assertEquals("周杰伦", LyricsSupport.artistFromEpisode("晴天", "周杰伦 - 晴天"));
        assertEquals("周杰伦", LyricsSupport.artistFromEpisode("晴天", "晴天 - 周杰伦"));
        assertEquals("周杰伦", LyricsSupport.artistFromEpisode("晴天", "周杰伦-晴天"));
        assertEquals("周杰伦", LyricsSupport.artistFromEpisode("晴天", "晴天 – 周杰伦"));
        // 无法分离时整串视为歌手，保持旧行为
        assertEquals("周杰伦", LyricsSupport.artistFromEpisode("晴天", "周杰伦"));
        assertEquals("", LyricsSupport.artistFromEpisode("晴天", "晴天"));
        assertEquals("", LyricsSupport.artistFromEpisode("", "周杰伦 - 晴天"));
        assertEquals("", LyricsSupport.artistFromEpisode(null, null));
    }

    @Test
    public void cleanEpisodeDropsSourcePrefix() {
        assertEquals("第3集", LyricsSupport.cleanEpisodeForArtist("源|第3集"));
        assertEquals("第3集", LyricsSupport.cleanEpisodeForArtist("源｜第3集"));
        assertEquals("第3集", LyricsSupport.cleanEpisodeForArtist("第3集"));
        assertEquals("", LyricsSupport.cleanEpisodeForArtist(""));
        assertEquals("", LyricsSupport.cleanEpisodeForArtist(null));
    }

    @Test
    public void timedLyricsRejectsPlainText() {
        String timed = "[00:01.00]第一句\n[00:05.00]第二句";
        assertEquals(timed, LyricsSupport.timedLyrics(timed));
        assertEquals("", LyricsSupport.timedLyrics("只有文字没有时间轴"));
        assertEquals("", LyricsSupport.timedLyrics(""));
        assertEquals("", LyricsSupport.timedLyrics(null));
    }

    @Test
    public void resultKeyDistinguishesAndMatchesSelection() {
        LyricsResult one = new LyricsResult("Detail", "晴天", "周杰伦", "", "[00:01.00]第一句", 200000, true, 100);
        LyricsResult two = new LyricsResult("QQ", "晴天", "周杰伦", "", "[00:01.00]第一句", 200000, true, 100);
        assertEquals("", LyricsSupport.resultKey(null));
        assertNotEquals(LyricsSupport.resultKey(one), LyricsSupport.resultKey(two));

        List<LyricsResult> results = Arrays.asList(one, two);
        assertEquals(0, LyricsSupport.selectedIndex(results, LyricsSupport.resultKey(one)));
        assertEquals(1, LyricsSupport.selectedIndex(results, LyricsSupport.resultKey(two)));
        assertEquals(-1, LyricsSupport.selectedIndex(results, ""));
        assertEquals(-1, LyricsSupport.selectedIndex(results, "no-such-key"));
        assertEquals(-1, LyricsSupport.selectedIndex(null, "any"));
    }

    @Test
    public void lastSuggestionOnlyForSameSong() {
        List<String> suggestions = Arrays.asList("晴天", "周杰伦", "", "晴天");
        assertEquals(Arrays.asList("手输关键词", "晴天", "周杰伦"),
                LyricsSupport.withLastSuggestion(suggestions, "手输关键词", "sig-a", "sig-a"));
        // 切歌后签名不同，必须原样返回，不能把上一首的关键词带过来
        assertEquals(suggestions, LyricsSupport.withLastSuggestion(suggestions, "手输关键词", "sig-a", "sig-b"));
        assertEquals(suggestions, LyricsSupport.withLastSuggestion(suggestions, "  ", "sig-a", "sig-a"));
    }

    @Test
    public void lastSuggestionCapsAtEightEntries() {
        List<String> suggestions = new ArrayList<>();
        for (int i = 0; i < 12; i++) suggestions.add("建议" + i);
        List<String> merged = LyricsSupport.withLastSuggestion(suggestions, "关键词", "sig", "sig");
        assertEquals(8, merged.size());
        assertEquals("关键词", merged.get(0));
    }

    @Test
    public void suggestionDedupeIgnoresCase() {
        assertTrue(LyricsSupport.containsSuggestion(Arrays.asList("Jay"), "jay"));
        assertFalse(LyricsSupport.containsSuggestion(Arrays.asList("Jay"), "周杰伦"));
        assertFalse(LyricsSupport.containsSuggestion(new ArrayList<>(), "任何"));
    }

    @Test
    public void suggestionSignatureFallsBackWithoutTitle() {
        LyricsRequest request = new LyricsRequest("sig", "", "晴天", "周杰伦", "", 200000);
        assertEquals("lyrics-ui|晴天|周杰伦|200", LyricsSupport.suggestionSignature(request, "fallback"));
        assertEquals("fallback", LyricsSupport.suggestionSignature(null, "fallback"));
        assertEquals("", LyricsSupport.suggestionSignature(null, null));
        assertEquals("fallback", LyricsSupport.suggestionSignature(new LyricsRequest("sig", "", "", "周杰伦", "", 200000), "fallback"));
    }

    @Test
    public void offsetFormatIsStable() {
        assertEquals("0s", LyricsSupport.formatOffset(0));
        assertEquals("+1.5s", LyricsSupport.formatOffset(1500));
        assertEquals("-2.5s", LyricsSupport.formatOffset(-2500));
    }
}
