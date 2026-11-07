package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.bean.Config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 多仓登记的行合并：已落库的子仓行只更新名称/类型标记，缓存与偏好字段必须原样保留；
 * 不存在的行才新建。time 被清零会让 24 小时缓存永远判过期，是此处的核心回归点。
 */
public class DepotRowTest {

    private static final long TIME = 1_700_000_000_000L;

    private static Config row() {
        Config row = new Config().type(Config.VOD).url("https://a.com/c.json").name("旧名").kind(Config.SINGLE);
        row.setId(7);
        row.setTime(TIME);
        row.setJson("{\"sites\":[]}");
        row.setActive(true);
        row.setHome("site1");
        row.setParse("parse1");
        row.setLogo("logo1");
        row.setEtag("etag1");
        return row;
    }

    @Test
    public void existingRowKeepsCachedFieldsAndOnlyRenames() {
        Config existing = row();
        Config merged = DepotRow.resolve(existing, existing.getUrl(), Config.VOD, "新名", Config.SINGLE);
        assertSame("必须复用原行对象，否则 upsert 会整行覆盖", existing, merged);
        assertEquals("新名", merged.getName());
        assertEquals("{\"sites\":[]}", merged.getJson());
        assertEquals(TIME, merged.getTime());
        assertTrue(merged.isActive());
        assertEquals("site1", merged.getHome());
        assertEquals("parse1", merged.getParse());
        assertEquals("logo1", merged.getLogo());
        assertEquals("etag1", merged.getEtag());
        assertEquals(Config.SINGLE, merged.getKind());
        assertEquals(7, merged.getId());
    }

    @Test
    public void emptyIncomingNameKeepsExistingName() {
        Config existing = row();
        Config merged = DepotRow.resolve(existing, existing.getUrl(), Config.VOD, "", Config.SINGLE);
        assertEquals("旧名", merged.getName());
    }

    @Test
    public void missingRowIsCreated() {
        Config created = DepotRow.resolve(null, "https://b.com/c.json", Config.LIVE, "b", Config.DEPOT);
        assertEquals(0, created.getId());
        assertEquals(Config.LIVE, created.getType());
        assertEquals(Config.DEPOT, created.getKind());
        assertEquals("https://b.com/c.json", created.getUrl());
        assertEquals("b", created.getName());
    }

    @Test
    public void unsavedPlaceholderIsNotReused() {
        Config placeholder = new Config().type(Config.VOD).url("https://b.com/c.json");
        Config created = DepotRow.resolve(placeholder, placeholder.getUrl(), Config.VOD, "b", Config.SINGLE);
        assertNotSame("id=0 的占位行复用后 update 会落空", placeholder, created);
        assertEquals(0, created.getId());
        assertEquals("b", created.getName());
    }

    @Test
    public void depotKindIsNeverDemoted() {
        Config existing = row();
        existing.setKind(Config.DEPOT);
        Config merged = DepotRow.resolve(existing, existing.getUrl(), Config.VOD, "", Config.SINGLE);
        assertEquals(Config.DEPOT, merged.getKind());
        assertEquals(TIME, merged.getTime());
        assertEquals("{\"sites\":[]}", merged.getJson());
    }

    @Test
    public void depotEntryRowIsUpgradedAndKeepsCache() {
        Config existing = row();
        Config merged = DepotRow.resolve(existing, existing.getUrl(), Config.VOD, "多仓", Config.DEPOT);
        assertEquals(Config.DEPOT, merged.getKind());
        assertEquals("多仓", merged.getName());
        assertEquals(TIME, merged.getTime());
        assertEquals("{\"sites\":[]}", merged.getJson());
        assertTrue(merged.isActive());
    }
}
