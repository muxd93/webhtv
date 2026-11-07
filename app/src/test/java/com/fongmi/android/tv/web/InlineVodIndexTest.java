package com.fongmi.android.tv.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InlineVodIndexTest {

    // 容量上限：第 9 条写入淘汰最旧的一条，并把它的 key 全部交回调用方
    @Test
    public void capacityEvictsOldestAndReturnsItsKeys() {
        InlineVodIndex<String> index = new InlineVodIndex<>();
        for (int i = 0; i < InlineVodIndex.MAX; i++) index.put("v" + i, "entry" + i, List.of("https://e/" + i));
        assertEquals(InlineVodIndex.MAX, index.size());
        Map<String, Set<String>> changes = index.put("v9", "entry9", List.of("https://e/9"));
        assertEquals(InlineVodIndex.MAX, index.size());
        assertEquals(Set.of("https://e/0"), changes.get("v0"));
        assertNull(index.get("v0"));
        assertNotNull(index.get("v9"));
        assertNull(index.ownerOf("https://e/0"));
    }

    // 三表成组一致性：一条记录占用的每个 url key（headers 与 episodes 两张表共用）都必须整套交回
    @Test
    public void evictionReleasesEveryKeyOfTheRecord() {
        InlineVodIndex<String> index = new InlineVodIndex<>();
        List<String> keys = List.of("https://a/ep1", "https://a/ep2", "https://cdn/a/direct.m3u8");
        index.put("a", "entry", keys);
        Map<String, Set<String>> changes = Map.of();
        for (int i = 0; i < InlineVodIndex.MAX; i++) changes = index.put("f" + i, "entry", List.of("https://f/" + i));
        assertEquals(Set.copyOf(keys), changes.get("a"));
    }

    // 当前正在播放（或正在浏览详情页）的那条：即使它是最旧的也不会被淘汰
    @Test
    public void pinnedRecordIsNeverEvicted() {
        InlineVodIndex<String> index = new InlineVodIndex<>();
        index.put("playing", "entry", List.of("https://p/ep1"));
        index.pin("playing");
        boolean droppedPlaying = false;
        for (int i = 0; i < 30; i++) {
            droppedPlaying |= index.put("n" + i, "entry", List.of("https://n/" + i)).containsKey("playing");
        }
        assertFalse(droppedPlaying);
        assertNotNull(index.get("playing"));
        assertEquals("playing", index.ownerOf("https://p/ep1"));
        assertEquals(InlineVodIndex.MAX, index.size());
    }

    // LRU：刚从详情页返回（get 访问过一次）的那条会被刷新成最新，下一次淘汰不再是它
    @Test
    public void recentlyAccessedRecordSurvivesNextInsert() {
        InlineVodIndex<String> index = new InlineVodIndex<>();
        for (int i = 0; i < InlineVodIndex.MAX; i++) index.put("v" + i, "entry", List.of("https://e/" + i));
        assertNotNull(index.get("v0"));
        Map<String, Set<String>> changes = index.put("v9", "entry", List.of("https://e/9"));
        assertTrue(changes.containsKey("v1"));
        assertFalse(changes.containsKey("v0"));
        assertNotNull(index.get("v0"));
    }

    // 两条记录共用同一个 url：淘汰其中一条不能把另一条还要用的 key 删掉
    @Test
    public void sharedUrlStaysWithSurvivingRecord() {
        InlineVodIndex<String> index = new InlineVodIndex<>();
        index.put("a", "entry", List.of("https://a/only", "https://shared/x"));
        index.put("b", "entry", List.of("https://shared/x"));
        for (int i = 0; i < InlineVodIndex.MAX - 2; i++) index.put("f" + i, "entry", List.of("https://f/" + i));
        Map<String, Set<String>> changes = index.put("last", "entry", List.of("https://last"));
        assertEquals(Set.of("https://a/only"), changes.get("a"));
        assertEquals("b", index.ownerOf("https://shared/x"));
    }

    // 同一条记录被覆盖写：作废的 key 交回清理，仍在用的 key 不受影响
    @Test
    public void overwriteReleasesOnlyStaleKeys() {
        InlineVodIndex<String> index = new InlineVodIndex<>();
        index.put("a", "v1", List.of("https://a/keep", "https://a/stale"));
        Map<String, Set<String>> changes = index.put("a", "v2", List.of("https://a/keep", "https://a/new"));
        assertEquals(Set.of("https://a/stale"), changes.get("a"));
        assertEquals("a", index.ownerOf("https://a/keep"));
        assertEquals("a", index.ownerOf("https://a/new"));
        assertNull(index.ownerOf("https://a/stale"));
    }

    // 播放时解析出的直链 url 记回同一条记录，记录淘汰时它也一起被收回
    @Test
    public void lateClaimedUrlIsReleasedWithRecord() {
        InlineVodIndex<String> index = new InlineVodIndex<>();
        index.put("a", "entry", List.of("https://page/1"));
        index.claim(index.ownerOf("https://page/1"), "https://cdn/direct.m3u8");
        Map<String, Set<String>> changes = Map.of();
        for (int i = 0; i < InlineVodIndex.MAX; i++) changes = index.put("f" + i, "entry", List.of("https://f/" + i));
        assertEquals(Set.of("https://page/1", "https://cdn/direct.m3u8"), changes.get("a"));
    }

    // 大量写入后容量始终收敛在 MAX（pin 允许多占一条的场景另测）
    @Test
    public void sizeStaysBounded() {
        InlineVodIndex<String> index = new InlineVodIndex<>();
        Map<String, Set<String>> changes = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            changes.putAll(index.put("v" + i, "entry", List.of("https://e/" + i)));
            assertTrue(index.size() <= InlineVodIndex.MAX);
        }
        assertFalse(changes.containsKey("v99"));
        assertNotNull(index.get("v99"));
    }
}
