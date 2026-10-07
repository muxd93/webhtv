package com.fongmi.android.tv.api.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 多仓聚合池回归：只增不减、墓碑不复活、改名共存、广告跨仓去重与状态隔离。 */
public class DepotPoolTest {

    private final Gson gson = new Gson();

    @Before
    public void setUp() {
        DepotPool.resetForTest();
    }

    @After
    public void tearDown() {
        DepotPool.resetForTest();
    }

    private JsonObject live(String name, String api) {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("type", 0);
        object.addProperty("url", api);
        return object;
    }

    private List<DepotPool.PoolLive> enabledLives() {
        return DepotPool.lives(null, true);
    }

    @Test
    public void upsertAppendsNewEntriesInDeclarationOrder() {
        assertTrue(DepotPool.upsert("http://a", "仓A", "", List.of(live("L1", "http://a/1")), List.of("ad1")));
        assertTrue(DepotPool.upsert("http://b", "仓B", "", List.of(live("L2", "http://b/1")), List.of()));
        List<DepotPool.PoolLive> lives = enabledLives();
        assertEquals(2, lives.size());
        assertEquals("仓A", lives.get(0).childName());
        assertEquals("L1", lives.get(0).origName());
        assertEquals("仓B", lives.get(1).childName());
        assertEquals(1, DepotPool.adsView().size());
        assertEquals("ad1", DepotPool.adsView().get(0));
    }

    @Test
    public void upsertRefreshesContentWithoutReorderingOrRemoving() {
        DepotPool.upsert("http://a", "仓A", "", List.of(live("L1", "http://a/old"), live("L2", "http://a/2")), List.of("ad1"));
        // 子仓新版本改了 L1 的地址、删除了 L2、新增 L3：L1 原位刷新，L2 保留（只增不减），L3 追加
        DepotPool.upsert("http://a", "仓A", "", List.of(live("L1", "http://a/new"), live("L3", "http://a/3")), List.of());
        List<DepotPool.PoolLive> lives = enabledLives();
        assertEquals(3, lives.size());
        assertEquals("L1", lives.get(0).origName());
        assertTrue(lives.get(0).raw().contains("http://a/new"));
        assertEquals("L2", lives.get(1).origName());
        assertEquals("L3", lives.get(2).origName());
    }

    @Test
    public void deletedTombstoneSurvivesUpsertAndRecovery() {
        DepotPool.upsert("http://a", "仓A", "", List.of(live("L1", "http://a/1")), List.of());
        DepotPool.setState("http://a", DepotPool.DOM_LIVE, "L1", DepotPool.DELETED);
        // 重导入同条目：墓碑不复活
        assertFalse(DepotPool.upsert("http://a", "仓A", "", List.of(live("L1", "http://a/1")), List.of()));
        assertTrue(enabledLives().isEmpty());
        assertEquals(DepotPool.DELETED, DepotPool.stateOf("http://a", DepotPool.DOM_LIVE, "L1"));
        // 恢复默认：清零后可见
        DepotPool.resetStates();
        assertEquals(1, enabledLives().size());
    }

    @Test
    public void disabledEntryHiddenFromViewsButKeptInPool() {
        DepotPool.upsert("http://a", "仓A", "", List.of(live("L1", "http://a/1")), List.of("ad1"));
        DepotPool.setState("http://a", DepotPool.DOM_LIVE, "L1", DepotPool.DISABLED);
        assertTrue(enabledLives().isEmpty());
        assertEquals(1, DepotPool.lives(null, false).size());
        assertEquals(DepotPool.DISABLED, DepotPool.lives(null, false).get(0).state());
        // 广告不受直播条目状态影响
        assertEquals(1, DepotPool.adsView().size());
    }

    @Test
    public void adsDedupeAcrossChildrenWithSingleState() {
        DepotPool.upsert("http://a", "仓A", "", List.of(), List.of("ad1", "ad2"));
        DepotPool.upsert("http://b", "仓B", "", List.of(), List.of("ad2", "ad3"));
        assertEquals(List.of("ad1", "ad2", "ad3"), DepotPool.adsView());
        // 规则状态与来源仓无关：删一次即全局隐藏；池内条目保留（只增不减）
        DepotPool.setState(null, DepotPool.DOM_AD, "ad2", DepotPool.DELETED);
        assertEquals(List.of("ad1", "ad3"), DepotPool.adsView());
        assertEquals(3, DepotPool.ads(false).size());
        assertEquals(2, DepotPool.ads(true).size());
    }

    @Test
    public void resolveNameCoexistsWithSuffixAndSequence() {
        Set<String> used = new HashSet<>();
        assertEquals("CCTV1", DepotPool.resolveName(used, "CCTV1", "仓A"));
        used.add("CCTV1");
        assertEquals("CCTV1（仓B）", DepotPool.resolveName(used, "CCTV1", "仓B"));
        used.add("CCTV1（仓B）");
        // 不同子仓名的后缀天然错开，无需序号
        assertEquals("CCTV1（仓C）", DepotPool.resolveName(used, "CCTV1", "仓C"));
        used.add("CCTV1（仓C）");
        // 同一后缀形态再撞名才走序号路径
        assertEquals("CCTV1（仓C 2）", DepotPool.resolveName(used, "CCTV1", "仓C"));
        used.add("CCTV1（仓C 2）");
        assertEquals("CCTV1（仓C 3）", DepotPool.resolveName(used, "CCTV1", "仓C"));
    }

    @Test
    public void resolveNameHandlesBlankInputs() {
        Set<String> used = new HashSet<>(List.of("直播"));
        assertEquals("直播（仓）", DepotPool.resolveName(used, "", null));
        used.add("直播（仓）");
        assertEquals("直播（仓 2）", DepotPool.resolveName(used, "", ""));
    }

    @Test
    public void excludeUrlHidesCurrentSourceContribution() {
        DepotPool.upsert("http://a", "仓A", "", List.of(live("L1", "http://a/1")), List.of());
        DepotPool.upsert("http://b", "仓B", "", List.of(live("L2", "http://b/1")), List.of());
        assertEquals(1, DepotPool.lives("http://a", true).size());
        assertEquals("L2", DepotPool.lives("http://a", true).get(0).origName());
    }

    @Test
    public void clearRemovesContributionsAndStates() {
        DepotPool.upsert("http://a", "仓A", "", List.of(live("L1", "http://a/1")), List.of("ad1"));
        DepotPool.setState("http://a", DepotPool.DOM_LIVE, "L1", DepotPool.DISABLED);
        DepotPool.clear();
        assertTrue(enabledLives().isEmpty());
        assertTrue(DepotPool.lives(null, false).isEmpty());
        assertTrue(DepotPool.ads(false).isEmpty());
        assertEquals(DepotPool.NORMAL, DepotPool.stateOf("http://a", DepotPool.DOM_LIVE, "L1"));
    }

    @Test
    public void emptyNameEntriesAreSkippedAndBlankInputsIgnored() {
        assertFalse(DepotPool.upsert(null, "仓A", "", List.of(live("L1", "x")), List.of()));
        assertFalse(DepotPool.upsert("", "仓A", "", List.of(live("L1", "x")), List.of()));
        assertTrue(DepotPool.upsert("http://a", "仓A", "", List.of(live("", "x"), live("L1", "x")), List.of(" ")));
        List<DepotPool.PoolLive> lives = enabledLives();
        assertEquals(1, lives.size());
        assertEquals("L1", lives.get(0).origName());
    }

    @Test
    public void rawJsonRoundTripsThroughGson() {
        JsonObject raw = gson.fromJson("{\"name\":\"L1\",\"type\":0,\"url\":\"http://a/1\",\"groups\":[]}", JsonObject.class);
        DepotPool.upsert("http://a", "仓A", "spider.jar", List.of(raw), new ArrayList<>());
        DepotPool.PoolLive item = enabledLives().get(0);
        JsonObject parsed = gson.fromJson(item.raw(), JsonObject.class);
        assertEquals("L1", parsed.get("name").getAsString());
        assertEquals("spider.jar", item.spider());
    }
}
