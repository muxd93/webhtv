package com.fongmi.android.tv.live;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Channel;

import org.junit.After;
import org.junit.Test;

/** 被动线路健康记分回归：连续失败沉底、成功回血、稳定双桶序与选中线路保位。 */
public class LineHealthTest {

    @After
    public void reset() {
        LineHealth.resetForTest();
        LineBlockStore.resetForTest();
    }

    @Test
    public void consecutiveFailuresSinkOnlyAfterThreshold() {
        LineHealth.failure("http://a");
        LineHealth.failure("http://a");
        assertFalse(LineHealth.isSunk("http://a"));
        LineHealth.failure("http://a");
        assertTrue(LineHealth.isSunk("http://a"));
    }

    @Test
    public void successRecoversSunkLine() {
        for (int i = 0; i < 3; i++) LineHealth.failure("http://a");
        assertTrue(LineHealth.isSunk("http://a"));
        LineHealth.success("http://a");
        assertFalse(LineHealth.isSunk("http://a"));
    }

    @Test
    public void failureReportsJustSunkExactlyAtThreshold() {
        assertFalse(LineHealth.failure("http://a"));
        assertFalse(LineHealth.failure("http://a"));
        assertTrue(LineHealth.failure("http://a"));
        assertFalse(LineHealth.failure("http://a"));
        LineHealth.success("http://a");
        assertFalse(LineHealth.failure("http://a"));
    }

    @Test
    public void orderKeepsStableBuckets() {
        assertArrayEquals(new int[]{1, 3, 4, 0, 2}, LineHealth.order(5, i -> i == 0 || i == 2));
        assertArrayEquals(new int[]{0, 1, 2}, LineHealth.order(3, i -> false));
        assertArrayEquals(new int[]{0, 1, 2}, LineHealth.order(3, i -> true));
    }

    @Test
    public void reorderSinksChannelLineAndKeepsNamesAndSelection() {
        Channel channel = new Channel();
        channel.addLine("http://a", "A");
        channel.addLine("http://b", "B");
        channel.addLine("http://c", "C");
        channel.setIndex(0);
        for (int i = 0; i < 3; i++) LineHealth.failure("http://a");
        LineHealth.reorder(channel);
        assertEquals("[http://b, http://c, http://a]", channel.getUrls().toString());
        assertEquals("[B, C, A]", channel.getLineNames().toString());
        assertEquals("http://a", channel.getUrls().get(channel.getIndex()));
    }

    @Test
    public void reorderSkipsHealthyAndSingleLineChannels() {
        Channel healthy = new Channel();
        healthy.addLine("http://a", "A");
        healthy.addLine("http://b", "B");
        LineHealth.reorder(healthy);
        assertEquals("[http://a, http://b]", healthy.getUrls().toString());
        Channel single = new Channel();
        single.addLine("http://a", "A");
        for (int i = 0; i < 3; i++) LineHealth.failure("http://a");
        LineHealth.reorder(single);
        assertEquals("[http://a]", single.getUrls().toString());
    }

    @Test
    public void reorderPutsBlockedAfterSunkAndKeepsSelection() {
        Channel channel = new Channel();
        channel.addLine("http://a", "A");
        channel.addLine("http://b", "B");
        channel.addLine("http://c", "C");
        channel.addLine("http://d", "D");
        channel.setIndex(3);
        for (int i = 0; i < 3; i++) LineHealth.failure("http://d");
        LineBlockStore.block("http://b", "B");
        LineHealth.reorder(channel);
        assertEquals("[http://a, http://c, http://d, http://b]", channel.getUrls().toString());
        assertEquals("[A, C, D, B]", channel.getLineNames().toString());
        assertEquals("http://d", channel.getUrls().get(channel.getIndex()));
    }

    @Test
    public void reorderKeepsOrderWhenAllLinesBlocked() {
        Channel channel = new Channel();
        channel.addLine("http://a", "A");
        channel.addLine("http://b", "B");
        channel.setIndex(1);
        LineBlockStore.block("http://a", "");
        LineBlockStore.block("http://b", "");
        LineHealth.reorder(channel);
        assertEquals("[http://a, http://b]", channel.getUrls().toString());
        assertEquals(1, channel.getIndex());
    }
}
