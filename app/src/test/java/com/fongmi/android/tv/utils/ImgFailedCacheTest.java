package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImgFailedCacheTest {

    // 瞬时失败不能永久生效：DNS 抖动期间记下的失败，TTL 过后必须允许重新加载
    @Test
    public void failedUrlBecomesRetryableAfterTtl() {
        long t0 = 1_000L;
        String url = "https://broken.example/a.jpg";
        ImgUtil.markFailed(url, t0);
        assertTrue(ImgUtil.isFailed(url, t0 + 1));
        assertTrue(ImgUtil.isFailed(url, t0 + ImgUtil.FAILED_TTL - 1));
        assertFalse(ImgUtil.isFailed(url, t0 + ImgUtil.FAILED_TTL));
        assertFalse(ImgUtil.isFailed(url, t0 + ImgUtil.FAILED_TTL + 1));
    }

    // 过期后重新加载仍然失败 → 重新计时，坏图重试被限制在每 TTL 一次而不是每次都试
    @Test
    public void repeatedFailureRestartsWindow() {
        String url = "https://broken.example/b.jpg";
        ImgUtil.markFailed(url, 0L);
        assertFalse(ImgUtil.isFailed(url, ImgUtil.FAILED_TTL));
        ImgUtil.markFailed(url, ImgUtil.FAILED_TTL);
        assertTrue(ImgUtil.isFailed(url, ImgUtil.FAILED_TTL + 1));
        assertTrue(ImgUtil.isFailed(url, ImgUtil.FAILED_TTL * 2 - 1));
        assertFalse(ImgUtil.isFailed(url, ImgUtil.FAILED_TTL * 2));
    }

    // 有界：超过容量后淘汰最旧，内存不随浏览记录无限增长
    @Test
    public void cacheIsBoundedAndEvictsOldest() {
        long t0 = 0L;
        for (int i = 0; i < ImgUtil.FAILED_MAX + 100; i++) ImgUtil.markFailed("https://img.example/" + i, t0);
        assertFalse(ImgUtil.isFailed("https://img.example/99", t0 + 1));
        assertTrue(ImgUtil.isFailed("https://img.example/100", t0 + 1));
        assertTrue(ImgUtil.isFailed("https://img.example/" + (ImgUtil.FAILED_MAX + 99), t0 + 1));
    }

    // 未失败过的 URL 一律不受影响（含过期清理不会误伤其它记录）
    @Test
    public void unknownUrlIsNeverFailed() {
        long t0 = 0L;
        ImgUtil.markFailed("https://img.example/other", t0);
        assertFalse(ImgUtil.isFailed("https://img.example/fresh", t0 + ImgUtil.FAILED_TTL * 2));
        assertTrue(ImgUtil.isFailed("https://img.example/other", t0 + 1));
    }
}
