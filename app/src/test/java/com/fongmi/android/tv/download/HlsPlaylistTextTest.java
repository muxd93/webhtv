package com.fongmi.android.tv.download;

import com.fongmi.android.tv.bean.DownloadTask;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class HlsPlaylistTextTest {

    private static List<HlsPlaylistText.SegmentSpec> specs() {
        return Arrays.asList(
                new HlsPlaylistText.SegmentSpec(9_600_000L, 0, "ts"),
                new HlsPlaylistText.SegmentSpec(9_600_000L, 0, "ts"),
                new HlsPlaylistText.SegmentSpec(9_600_000L, 1, "ts")
        );
    }

    @Test
    public void incompletePlaylistHasNoEndlist() {
        String text = HlsPlaylistText.build(7, 3, 10_000_000L, null, null, null, specs(), false);
        assertTrue(text.startsWith("#EXTM3U\n"));
        assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:7\n"));
        assertTrue(text.contains("#EXT-X-TARGETDURATION:10\n"));
        assertFalse(text.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
        assertFalse(text.contains("#EXT-X-ENDLIST"));
        assertFalse(HlsPlaylistText.isComplete(text));
        assertEquals(3, HlsPlaylistText.countSegments(text));
        assertTrue(text.contains("seg_00000.ts\n"));
        assertTrue(text.contains("seg_00002.ts\n"));
        assertTrue(text.contains("#EXTINF:9.600000,\n"));
    }

    @Test
    public void completedPlaylistHasVodTypeAndEndlist() {
        String text = HlsPlaylistText.build(0, 3, 9_600_000L, null, null, null, specs(), true);
        assertTrue(text.contains("#EXT-X-PLAYLIST-TYPE:VOD\n"));
        assertTrue(text.trim().endsWith("#EXT-X-ENDLIST"));
        assertTrue(HlsPlaylistText.isComplete(text));
    }

    @Test
    public void discontinuityEmittedOnSequenceJump() {
        String text = HlsPlaylistText.build(0, 3, 9_600_000L, null, null, null, specs(), false);
        // 第 3 个分片 relativeDiscontinuitySequence 由 0 变 1 → 且只出现一次
        assertEquals(1, text.split("#EXT-X-DISCONTINUITY\n", -1).length - 1);
        assertTrue(text.indexOf("#EXT-X-DISCONTINUITY") > text.indexOf("seg_00001.ts"));
        assertTrue(text.indexOf("#EXT-X-DISCONTINUITY") < text.indexOf("seg_00002.ts"));
    }

    @Test
    public void keyAndMapLinesWrittenWhenPresent() {
        String text = HlsPlaylistText.build(5, 4, 6_000_000L, "key.bin", "0x1234", "init.mp4", specs(), false);
        assertTrue(text.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1234\n"));
        assertTrue(text.contains("#EXT-X-MAP:URI=\"init.mp4\"\n"));
    }

    @Test
    public void keyWithoutIvOmitsIvAttribute() {
        String text = HlsPlaylistText.build(5, 4, 6_000_000L, "key.bin", null, null, specs(), false);
        assertTrue(text.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"));
        assertFalse(text.contains("IV="));
    }

    @Test
    public void versionFallsBackToThree() {
        assertTrue(HlsPlaylistText.build(0, 0, 1_000_000L, null, null, null, specs(), false).contains("#EXT-X-VERSION:3\n"));
        assertTrue(HlsPlaylistText.build(0, 6, 1_000_000L, null, null, null, specs(), false).contains("#EXT-X-VERSION:6\n"));
    }

    @Test
    public void segmentFileNamesUseFiveDigitsAndExt() {
        String text = HlsPlaylistText.build(0, 3, 1_000_000L, null, null, null, Arrays.asList(
                new HlsPlaylistText.SegmentSpec(1_000_000L, 0, "m4s"),
                new HlsPlaylistText.SegmentSpec(1_000_000L, 0, "")
        ), false);
        assertTrue(text.contains("seg_00000.m4s\n"));
        assertTrue(text.contains("seg_00001.ts\n"));
    }

    @Test
    public void emptySegmentListStillWritesHeader() {
        String text = HlsPlaylistText.build(3, 3, 5_000_000L, null, null, null, Arrays.asList(), true);
        assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:3\n"));
        assertEquals(0, HlsPlaylistText.countSegments(text));
        assertTrue(HlsPlaylistText.isComplete(text));
    }

    @Test
    public void episodeDirNameIncludesStableShortHash() {
        String id = DownloadTask.id("siteA", "123", "线路1", "第03集", "http://example.com/3");
        File dir = DownloadPath.episode(new File("/root/vod"), id, "线路1", "第03集");
        assertTrue(dir.getName().startsWith("线路1_第03集_"));
        assertTrue(dir.getName().endsWith(id.substring(0, 6)));
    }

    @Test
    public void sameNameDifferentUrlGetsDifferentDirs() {
        String idA = DownloadTask.id("siteA", "123", "线路1", "第01集", "http://example.com/a");
        String idB = DownloadTask.id("siteA", "123", "线路1", "第01集", "http://example.com/b");
        File dirA = DownloadPath.episode(new File("/root/vod"), idA, "线路1", "第01集");
        File dirB = DownloadPath.episode(new File("/root/vod"), idB, "线路1", "第01集");
        assertNotEquals(dirA.getName(), dirB.getName());
    }

    @Test
    public void episodeDirNameSanitizesIllegalChars() {
        String id = DownloadTask.id("siteA", "123", "a/b", "c:d", "http://example.com/x");
        File dir = DownloadPath.episode(new File("/root/vod"), id, "a/b", "c:d");
        assertFalse(dir.getName().contains("/"));
        assertFalse(dir.getName().contains(":"));
        assertTrue(dir.getName().startsWith("a_b_c_d_"));
    }
}
