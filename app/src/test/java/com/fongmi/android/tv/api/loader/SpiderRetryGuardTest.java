package com.fongmi.android.tv.api.loader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpiderRetryGuardTest {

    // 首次失败：只在退避窗口内跳过，窗口过后必须允许重试（否则站点整个进程生命周期空列表）
    @Test
    public void failureSkipsOnlyInsideBackoffWindow() {
        SpiderRetryGuard guard = new SpiderRetryGuard();
        long failedAt = 1_000L;
        guard.failed("site-a", failedAt);
        assertTrue(guard.shouldSkip("site-a", failedAt + 1));
        assertTrue(guard.shouldSkip("site-a", failedAt + SpiderRetryGuard.BACKOFF_MS - 1));
        assertFalse(guard.shouldSkip("site-a", failedAt + SpiderRetryGuard.BACKOFF_MS));
        assertFalse(guard.shouldSkip("site-a", failedAt + SpiderRetryGuard.BACKOFF_MS + 1));
    }

    // 失败状态不是永久缓存：失败不产生任何「已完成」记录，成功也不留下残留
    @Test
    public void successClearsBackoffAndUnknownKeysAreNeverSkipped() {
        SpiderRetryGuard guard = new SpiderRetryGuard();
        assertFalse(guard.shouldSkip("site-b", 0L));
        guard.failed("site-b", 10L);
        assertTrue(guard.shouldSkip("site-b", 20L));
        guard.succeeded("site-b");
        assertFalse(guard.shouldSkip("site-b", 20L));
    }

    // 每个站点独立计时，一个站点失败不会牵连其它站点
    @Test
    public void backoffIsPerSite() {
        SpiderRetryGuard guard = new SpiderRetryGuard();
        guard.failed("site-c", 100L);
        assertTrue(guard.shouldSkip("site-c", 200L));
        assertFalse(guard.shouldSkip("site-d", 200L));
    }

    // clear() 后所有站点立刻可重试（配置切换/引擎清空场景）
    @Test
    public void clearAllowsImmediateRetry() {
        SpiderRetryGuard guard = new SpiderRetryGuard();
        guard.failed("site-e", 0L);
        assertTrue(guard.shouldSkip("site-e", 1L));
        guard.clear();
        assertFalse(guard.shouldSkip("site-e", 1L));
    }
}
