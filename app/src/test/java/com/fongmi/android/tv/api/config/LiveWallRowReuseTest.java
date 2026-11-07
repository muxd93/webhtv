package com.fongmi.android.tv.api.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Config;

import org.junit.Test;

/**
 * 加载点播配置时直播/壁纸行必须复用已落库的行：整行新建会让 upsert 清掉
 * json/time/active/home/parse/logo/etag，直播源和壁纸缓存每次加载都被归零。
 */
public class LiveWallRowReuseTest {

    private static final long TIME = 1_700_000_000_000L;

    private static Config row(int type, String url) {
        Config row = new Config().type(type).url(url).name("旧名").kind(Config.SINGLE);
        row.setId(9);
        row.setTime(TIME);
        row.setJson("{\"lives\":[]}");
        row.setActive(true);
        row.setHome("home1");
        row.setParse("parse1");
        row.setLogo("logo1");
        row.setEtag("etag1");
        return row;
    }

    private static void assertCacheFieldsKept(Config merged) {
        assertEquals("json", "{\"lives\":[]}", merged.getJson());
        assertEquals("time", TIME, merged.getTime());
        assertTrue("active", merged.isActive());
        assertEquals("home", "home1", merged.getHome());
        assertEquals("parse", "parse1", merged.getParse());
        assertEquals("logo", "logo1", merged.getLogo());
        assertEquals("etag", "etag1", merged.getEtag());
        assertEquals("id", 9, merged.getId());
    }

    @Test
    public void existingLiveRowKeepsCachedFieldsAndOnlyRenames() {
        Config existing = row(Config.LIVE, "https://a.com/live.json");
        Config merged = VodConfig.liveRow(existing, existing.getUrl(), "新名");
        assertSame("必须复用原行对象，否则 upsert 会整行覆盖", existing, merged);
        assertEquals("新名", merged.getName());
        assertEquals(Config.LIVE, merged.getType());
        assertEquals(Config.SINGLE, merged.getKind());
        assertCacheFieldsKept(merged);
    }

    @Test
    public void existingWallRowKeepsCachedFieldsAndOnlyRenames() {
        Config existing = row(Config.WALL, "https://a.com/wall.json");
        Config merged = VodConfig.wallRow(existing, existing.getUrl(), "新名");
        assertSame("必须复用原行对象，否则 upsert 会整行覆盖", existing, merged);
        assertEquals("新名", merged.getName());
        assertEquals(Config.WALL, merged.getType());
        assertCacheFieldsKept(merged);
    }

    @Test
    public void registeredLiveDepotIsNotDemoted() {
        Config existing = row(Config.LIVE, "https://a.com/live.json");
        existing.setKind(Config.DEPOT);
        Config merged = VodConfig.liveRow(existing, existing.getUrl(), "新名");
        assertEquals(Config.DEPOT, merged.getKind());
        assertCacheFieldsKept(merged);
    }

    @Test
    public void missingLiveRowIsCreatedAsSingle() {
        Config created = VodConfig.liveRow(null, "https://b.com/live.json", "b");
        assertNotSame(null, created);
        assertEquals(0, created.getId());
        assertEquals(Config.LIVE, created.getType());
        assertEquals(Config.SINGLE, created.getKind());
        assertEquals("b", created.getName());
        assertEquals("https://b.com/live.json", created.getUrl());
    }
}
