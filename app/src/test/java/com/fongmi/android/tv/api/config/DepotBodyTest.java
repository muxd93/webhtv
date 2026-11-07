package com.fongmi.android.tv.api.config;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证子仓正文的缓存决策：无效正文不落库、历史坏正文读取侧当未命中重拉、
 * 负缓存窗口内零请求、有效旧缓存兜底。
 */
public class DepotBodyTest {

    /** 有效正文的最小判定：JSON 对象；调用方实际用 Json.isObj */
    private static final Predicate<String> OBJECT = json -> json.startsWith("{");

    private static final long NOW = 1_000_000_000_000L;

    @Test
    public void invalidFetchedBodyIsNotStored() {
        DepotBody.Outcome outcome = DepotBody.resolve(null, false, NOW, "", () -> "<html>拦截页</html>", OBJECT);
        assertEquals("无效正文不可返回", "", outcome.json);
        assertNull("无效正文不应落库", outcome.store);
        assertTrue(outcome.fetched);
        assertEquals("JSON", outcome.stage);
    }

    @Test
    public void pollutedCacheIsTreatedAsMissingAndRefetched() {
        AtomicInteger fetches = new AtomicInteger();
        DepotBody.Outcome outcome = DepotBody.resolve("<html>拦截页</html>", true, NOW, "", () -> {
            fetches.incrementAndGet();
            return "{\"lives\":[]}";
        }, OBJECT);
        assertEquals("TTL 内的坏缓存也必须重拉", 1, fetches.get());
        assertEquals("{\"lives\":[]}", outcome.json);
        assertNotNull("有效的重拉正文应落库覆盖坏缓存", outcome.store);
        assertNull(outcome.stage);
    }

    @Test
    public void validFreshCacheIsUsedWithoutFetch() {
        AtomicInteger fetches = new AtomicInteger();
        DepotBody.Outcome outcome = DepotBody.resolve("{\"lives\":[]}", true, NOW, "", () -> {
            fetches.incrementAndGet();
            return "{\"lives\":[]}";
        }, OBJECT);
        assertEquals(0, fetches.get());
        assertEquals("{\"lives\":[]}", outcome.json);
        assertNull(outcome.store);
        assertFalse(outcome.fetched);
    }

    @Test
    public void validStaleCacheIsRefetchedAndUpdated() {
        AtomicInteger fetches = new AtomicInteger();
        DepotBody.Outcome outcome = DepotBody.resolve("{\"lives\":[]}", false, NOW, "", () -> {
            fetches.incrementAndGet();
            return "{\"lives\":[{\"name\":\"new\"}]}";
        }, OBJECT);
        assertEquals(1, fetches.get());
        assertEquals("{\"lives\":[{\"name\":\"new\"}]}", outcome.json);
        assertNotNull(outcome.store);
    }

    @Test
    public void validStaleCacheIsKeptWhenFetchFails() {
        DepotBody.Outcome outcome = DepotBody.resolve("{\"lives\":[]}", false, NOW, "", () -> {
            throw new Exception("io");
        }, OBJECT);
        assertEquals("拉取失败沿用有效旧缓存", "{\"lives\":[]}", outcome.json);
        assertNull("没有新正文就不写库", outcome.store);
        assertNotNull("失败层要能记录", outcome.stage);
    }

    @Test
    public void invalidCacheAndFailedFetchYieldsNothing() {
        DepotBody.Outcome outcome = DepotBody.resolve("<html/>", true, NOW, "", () -> {
            throw new Exception("io");
        }, OBJECT);
        assertEquals("坏缓存 + 拉取失败应视为没有", "", outcome.json);
        assertNull(outcome.store);
    }

    @Test
    public void windowBlocksFetchAndKeepsUsableCache() {
        AtomicInteger fetches = new AtomicInteger();
        String state = DepotBackoff.fail(null, NOW, "HTTP").encode();
        DepotBody.Outcome outcome = DepotBody.resolve("{\"lives\":[]}", false, NOW, state, () -> {
            fetches.incrementAndGet();
            return "{\"lives\":[]}";
        }, OBJECT);
        assertEquals("窗口内不应发请求", 0, fetches.get());
        assertTrue(outcome.blocked);
        assertFalse(outcome.fetched);
        assertEquals("窗口内仍可展示有效旧正文", "{\"lives\":[]}", outcome.json);
    }

    @Test
    public void windowBlocksFetchAndSkipsWhenCacheInvalid() {
        AtomicInteger fetches = new AtomicInteger();
        String state = DepotBackoff.fail(null, NOW, "DNS").encode();
        DepotBody.Outcome outcome = DepotBody.resolve("<html/>", true, NOW, state, () -> {
            fetches.incrementAndGet();
            return "{\"lives\":[]}";
        }, OBJECT);
        assertEquals(0, fetches.get());
        assertEquals("", outcome.json);
        assertTrue(outcome.blocked);
    }

    @Test
    public void expiredWindowRefetches() {
        AtomicInteger fetches = new AtomicInteger();
        DepotBackoff.State failed = DepotBackoff.fail(null, NOW, "HTTP");
        String state = failed.encode();
        DepotBody.Outcome outcome = DepotBody.resolve(null, false, failed.until + 1, state, () -> {
            fetches.incrementAndGet();
            return "{\"lives\":[]}";
        }, OBJECT);
        assertEquals("窗口结束后应重试", 1, fetches.get());
        assertEquals("{\"lives\":[]}", outcome.json);
        assertFalse(outcome.blocked);
    }
}
