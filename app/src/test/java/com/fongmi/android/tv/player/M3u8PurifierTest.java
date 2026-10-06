package com.fongmi.android.tv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class M3u8PurifierTest {

    private static final String BASE = "https://cdn.example.com/live/playlist.m3u8";

    private String segment(String url, double seconds) {
        return "#EXTINF:" + seconds + ",\n" + url + "\n";
    }

    @Test
    public void cleanPlaylistPassesThroughWithoutRemoval() {
        String content = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
                + segment("https://cdn.example.com/live/alpha12.ts", 4.0)
                + segment("https://cdn.example.com/live/bravo34.ts", 4.0)
                + "#EXT-X-ENDLIST\n";
        M3u8Purifier.Result result = M3u8Purifier.purify(BASE, content, List.of());
        assertNotNull(result);
        assertEquals(0, result.removed());
        assertTrue(result.content().contains("alpha12.ts"));
        assertTrue(result.content().contains("bravo34.ts"));
    }

    @Test
    public void masterPlaylistIsPassedThrough() {
        String content = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhttps://cdn.example.com/live/hi.m3u8\n";
        assertNull(M3u8Purifier.purify(BASE, content, List.of()));
    }

    @Test
    public void cueOutAdBreakIsRemoved() {
        String content = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
                + segment("https://cdn.example.com/live/main01.ts", 6.0)
                + "#EXT-X-CUE-OUT:DURATION=15\n"
                + segment("https://cdn.example.com/live/ad01.ts", 5.0)
                + segment("https://cdn.example.com/live/ad02.ts", 5.0)
                + "#EXT-X-CUE-IN\n"
                + segment("https://cdn.example.com/live/main02.ts", 6.0)
                + "#EXT-X-ENDLIST\n";
        M3u8Purifier.Result result = M3u8Purifier.purify(BASE, content, List.of());
        assertNotNull(result);
        assertEquals(2, result.removed());
        assertFalse(result.content().contains("ad01.ts"));
        assertFalse(result.content().contains("ad02.ts"));
        assertTrue(result.content().contains("main01.ts"));
        assertTrue(result.content().contains("main02.ts"));
        assertTrue(result.content().contains("#EXT-X-ENDLIST"));
    }

    @Test
    public void minorityUrlAdSegmentsAreRemoved() {
        StringBuilder content = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:10\n");
        for (int i = 0; i < 12; i++) content.append(segment("https://cdn.example.com/vod1/part" + i + "ab.ts", 4.0));
        content.append(segment("https://other.example.net/adxq/first9z.ts", 4.0));
        content.append(segment("https://other.example.net/adxq/second8y.ts", 4.0));
        content.append("#EXT-X-ENDLIST\n");
        M3u8Purifier.Result result = M3u8Purifier.purify(BASE, content.toString(), List.of());
        assertNotNull(result);
        assertEquals(2, result.removed());
        assertFalse(result.content().contains("other.example.net"));
        assertTrue(result.content().contains("part0ab.ts") || result.content().contains("part"));
    }

    @Test
    public void mixedDecimalPrecisionAdBlockIsRemoved() {
        String content = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
                + segment("https://cdn.example.com/live/show010.ts", 4.04)
                + segment("https://cdn.example.com/live/show011.ts", 4.12)
                + segment("https://cdn.example.com/live/show012.ts", 5.16)
                + "#EXT-X-DISCONTINUITY\n"
                + segment("https://cdn.example.com/live/spot013.ts", 5.033333)
                + segment("https://cdn.example.com/live/spot014.ts", 2.066667)
                + "#EXT-X-DISCONTINUITY\n"
                + segment("https://cdn.example.com/live/show015.ts", 4.20)
                + segment("https://cdn.example.com/live/show016.ts", 4.08)
                + segment("https://cdn.example.com/live/show017.ts", 4.04)
                + "#EXT-X-ENDLIST\n";
        M3u8Purifier.Result result = M3u8Purifier.purify(BASE, content, List.of());
        assertNotNull(result);
        assertEquals(2, result.removed());
        assertFalse(result.content().contains("spot013.ts"));
        assertFalse(result.content().contains("spot014.ts"));
        assertTrue(result.content().contains("show015.ts"));
    }

    @Test
    public void oversizedRemovalAbortsAndKeepsOriginal() {
        String content = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
                + segment("https://cdn.example.com/live/main01.ts", 6.0)
                + segment("https://cdn.example.com/live/main02.ts", 6.0)
                + segment("https://cdn.example.com/live/main03.ts", 6.0)
                + segment("https://cdn.example.com/live/main04.ts", 6.0)
                + segment("https://cdn.example.com/live/main05.ts", 6.0)
                + "#EXT-X-CUE-OUT:DURATION=45\n"
                + segment("https://cdn.example.com/live/ad01.ts", 5.0)
                + segment("https://cdn.example.com/live/ad02.ts", 5.0)
                + segment("https://cdn.example.com/live/ad03.ts", 5.0)
                + segment("https://cdn.example.com/live/ad04.ts", 5.0)
                + segment("https://cdn.example.com/live/ad05.ts", 5.0)
                + segment("https://cdn.example.com/live/ad06.ts", 5.0)
                + "#EXT-X-CUE-IN\n"
                + "#EXT-X-ENDLIST\n";
        M3u8Purifier.Result result = M3u8Purifier.purify(BASE, content, List.of());
        assertNotNull(result);
        assertEquals(0, result.removed());
        assertTrue(result.content().contains("ad01.ts"));
    }

    @Test
    public void configuredKeywordSegmentsAreRemoved() {
        String content = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
                + segment("https://cdn.example.com/live/main01.ts", 6.0)
                + segment("https://sponsorcdn.example/live/promo9.ts", 5.0)
                + segment("https://cdn.example.com/live/main02.ts", 6.0)
                + "#EXT-X-ENDLIST\n";
        M3u8Purifier.Result result = M3u8Purifier.purify(BASE, content, List.of("sponsorcdn.example"));
        assertNotNull(result);
        assertEquals(1, result.removed());
        assertFalse(result.content().contains("sponsorcdn.example"));
        assertTrue(result.content().contains("main01.ts"));
        assertTrue(result.content().contains("main02.ts"));
    }
}
