package com.fongmi.android.tv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/** QA1-A3 净化缓存回归：命中复用同 token、TTL 过期、LRU 淘汰、token 幂等供数、中毒语义。 */
public class AdFilterCacheTest {

    private AdFilterCache cache(int capacity, long ttlMs, AtomicLong clock) {
        return new AdFilterCache(capacity, ttlMs, clock::get);
    }

    @Test
    public void findReturnsFreshEntryWithStableToken() {
        AtomicLong clock = new AtomicLong(1000);
        AdFilterCache cache = cache(4, 10_000, clock);
        AdFilterCache.Entry stored = cache.put("u1", "c1", 3, "t1");
        AdFilterCache.Entry hit = cache.find("u1");
        assertNotNull(hit);
        assertEquals(stored.token(), hit.token());
        assertEquals("c1", hit.content());
        assertEquals(3, hit.removed());
        assertEquals(1000, hit.createdAt());
    }

    @Test
    public void findMissesUnknownUrl() {
        AdFilterCache cache = cache(4, 10_000, new AtomicLong(1000));
        assertNull(cache.find("missing"));
        assertNull(cache.findByToken("missing"));
    }

    @Test
    public void entryExpiresAfterTtl() {
        AtomicLong clock = new AtomicLong(1000);
        AdFilterCache cache = cache(4, 10_000, clock);
        cache.put("u1", "c1", 1, "t1");
        clock.set(11_000);
        assertNotNull(cache.peek("u1"));
        clock.set(11_001);
        assertNull(cache.find("u1"));
        assertNull(cache.peek("u1"));
        assertNull(cache.findByToken("t1"));
    }

    @Test
    public void poisonSuppressesFindServeAndRefilterUntilExpiry() {
        AtomicLong clock = new AtomicLong(1000);
        AdFilterCache cache = cache(4, 10_000, clock);
        cache.put("u1", "c1", 2, "t1");
        cache.poison("u1");
        assertNull(cache.find("u1"));
        assertNull(cache.findByToken("t1"));
        AdFilterCache.Entry poisoned = cache.peek("u1");
        assertNotNull(poisoned);
        assertEquals(true, poisoned.poisoned());
        clock.set(11_001);
        assertNull(cache.peek("u1"));
        cache.put("u1", "c2", 0, "t2");
        assertNotNull(cache.find("u1"));
        assertEquals("t2", cache.find("u1").token());
    }

    @Test
    public void poisonUnknownOrStaleUrlIsNoop() {
        AtomicLong clock = new AtomicLong(1000);
        AdFilterCache cache = cache(4, 10_000, clock);
        cache.poison("missing");
        cache.put("u1", "c1", 1, "t1");
        clock.set(20_000);
        cache.poison("u1");
        assertNull(cache.peek("u1"));
    }

    @Test
    public void lruEvictsLeastRecentlyUsedWithAccessRefresh() {
        AtomicLong clock = new AtomicLong(1000);
        AdFilterCache cache = cache(3, 100_000, clock);
        cache.put("a", "ca", 1, "ta");
        cache.put("b", "cb", 1, "tb");
        cache.put("c", "cc", 1, "tc");
        cache.find("b");
        cache.put("d", "cd", 1, "td");
        assertNull(cache.find("a"));
        assertNotNull(cache.find("b"));
        assertNotNull(cache.find("c"));
        assertNotNull(cache.find("d"));
    }

    @Test
    public void tokenServesRepeatedlyUntilPoisonOrExpiry() {
        AtomicLong clock = new AtomicLong(1000);
        AdFilterCache cache = cache(4, 10_000, clock);
        cache.put("u1", "c1", 1, "t1");
        assertEquals("c1", cache.findByToken("t1").content());
        assertEquals("c1", cache.findByToken("t1").content());
        cache.poison("u1");
        assertNull(cache.findByToken("t1"));
    }

    @Test
    public void putReplacesExistingEntryIncludingPoisoned() {
        AtomicLong clock = new AtomicLong(1000);
        AdFilterCache cache = cache(4, 10_000, clock);
        cache.put("u1", "c1", 1, "t1");
        cache.poison("u1");
        cache.put("u1", "c2", 4, "t2");
        assertEquals("c2", cache.find("u1").content());
        assertNotNull(cache.findByToken("t2"));
        assertNull(cache.findByToken("t1"));
    }
}
