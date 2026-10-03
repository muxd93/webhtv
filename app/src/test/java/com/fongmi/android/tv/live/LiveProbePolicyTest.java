package com.fongmi.android.tv.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 探测策略回归：错排序会让人一直撞死线，错隔离会误杀真频道。 */
public class LiveProbePolicyTest {

    private static JsonObject state(boolean ok, long latency, int streak) {
        JsonObject state = new JsonObject();
        state.addProperty("ok", ok);
        if (ok) state.addProperty("latency", latency);
        state.addProperty("streak", streak);
        return state;
    }

    private static JsonObject probe(String url, JsonObject state) {
        JsonObject probe = new JsonObject();
        probe.add(url, state);
        return probe;
    }

    @Test
    public void aliveLinesSortByLatencyBeforeUnknownAndDead() {
        JsonObject probe = new JsonObject();
        probe.add("http://a", state(true, 300, 0));
        probe.add("http://b", state(true, 80, 0));
        probe.add("http://c", state(false, 0, 3));
        List<String> ordered = LiveAggregator.orderUrls(Arrays.asList("http://c", "http://x", "http://a", "http://b"), probe);
        assertEquals(Arrays.asList("http://b", "http://a", "http://x", "http://c"), ordered);
    }

    @Test
    public void unknownAndDeadKeepOriginalOrder() {
        JsonObject probe = new JsonObject();
        probe.add("http://c1", state(false, 0, 1));
        probe.add("http://c2", state(false, 0, 2));
        List<String> input = Arrays.asList("http://c2", "http://u1", "http://c1", "http://u2");
        assertEquals(Arrays.asList("http://u1", "http://u2", "http://c2", "http://c1"), LiveAggregator.orderUrls(input, probe));
    }

    @Test
    public void nonHttpTreatedAsUnknownNeverDead() {
        JsonObject probe = new JsonObject();
        probe.add("http://dead", state(false, 0, 9));
        List<String> ordered = LiveAggregator.orderUrls(Arrays.asList("http://dead", "rtp://udp:5000"), probe);
        assertEquals(Arrays.asList("rtp://udp:5000", "http://dead"), ordered);
    }

    @Test
    public void quarantineRequiresAllProbeableAndStreakReached() {
        JsonObject probe = new JsonObject();
        probe.add("http://a", state(false, 0, 2));
        probe.add("http://b", state(false, 0, 2));
        assertTrue(LiveAggregator.allDead(Arrays.asList("http://a", "http://b"), probe, 2));
        // streak 不足不隔离（先降级）
        probe.add("http://b", state(false, 0, 1));
        assertFalse(LiveAggregator.allDead(Arrays.asList("http://a", "http://b"), probe, 2));
        // 有可用线路不隔离
        probe.add("http://b", state(true, 120, 0));
        assertFalse(LiveAggregator.allDead(Arrays.asList("http://a", "http://b"), probe, 2));
        // 混入不可探测协议绝不隔离
        assertFalse(LiveAggregator.allDead(Arrays.asList("http://a", "rtp://x:1"), probe, 2));
        // 未探测线路不隔离
        assertFalse(LiveAggregator.allDead(Arrays.asList("http://a", "http://unknown"), probe, 2));
    }

    @Test
    public void emptyChannelNeverQuarantined() {
        assertFalse(LiveAggregator.allDead(Arrays.asList(), new JsonObject(), 2));
    }

    @Test
    public void orderIndicesMatchOrderUrlsPermutation() {
        JsonObject probe = new JsonObject();
        probe.add("http://a", state(true, 300, 0));
        probe.add("http://b", state(true, 80, 0));
        probe.add("http://c", state(false, 0, 3));
        List<String> input = Arrays.asList("http://c", "http://x", "http://a", "http://b");
        int[] order = LiveAggregator.orderIndices(input, probe);
        List<String> ordered = LiveAggregator.orderUrls(input, probe);
        assertEquals(input.size(), order.length);
        for (int i = 0; i < order.length; i++) assertEquals(ordered.get(i), input.get(order[i]));
    }
}
