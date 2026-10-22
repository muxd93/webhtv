package com.fongmi.android.tv.download;

import com.fongmi.android.tv.download.DashJob.MpdModel;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashJobMpdTest {

    private static MpdModel.Track segmented() {
        MpdModel.Track track = new MpdModel.Track();
        track.kind = "video";
        track.mimeType = "video/mp4";
        track.codecs = "avc1.640028";
        track.width = 1920;
        track.height = 1080;
        track.bandwidth = 2800000;
        track.initFile = "v_init.mp4";
        track.mediaTemplate = "v_seg_$Number%05d$.m4s";
        track.startNumber = 1;
        track.starts.addAll(Arrays.asList(0L, 4000000L, 8000000L));
        track.durations.addAll(Arrays.asList(4000000L, 4000000L, 3999000L));
        return track;
    }

    private static MpdModel.Track singleFile() {
        MpdModel.Track track = new MpdModel.Track();
        track.kind = "audio";
        track.mimeType = "audio/mp4";
        track.codecs = "mp4a.40.2";
        track.language = "zh";
        track.bandwidth = 128000;
        track.baseFile = "a_video.m4s";
        track.indexRange = "1000-2000";
        track.initRange = "0-999";
        return track;
    }

    @Test
    public void segmentedTrackEmitsTimelineAndTemplate() {
        MpdModel model = new MpdModel();
        model.durationMs = 11999;
        model.tracks.add(segmented());
        String xml = DashJob.buildXml(model);
        assertTrue(xml.startsWith("<?xml"));
        assertTrue(xml.contains("mediaPresentationDuration=\"PT11.999S\""));
        assertTrue(xml.contains("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""));
        assertTrue(xml.contains("<AdaptationSet mimeType=\"video/mp4\""));
        assertTrue(xml.contains("bandwidth=\"2800000\""));
        assertTrue(xml.contains("codecs=\"avc1.640028\""));
        assertTrue(xml.contains("width=\"1920\" height=\"1080\""));
        assertTrue(xml.contains("initialization=\"v_init.mp4\""));
        assertTrue(xml.contains("media=\"v_seg_$Number%05d$.m4s\" startNumber=\"1\""));
        assertTrue(xml.contains("<SegmentTimeline>"));
        assertTrue(xml.contains("<S t=\"0\" d=\"4000000\"/>"));
        assertTrue(xml.contains("<S t=\"8000000\" d=\"3999000\"/>"));
        assertEquals(3, xml.split("<S t=", -1).length - 1);
        // 分段轨不写 BaseURL/SegmentBase
        assertFalse(xml.contains("<BaseURL>"));
        assertFalse(xml.contains("<SegmentBase"));
    }

    @Test
    public void singleFileTrackKeepsBaseUrlAndRanges() {
        MpdModel model = new MpdModel();
        model.durationMs = 0;
        model.tracks.add(singleFile());
        String xml = DashJob.buildXml(model);
        assertTrue(xml.contains("<BaseURL>a_video.m4s</BaseURL>"));
        assertTrue(xml.contains("<SegmentBase indexRange=\"1000-2000\">"));
        assertTrue(xml.contains("<Initialization range=\"0-999\"/>"));
        assertTrue(xml.contains("lang=\"zh\""));
        assertFalse(xml.contains("<SegmentTemplate"));
        // durationMs<=0 时不写 mediaPresentationDuration
        assertFalse(xml.contains("mediaPresentationDuration"));
    }

    @Test
    public void partialDownloadEmitsOnlyDoneSegments() {
        MpdModel model = new MpdModel();
        model.durationMs = 11999;
        MpdModel.Track track = segmented();
        // 模拟只下了 2 片：模型构造时只放 2 个条目（toModel 按 plan.done 截断）
        track.starts.remove(2);
        track.durations.remove(2);
        model.tracks.add(track);
        String xml = DashJob.buildXml(model);
        assertEquals(2, xml.split("<S t=", -1).length - 1);
        assertFalse(xml.contains("t=\"8000000\""));
    }

    @Test
    public void xmlSpecialCharsEscaped() {
        MpdModel model = new MpdModel();
        model.durationMs = 1000;
        MpdModel.Track track = segmented();
        track.codecs = "avc1&\"<>";
        track.mimeType = "video/mp4";
        model.tracks.add(track);
        String xml = DashJob.buildXml(model);
        assertTrue(xml.contains("codecs=\"avc1&amp;&quot;&lt;&gt;\""));
    }

    @Test
    public void emptyTracksStillValidMpd() {
        MpdModel model = new MpdModel();
        String xml = DashJob.buildXml(model);
        assertTrue(xml.contains("<Period>"));
        assertTrue(xml.trim().endsWith("</MPD>"));
    }
}
