package com.fongmi.android.tv.download;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DownloadPathExtTest {

    @Test
    public void urlExtensionWins() {
        assertEquals("mkv", DownloadPath.ext("https://cdn.example.com/video.mkv?token=1", "video/mp4"));
        assertEquals("ts", DownloadPath.ext("https://cdn.example.com/a/b/seg.ts", "video/mp4"));
        assertEquals("flv", DownloadPath.ext("https://cdn.example.com/live.FLV", null));
    }

    @Test
    public void mimeFallbackWhenUrlHasNoExtension() {
        assertEquals("mkv", DownloadPath.ext("https://cdn.example.com/getVideo?id=123", "video/x-matroska"));
        assertEquals("webm", DownloadPath.ext("https://cdn.example.com/getVideo?id=123", "video/webm; codecs=vp9"));
        assertEquals("mov", DownloadPath.ext("https://cdn.example.com/x", "video/quicktime"));
        assertEquals("avi", DownloadPath.ext("https://cdn.example.com/x", "video/x-msvideo"));
        assertEquals("ts", DownloadPath.ext("https://cdn.example.com/x", "video/mp2t"));
        assertEquals("flv", DownloadPath.ext("https://cdn.example.com/x", "video/x-flv"));
        assertEquals("mp3", DownloadPath.ext("https://cdn.example.com/x", "audio/mpeg"));
        assertEquals("aac", DownloadPath.ext("https://cdn.example.com/x", "audio/aac"));
        assertEquals("ogg", DownloadPath.ext("https://cdn.example.com/x", "audio/ogg"));
        assertEquals("wma", DownloadPath.ext("https://cdn.example.com/x", "audio/x-ms-wma"));
        assertEquals("3gp", DownloadPath.ext("https://cdn.example.com/x", "video/3gpp"));
    }

    @Test
    public void unknownMimeFallsBackToMp4() {
        assertEquals("mp4", DownloadPath.ext("https://cdn.example.com/getVideo?id=1", "application/octet-stream"));
        assertEquals("mp4", DownloadPath.ext("https://cdn.example.com/getVideo?id=1", null));
    }

    @Test
    public void realMp4UrlKeepsMp4() {
        assertEquals("mp4", DownloadPath.ext("https://cdn.example.com/video.mp4", "video/x-matroska"));
    }
}
