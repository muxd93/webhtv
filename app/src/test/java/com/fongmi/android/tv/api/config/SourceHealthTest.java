package com.fongmi.android.tv.api.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * 源健康度只读聚合：只把仍在窗口内的失败状态报出来，窗口已过或没有记录时保持沉默。
 */
public class SourceHealthTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void summarizeIgnoresMissingAndInvalidStates() {
        SourceHealth.Summary summary = SourceHealth.summarize(Arrays.asList("", "bad", null), NOW);
        assertTrue(summary.isEmpty());
    }

    @Test
    public void summarizeReportsWindowState() {
        DepotBackoff.State state = DepotBackoff.fail(null, NOW, "DNS");
        SourceHealth.Summary summary = SourceHealth.summarize(Collections.singletonList(state.encode()), NOW);
        assertEquals(1, summary.failed);
        assertEquals(1, summary.attempts);
        assertEquals("DNS", summary.stage);
        assertEquals(NOW + DepotBackoff.window(1), summary.until);
    }

    @Test
    public void summarizeUsesEarliestWindowOfMultipleSources() {
        DepotBackoff.State first = DepotBackoff.fail(null, NOW, "DNS");
        DepotBackoff.State second = DepotBackoff.fail(first, NOW, "JSON");
        SourceHealth.Summary summary = SourceHealth.summarize(Arrays.asList(first.encode(), second.encode()), NOW);
        assertEquals(2, summary.failed);
        assertEquals(first.until, summary.until);
        assertEquals(1, summary.attempts);
        assertEquals("DNS", summary.stage);
    }

    @Test
    public void summarizeStaysQuietAfterWindowPassed() {
        DepotBackoff.State state = DepotBackoff.fail(null, NOW, "DNS");
        SourceHealth.Summary summary = SourceHealth.summarize(Collections.singletonList(state.encode()), NOW + DepotBackoff.window(1) + 1);
        assertTrue(summary.isEmpty());
    }
}
