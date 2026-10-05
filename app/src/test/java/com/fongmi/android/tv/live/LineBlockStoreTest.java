package com.fongmi.android.tv.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/** 手动屏蔽集合回归：block/unblock/clear 语义与 md5 键稳定性（JVM 下持久化自动豁免）。 */
public class LineBlockStoreTest {

    @After
    public void reset() {
        LineBlockStore.resetForTest();
    }

    @Test
    public void blockAndUnblockByKey() {
        assertFalse(LineBlockStore.isBlocked("http://a"));
        LineBlockStore.block("http://a", "电信");
        assertTrue(LineBlockStore.isBlocked("http://a"));
        assertEquals(1, LineBlockStore.count());
        LineBlockStore.unblock("http://a");
        assertFalse(LineBlockStore.isBlocked("http://a"));
        assertEquals(0, LineBlockStore.count());
    }

    @Test
    public void sameUrlAcrossSourcesSharesBlock() {
        LineBlockStore.block("http://a", "");
        assertTrue(LineBlockStore.isBlocked("http://a"));
        assertEquals(1, LineBlockStore.count());
    }

    @Test
    public void nullAndEmptyIgnored() {
        LineBlockStore.block(null, "");
        LineBlockStore.block("", "");
        assertFalse(LineBlockStore.isBlocked(null));
        assertFalse(LineBlockStore.isBlocked(""));
        assertEquals(0, LineBlockStore.count());
    }

    @Test
    public void clearRemovesAll() {
        LineBlockStore.block("http://a", "");
        LineBlockStore.block("http://b", "");
        LineBlockStore.clear();
        assertEquals(0, LineBlockStore.count());
    }
}
