package com.fongmi.android.tv.player.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词会话状态机：切歌重置、陈旧回调丢弃、失败只记空态。
 * 这里驱动的是从 LyricsController 抽出的纯状态，不经过 View/Player。
 * LyricsResult 内部用 TextUtils，需要 Robolectric 提供 Android 实现（不创建 View）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LyricsSessionTest {

    private static LyricsResult result() {
        return new LyricsResult("Detail", "晴天", "周杰伦", "", "[00:01.00]第一句\n[00:05.00]第二句", 200000, true, 100);
    }

    private static List<LyricsLine> lines(LyricsResult result) {
        return new ArrayList<>(result.getLines(200000));
    }

    @Test
    public void trackChangeResetsLinesAndDropsStaleCallback() {
        LyricsSession session = new LyricsSession();
        LyricsResult first = result();
        List<LyricsLine> firstLines = lines(first);
        assertEquals(2, firstLines.size());

        int firstLoad = session.begin("track-a");
        session.applyLoaded("track-a", first, firstLines);
        assertEquals(2, session.lines().size());
        assertEquals("track-a", session.active());

        // 切歌：同一会话进入新签名，行必须立刻清空，且旧序号回调作废
        int secondLoad = session.begin("track-b");
        assertTrue(session.lines().isEmpty());
        assertNull(session.active());
        assertFalse(session.isCurrent(firstLoad));
        assertTrue(session.isCurrent(secondLoad));
    }

    @Test
    public void offsetSurvivesTrackChange() {
        // 偏移只来自 PlayerSetting，不属于会话状态：切歌（clear/begin）不会改变它的计算结果
        long offset = 1200;
        assertEquals(6200, LyricsSupport.adjust(5000, offset));
        assertEquals(6200, LyricsSupport.adjust(5000, offset));

        LyricsSession session = new LyricsSession();
        session.begin("track-a");
        session.applyLoaded("track-a", result(), lines(result()));
        session.clear();
        session.begin("track-b");
        assertEquals(6200, LyricsSupport.adjust(5000, offset));
        assertEquals(0, LyricsSupport.adjust(0, -3000));
    }

    @Test
    public void failedLoadKeepsLinesEmptyMarkedEmptyAndRetryable() {
        LyricsSession session = new LyricsSession();
        session.begin("track-a");
        session.markEmpty("track-a");

        assertTrue(session.lines().isEmpty());
        assertNull(session.result());
        assertNull(session.loading());
        assertEquals("track-a", session.empty());
        // 同签名被记为已知，不会在每次进度回调里反复打网络
        assertTrue(session.isKnown("track-a"));

        // 用户主动换源：清掉空态并进入 loading，允许同签名重新请求；loading 结束后才算真正可重试
        session.beginReload("track-a");
        assertNull(session.empty());
        assertEquals("track-a", session.loading());
        session.settleLoading();
        assertFalse(session.isKnown("track-a"));
    }

    @Test
    public void inlineSuccessKeepsSignatureAndUpgradeOnlyReplacesLines() {
        LyricsSession session = new LyricsSession();
        int current = session.nextSequence();
        session.applyChoice("inline-key", result(), lines(result()));
        assertEquals("inline-key", session.active());

        // 内嵌歌词升级为逐字歌词：签名不变，只替换行
        session.markLoading("upgrade-signature");
        session.applyLines(result(), lines(result()));
        assertNotNull(session.result());
        assertEquals("inline-key", session.active());
        assertTrue(session.isCurrent(current));
    }
}
