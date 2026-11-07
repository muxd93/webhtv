package com.fongmi.android.tv.api.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证失败负缓存的阶梯退避：窗口取值、封顶、窗口内阻塞、窗口后放行、成功清除、坏状态容错。
 */
public class DepotBackoffTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final long MIN = 60 * 1000L;

    @Test
    public void firstWindowIsFiveMinutes() {
        assertEquals(5 * MIN, DepotBackoff.window(1));
        assertEquals(5 * MIN, DepotBackoff.BASE_MS);
    }

    @Test
    public void windowDoublesUntilCap() {
        assertEquals(5 * MIN, DepotBackoff.window(1));
        assertEquals(10 * MIN, DepotBackoff.window(2));
        assertEquals(20 * MIN, DepotBackoff.window(3));
        assertEquals(40 * MIN, DepotBackoff.window(4));
        assertEquals("封顶 1 小时", DepotBackoff.MAX_MS, DepotBackoff.window(5));
        assertEquals(DepotBackoff.MAX_MS, DepotBackoff.window(20));
    }

    @Test
    public void blockedOnlyWithinWindow() {
        DepotBackoff.State state = new DepotBackoff.State(1, NOW + 5 * MIN, "HTTP");
        assertTrue(DepotBackoff.blocked(state, NOW));
        assertTrue(DepotBackoff.blocked(state, NOW + 5 * MIN - 1));
        assertFalse("到点即放行", DepotBackoff.blocked(state, NOW + 5 * MIN));
        assertFalse("没有记录不阻塞", DepotBackoff.blocked(null, NOW));
    }

    @Test
    public void failIncrementsAttemptsAndKeepsStage() {
        DepotBackoff.State first = DepotBackoff.fail(null, NOW, "DNS");
        assertEquals(1, first.attempts);
        assertEquals(NOW + 5 * MIN, first.until);
        assertEquals("DNS", first.stage);

        DepotBackoff.State second = DepotBackoff.fail(first, NOW, "HTTP");
        assertEquals(2, second.attempts);
        assertEquals(NOW + 10 * MIN, second.until);
        assertEquals("HTTP", second.stage);
    }

    @Test
    public void stateRoundTrips() {
        DepotBackoff.State state = DepotBackoff.fail(null, NOW, "TLS");
        DepotBackoff.State parsed = DepotBackoff.parse(state.encode());
        assertEquals(state.attempts, parsed.attempts);
        assertEquals(state.until, parsed.until);
        assertEquals(state.stage, parsed.stage);
    }

    @Test
    public void successClearsState() {
        String cleared = "";
        assertNull("清除后的状态视为没有记录", DepotBackoff.parse(cleared));
        assertFalse(DepotBackoff.blocked(DepotBackoff.parse(cleared), NOW));
        DepotBackoff.State restarted = DepotBackoff.fail(DepotBackoff.parse(cleared), NOW, "HTTP");
        assertEquals("清除后重新从第一档开始", 1, restarted.attempts);
    }

    @Test
    public void corruptStateIsIgnored() {
        assertNull(DepotBackoff.parse("garbage"));
        assertNull(DepotBackoff.parse("0:123:x"));
        assertNull(DepotBackoff.parse("-1:0:x"));
        assertNull(DepotBackoff.parse("abc:def"));
        assertFalse(DepotBackoff.blocked(DepotBackoff.parse("abc:def"), NOW));
    }
}
