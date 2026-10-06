package com.fongmi.android.tv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;

public class ParseJobQualityTest {

    private static List<ParseJob.Quality> parse(String json) {
        return ParseJob.parseQualities(JsonParser.parseString(json).getAsJsonArray());
    }

    @Test
    public void nameUrlPairsAreParsedInOrder() {
        List<ParseJob.Quality> qualities = parse("[\"蓝光1080\",\"http://a/1.m3u8\",\"超清\",\"http://a/2.m3u8\"]");
        assertEquals(2, qualities.size());
        assertEquals("蓝光1080", qualities.get(0).name());
        assertEquals("http://a/1.m3u8", qualities.get(0).url());
        assertEquals("超清", qualities.get(1).name());
        assertEquals("http://a/2.m3u8", qualities.get(1).url());
    }

    @Test
    public void shortArrayFallsBackToSingleUrl() {
        assertEquals(0, parse("[\"http://a/1.m3u8\"]").size());
        assertEquals(0, parse("[\"蓝光\",\"http://a/1.m3u8\"]").size());
    }

    @Test
    public void oddLengthArrayIsRejected() {
        assertEquals(0, parse("[\"蓝光\",\"http://a/1.m3u8\",\"超清\"]").size());
    }

    @Test
    public void emptyNameOrUrlIsRejected() {
        assertEquals(0, parse("[\"\",\"http://a/1.m3u8\",\"超清\",\"http://a/2.m3u8\"]").size());
        assertEquals(0, parse("[\"蓝光\",\"http://a/1.m3u8\",\"超清\",\"\"]").size());
    }

    @Test
    public void nonStringElementsAreRejected() {
        assertEquals(0, parse("[\"蓝光\",\"http://a/1.m3u8\",3,\"http://a/2.m3u8\"]").size());
    }

    @Test
    public void whitespaceIsTrimmed() {
        List<ParseJob.Quality> qualities = parse("[\" 蓝光 1080 \",\" http://a/1.m3u8 \",\" 超清 \",\" http://a/2.m3u8 \"]");
        assertEquals(2, qualities.size());
        assertTrue(qualities.get(0).name().startsWith("蓝光"));
        assertTrue(qualities.get(0).url().startsWith("http://"));
    }
}
