package com.fongmi.android.tv.download;

import com.fongmi.android.tv.bean.DownloadTask;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class DownloadIdentityTest {

    @Test
    public void contentKeyIsStableForSameEpisode() {
        String first = DownloadTask.id("siteA", "123", "线路1", "第03集", "http://example.com/3");
        String second = DownloadTask.id("siteA", "123", "线路1", "第03集", "http://example.com/3");
        assertEquals(first, second);
    }

    @Test
    public void contentKeyDiffersAcrossEpisodesAndSites() {
        String base = DownloadTask.id("siteA", "123", "线路1", "第03集", "http://example.com/3");
        assertNotEquals(base, DownloadTask.id("siteA", "123", "线路1", "第04集", "http://example.com/4"));
        assertNotEquals(base, DownloadTask.id("siteB", "123", "线路1", "第03集", "http://example.com/3"));
        assertNotEquals(base, DownloadTask.id("siteA", "123", "线路2", "第03集", "http://example.com/3"));
    }

    @Test
    public void contentKeySurvivesEmptyFields() {
        assertEquals(DownloadTask.id("", "", "", "", ""), DownloadTask.id(null, null, null, null, null));
    }

    @Test
    public void sanitizeRemovesIllegalFileNameChars() {
        assertEquals("a_b", DownloadPath.sanitize("a/b"));
        assertEquals("a_b_c", DownloadPath.sanitize("a\\b:c"));
        assertEquals("unnamed", DownloadPath.sanitize(""));
        assertEquals("unnamed", DownloadPath.sanitize(null));
        assertEquals("name", DownloadPath.sanitize("name..."));
        assertEquals(48, DownloadPath.sanitize("x".repeat(100)).length());
    }

    @Test
    public void extFromResolvedUrl() {
        assertEquals("mp4", DownloadPath.ext("http://example.com/a.mp4?token=1"));
        assertEquals("m3u8", DownloadPath.ext("http://example.com/a.m3u8"));
        assertEquals("ts", DownloadPath.ext("http://example.com/a.ts"));
        assertEquals("mp4", DownloadPath.ext("http://example.com/noext"));
        assertEquals("mp4", DownloadPath.ext(null));
        assertEquals("mp4", DownloadPath.ext("http://example.com/a.toolongext"));
    }
}
