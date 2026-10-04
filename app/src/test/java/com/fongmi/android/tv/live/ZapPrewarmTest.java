package com.fongmi.android.tv.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 换台预热资格回归：仅 http(s) 直连线路可预热，其他协议一律跳过；顺延选择按原序取前两条。 */
public class ZapPrewarmTest {

    @Test
    public void onlyHttpHttpsArePrewarmable() {
        assertTrue(ZapPrewarm.isPrewarmable("http://a/stream"));
        assertTrue(ZapPrewarm.isPrewarmable("HTTPS://a/stream"));
        assertFalse(ZapPrewarm.isPrewarmable("rtp://239.1.1.1:5000"));
        assertFalse(ZapPrewarm.isPrewarmable("rtsp://x/y"));
        assertFalse(ZapPrewarm.isPrewarmable("proxy://do=py&url=x"));
        assertFalse(ZapPrewarm.isPrewarmable("file:///mnt/x.ts"));
        assertFalse(ZapPrewarm.isPrewarmable("assets://x.m3u"));
        assertFalse(ZapPrewarm.isPrewarmable(""));
        assertFalse(ZapPrewarm.isPrewarmable(null));
    }

    @Test
    public void prewarmableLinesPicksFirstTwoInOrder() {
        List<String> lines = ZapPrewarm.prewarmableLines(Arrays.asList("rtp://x", "http://a", "https://b", "http://c"), 2);
        assertEquals(Arrays.asList("http://a", "https://b"), lines);
        assertTrue(ZapPrewarm.prewarmableLines(Arrays.asList("rtp://x", "file:///y"), 2).isEmpty());
        assertEquals(Arrays.asList("http://a"), ZapPrewarm.prewarmableLines(Arrays.asList("http://a"), 2));
    }
}
