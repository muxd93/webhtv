package com.fongmi.android.tv.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** 随仓自动聚合的池条目与请求头构造回归：参数丢失会让源拉取/清理失效。 */
public class LiveAggregatorIntegrateTest {

    @Test
    public void poolItemOmitsEmptyOptionalFields() {
        JsonObject item = LiveAggregator.poolItem("http://a", "源A", null, "", null, "", 3, null);
        assertEquals("http://a", item.get("url").getAsString());
        assertEquals("源A", item.get("name").getAsString());
        assertEquals(3, item.get("order").getAsInt());
        assertTrue(item.get("ok").getAsBoolean());
        assertEquals(0, item.get("ts").getAsLong());
        assertTrue(item.get("enabled").getAsBoolean());
        assertFalse(item.has("from"));
        assertFalse(item.has("ua"));
        assertFalse(item.has("header"));
        assertFalse(item.has("epg"));
        assertFalse(item.has("chan"));
    }

    @Test
    public void poolItemNameFallsBackToUrl() {
        JsonObject item = LiveAggregator.poolItem("http://a", "", "http://vod", null, null, null, 0, null);
        assertEquals("http://a", item.get("name").getAsString());
        assertEquals("http://vod", item.get("from").getAsString());
    }

    @Test
    public void poolItemReusesPrevState() {
        JsonObject prev = new JsonObject();
        prev.addProperty("ok", false);
        prev.addProperty("ts", 123L);
        prev.addProperty("chan", 45);
        prev.addProperty("enabled", false);
        JsonObject item = LiveAggregator.poolItem("http://a", "源A", "http://vod", "UA", null, "http://epg", 1, prev);
        assertFalse(item.get("ok").getAsBoolean());
        assertEquals(123L, item.get("ts").getAsLong());
        assertEquals(45, item.get("chan").getAsInt());
        assertFalse(item.get("enabled").getAsBoolean());
        assertEquals("UA", item.get("ua").getAsString());
        assertEquals("http://epg", item.get("epg").getAsString());
    }

    @Test
    public void sourceHeadersBuildsUaAndHeaderMap() {
        JsonObject state = new JsonObject();
        state.addProperty("ua", "okhttp/1.0");
        JsonObject header = new JsonObject();
        header.addProperty("Referer", "http://ref");
        header.addProperty("Origin", "http://org");
        state.add("header", header);
        Map<String, String> headers = LiveAggregator.sourceHeaders(state);
        assertEquals("okhttp/1.0", headers.get("User-Agent"));
        assertEquals("http://ref", headers.get("Referer"));
        assertEquals("http://org", headers.get("Origin"));
    }

    @Test
    public void sourceHeadersEmptyStateYieldsEmptyMap() {
        assertTrue(LiveAggregator.sourceHeaders(new JsonObject()).isEmpty());
    }

    @Test
    public void headerJsonRoundTrip() {
        Map<String, String> header = new HashMap<>();
        header.put("Referer", "http://ref");
        JsonObject item = LiveAggregator.poolItem("http://a", "n", null, null, header, null, 0, null);
        Map<String, String> back = new com.google.gson.Gson().fromJson(item.getAsJsonObject("header"), new TypeToken<Map<String, String>>() {}.getType());
        assertEquals("http://ref", back.get("Referer"));
        assertNull(back.get("User-Agent"));
    }
}
