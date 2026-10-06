package com.fongmi.android.tv.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 换台预热资格回归：仅 http(s) 直连线路可预热，其他协议与 loopback 一律跳过；顺延选择按原序取前两条。 */
public class ZapPrewarmTest {

    @After
    public void reset() {
        ZapPrewarm.markWarmedForTest(null, 0);
    }

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
    public void loopbackLinesAreNotPrewarmable() {
        assertFalse(ZapPrewarm.isPrewarmable("http://127.0.0.1:9978/proxy?do=live"));
        assertFalse(ZapPrewarm.isPrewarmable("http://localhost/stream.m3u8"));
        assertFalse(ZapPrewarm.isPrewarmable("http://[::1]:8080/live"));
        assertFalse(ZapPrewarm.isPrewarmable("https://LOCALHOST/x"));
        assertTrue(ZapPrewarm.isPrewarmable("http://192.168.1.10:8080/live.m3u8"));
        assertTrue(ZapPrewarm.isPrewarmable("https://cdn.example.com/live.m3u8"));
    }

    @Test
    public void prewarmableLinesPicksFirstTwoInOrder() {
        List<String> lines = ZapPrewarm.prewarmableLines(Arrays.asList("rtp://x", "http://a", "https://b", "http://c"), 2);
        assertEquals(Arrays.asList("http://a", "https://b"), lines);
        assertTrue(ZapPrewarm.prewarmableLines(Arrays.asList("rtp://x", "file:///y"), 2).isEmpty());
        assertEquals(Arrays.asList("http://a"), ZapPrewarm.prewarmableLines(Arrays.asList("http://a"), 2));
    }

    @Test
    public void prewarmableLinesSkipsLoopbackLines() {
        List<String> lines = ZapPrewarm.prewarmableLines(Arrays.asList("http://127.0.0.1:9978/proxy", "http://a", "https://b"), 2);
        assertEquals(Arrays.asList("http://a", "https://b"), lines);
    }

    @Test
    public void sameEndpointMatchesHostAndPortOnly() {
        assertTrue(ZapPrewarm.sameEndpoint("http://a/live/1.m3u8", "http://a/live/2.ts"));
        assertTrue(ZapPrewarm.sameEndpoint("http://a/x", "http://a:80/y"));
        assertTrue(ZapPrewarm.sameEndpoint("https://a/x", "https://a:443/y"));
        assertFalse(ZapPrewarm.sameEndpoint("http://a:8080/x", "http://a/x"));
        assertFalse(ZapPrewarm.sameEndpoint("http://a/x", "https://a/x"));
        assertFalse(ZapPrewarm.sameEndpoint("http://a/x", "rtp://a/x"));
        assertFalse(ZapPrewarm.sameEndpoint(null, "http://a/x"));
        assertFalse(ZapPrewarm.sameEndpoint("http://a/x", null));
    }

    @Test
    public void endpointOfExtractsHostPortAndMasksInvalid() {
        assertEquals("a:80", ZapPrewarm.endpointOf("http://a/x"));
        assertEquals("a:8080", ZapPrewarm.endpointOf("http://a:8080/live.m3u8"));
        assertEquals("b:443", ZapPrewarm.endpointOf("https://B/y?token=1"));
        assertEquals("(invalid)", ZapPrewarm.endpointOf("rtp://x"));
        assertEquals("(invalid)", ZapPrewarm.endpointOf(null));
    }

    @Test
    public void isWarmedRequiresSameEndpointInsidePoolWindow() {
        long now = System.currentTimeMillis();
        ZapPrewarm.markWarmedForTest("a:80", now - 1000);
        assertTrue(ZapPrewarm.isWarmed("http://a/live.m3u8"));
        assertFalse(ZapPrewarm.isWarmed("http://a:8080/live.m3u8"));
        assertFalse(ZapPrewarm.isWarmed("https://a/live.m3u8"));
        assertFalse(ZapPrewarm.isWarmed(null));
        ZapPrewarm.markWarmedForTest("a:80", now - 6 * 60 * 1000);
        assertFalse(ZapPrewarm.isWarmed("http://a/live.m3u8"));
    }
}
