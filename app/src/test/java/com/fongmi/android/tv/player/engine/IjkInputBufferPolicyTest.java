package com.fongmi.android.tv.player.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkInputBufferPolicyTest {

    @Test
    public void realtimeProtocolsStayOnFiniteQueue() {
        String[] urls = {
                "rtsp://example.com/live",
                "rtp://239.0.0.1:5004",
                "udp://239.0.0.1:5000",
                "rtmp://example.com/live/stream"
        };

        for (String url : urls) {
            IjkInputBufferPolicy.Decision decision = IjkInputBufferPolicy.resolve(url, 15, 256L * 1024 * 1024);
            assertTrue("url=" + url, decision.realtime());
            assertFalse("url=" + url, decision.infiniteBuffer());
            assertEquals(15L * 1024 * 1024, decision.maxBufferBytes());
        }
    }

    @Test
    public void bufferCapacityIsNormalizedToFiniteSupportedLevels() {
        assertDecision(4, 4, 4L * 1024 * 1024);
        assertDecision(5, 8, 8L * 1024 * 1024);
        assertDecision(8, 8, 8L * 1024 * 1024);
        assertDecision(9, 15, 15L * 1024 * 1024);
        assertDecision(Integer.MAX_VALUE, 15, 15L * 1024 * 1024);
    }

    @Test
    public void vodUsesConfiguredMemoryCapacity() {
        IjkInputBufferPolicy.Decision decision = IjkInputBufferPolicy.resolve(
                "https://example.com/movie.mp4",
                15,
                256L * 1024 * 1024);

        assertFalse(decision.realtime());
        assertEquals(256L * 1024 * 1024, decision.maxBufferBytes());
        assertFalse(decision.infiniteBuffer());
    }

    @Test
    public void nonRealtimeAndHttpHlsRemainFiniteWithoutExpandingLiveDetection() {
        for (String url : new String[]{null, "", "https://example.com/live.m3u8", "file:///video.mp4"}) {
            IjkInputBufferPolicy.Decision decision = IjkInputBufferPolicy.resolve(url, 15, 0);
            assertFalse(decision.realtime());
            assertFalse(decision.infiniteBuffer());
            assertEquals(15L * 1024 * 1024, decision.maxBufferBytes());
        }
    }

    @Test
    public void outOfRangeCapacityIsClampedWithoutEnablingInfiniteBuffer() {
        IjkInputBufferPolicy.Decision below = IjkInputBufferPolicy.resolve("RTSP://example.com/live", Integer.MIN_VALUE, 0);
        IjkInputBufferPolicy.Decision above = IjkInputBufferPolicy.resolve("RTMP://example.com/live", Integer.MAX_VALUE, 0);

        assertEquals(4, below.bufferMb());
        assertEquals(15, above.bufferMb());
        assertTrue(below.realtime());
        assertTrue(above.realtime());
        assertFalse(below.infiniteBuffer());
        assertFalse(above.infiniteBuffer());
    }

    private void assertDecision(int configuredMb, int expectedMb, long expectedBytes) {
        IjkInputBufferPolicy.Decision decision = IjkInputBufferPolicy.resolve("rtsp://example.com/live", configuredMb, 0);
        assertEquals(expectedMb, decision.bufferMb());
        assertEquals(expectedBytes, decision.maxBufferBytes());
        assertFalse(decision.infiniteBuffer());
    }
}
