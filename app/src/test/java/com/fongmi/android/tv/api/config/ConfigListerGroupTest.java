package com.fongmi.android.tv.api.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ConfigDepot;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列表构建的子关系分组：一次全表关系换内存分组后，语义必须与原「逐仓查关系 + 逐条扫全表」保持一致。
 */
public class ConfigListerGroupTest {

    @Test
    public void groupKeepsRelationOrderPerDepot() {
        Config c1 = new Config().url("https://a.com/c1.json");
        Config c2 = new Config().url("https://a.com/c2.json");
        List<Config> rows = Arrays.asList(c1, c2);
        List<ConfigDepot> relations = Arrays.asList(relation(1, c2.getUrl(), 0), relation(1, c1.getUrl(), 1));
        Set<String> childUrls = new LinkedHashSet<>();
        Map<Integer, List<Config>> children = ConfigLister.group(rows, relations, childUrls);
        assertEquals(Arrays.asList(c2, c1), children.get(1));
        assertEquals(2, childUrls.size());
    }

    @Test
    public void groupSkipsRelationsWithoutMatchingRow() {
        Config c1 = new Config().url("https://a.com/c1.json");
        List<ConfigDepot> relations = Arrays.asList(relation(1, c1.getUrl(), 0), relation(1, "https://a.com/gone.json", 1));
        Set<String> childUrls = new LinkedHashSet<>();
        Map<Integer, List<Config>> children = ConfigLister.group(Arrays.asList(c1), relations, childUrls);
        assertEquals(1, children.get(1).size());
        // 缺失子源不能计入 childCount，也不能被当作「属于某个多仓」而隐藏成单源
        assertEquals(1, childUrls.size());
        assertFalse(childUrls.contains("https://a.com/gone.json"));
    }

    @Test
    public void groupSeparatesDepotsAndIgnoresUnknownDepot() {
        Config c1 = new Config().url("https://a.com/c1.json");
        Config c2 = new Config().url("https://b.com/c2.json");
        ConfigDepot other = relation(2, c2.getUrl(), 0);
        List<ConfigDepot> relations = Arrays.asList(relation(1, c1.getUrl(), 0), other);
        Set<String> childUrls = new LinkedHashSet<>();
        Map<Integer, List<Config>> children = ConfigLister.group(Arrays.asList(c1, c2), relations, childUrls);
        assertEquals(Arrays.asList(c1), children.get(1));
        assertEquals(Arrays.asList(c2), children.get(2));
        assertNull(children.get(3));
        assertEquals(2, childUrls.size());
        assertTrue(childUrls.contains(c1.getUrl()) && childUrls.contains(c2.getUrl()));
    }

    private static ConfigDepot relation(int configId, String url, int ord) {
        return new ConfigDepot().configId(configId).url(url).ord(ord);
    }
}
