package com.fongmi.android.tv.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FlushThrottleTest {

    // 连续 N 次写入（同一批）只落盘一次：窗口内每次写入只是重排定时器
    @Test
    public void burstOfWritesFlushesOnce() {
        FlushThrottle throttle = new FlushThrottle();
        long now = 0;
        long timerAt = 0;
        for (int i = 0; i < 10; i++) {
            assertFalse(throttle.onWrite(now));
            timerAt = now + FlushThrottle.QUIET_MS;
            now += 50;
        }
        assertTrue(throttle.onTimer(timerAt));
        assertFalse(throttle.onTimer(timerAt + 1));
    }

    // 静默窗口内不落盘，窗口到了才落盘
    @Test
    public void quietWindowBoundary() {
        FlushThrottle throttle = new FlushThrottle();
        throttle.onWrite(0);
        assertFalse(throttle.onTimer(FlushThrottle.QUIET_MS - 1));
        assertTrue(throttle.onTimer(FlushThrottle.QUIET_MS));
    }

    // 写入一直不断时不会被无限推迟：超过 2s 兜底立即落盘，之后重新计时
    @Test
    public void continuousWritesFlushAtMaxWait() {
        FlushThrottle throttle = new FlushThrottle();
        long now = 0;
        int flushes = 0;
        long timerAt = 0;
        for (int i = 0; i < 40; i++) {
            if (throttle.onWrite(now)) {
                throttle.onImmediate();
                flushes++;
            } else {
                timerAt = now + FlushThrottle.QUIET_MS;
            }
            now += 100;
        }
        assertEquals(1, flushes);
        assertTrue(throttle.onTimer(timerAt));
    }

    // 退出前 flush：待落盘批次被消费，随后的定时器不再重复写盘
    @Test
    public void exitFlushConsumesPendingBatch() {
        FlushThrottle throttle = new FlushThrottle();
        throttle.onWrite(0);
        assertTrue(throttle.onImmediate());
        assertFalse(throttle.onTimer(FlushThrottle.QUIET_MS));
        // 没有任何待落盘写入时，退出路径无需额外写盘
        assertFalse(throttle.onImmediate());
    }
}
