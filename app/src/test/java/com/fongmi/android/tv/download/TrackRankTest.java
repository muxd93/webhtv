package com.fongmi.android.tv.download;

import androidx.media3.common.Format;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TrackRankTest {

    private static Format format(long bitrate, String codecs, int width, int height) {
        return new Format.Builder().setAverageBitrate((int) bitrate).setCodecs(codecs).setWidth(width).setHeight(height).build();
    }

    @Test
    public void nullFormatScoresUnknown() {
        assertEquals(TrackRank.SCORE_UNKNOWN, TrackRank.score(null));
    }

    @Test
    public void unknownCodecScoresUnknown() {
        assertEquals(TrackRank.SCORE_UNKNOWN, TrackRank.score(format(1000, null, 1920, 1080)));
        assertEquals(TrackRank.SCORE_UNKNOWN, TrackRank.score(format(1000, "unknown-codec", 1920, 1080)));
    }

    @Test
    public void scoreNeverThrowsOnStubbedAndroid() {
        // 单测环境 MediaCodecUtil 不可用：内部 catch 必须兜住，不能抛给调用方
        assertEquals(TrackRank.SCORE_UNKNOWN, TrackRank.score(format(1000, "avc1.640028", 1920, 1080)));
    }

    @Test
    public void equalScoreFallsBackToHigherBitrate() {
        // 同分（环境内都是 UNKNOWN）→ 码率高者胜出
        Format low = format(1000, "avc1.640028", 1920, 1080);
        Format high = format(4000, "avc1.640028", 1920, 1080);
        assertTrue(TrackRank.compare(high, low) < 0);
        assertTrue(TrackRank.compare(low, high) > 0);
        assertEquals(0, TrackRank.compare(low, low));
    }

    @Test
    public void unsetBitrateTreatedAsLowest() {
        Format none = new Format.Builder().setCodecs("avc1.640028").setWidth(1920).setHeight(1080).build();
        Format some = format(500, "avc1.640028", 1920, 1080);
        assertTrue(TrackRank.compare(some, none) < 0);
        assertTrue(TrackRank.compare(none, some) > 0);
    }
}
