package com.fongmi.android.tv.utils;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 goProxy 首现诊断的解析逻辑：ps 行取 PID、/proc 的 stat 取 starttime、由开机时长反推启动时刻。
 */
public class LocalProxyTraceTest {

    @Test
    public void parsePidsFromPsOutput() {
        String output = "u0_a123       1234   567  123456  23456  0   0 S goProxy_arm\n"
                + "u0_a123       9876   567  123456  23456  0   0 S tgsou\n"
                + "grep: /proc/self/task: Permission denied\n";
        List<Integer> pids = LocalProxyTrace.parsePids(output);
        assertEquals(2, pids.size());
        assertEquals(Integer.valueOf(1234), pids.get(0));
        assertEquals(Integer.valueOf(9876), pids.get(1));
    }

    @Test
    public void parsePidsOfEmptyOutput() {
        assertTrue(LocalProxyTrace.parsePids("").isEmpty());
        assertTrue(LocalProxyTrace.parsePids(null).isEmpty());
    }

    @Test
    public void statStartTicksHandlesNameWithSpaces() {
        String stat = "1234 (go Proxy (arm)) S 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1000 2000 3000";
        assertEquals(1000L, LocalProxyTrace.statStartTicks(stat));
        assertEquals(0L, LocalProxyTrace.statStartTicks(""));
        assertEquals(0L, LocalProxyTrace.statStartTicks("1234 (x) S 1"));
    }

    @Test
    public void startMillisFromUptime() {
        // 开机 60s，进程 starttime=1000 tick（10ms/tick）→ 进程已运行 50s
        assertEquals(50000L, LocalProxyTrace.startMillis(100000L, 60000L, 1000L));
        // 反推为负（时钟/权限异常）时降级为 n/a
        assertEquals(0L, LocalProxyTrace.startMillis(100000L, 60000L, 0L));
        assertEquals(0L, LocalProxyTrace.startMillis(100000L, 60000L, 7000L));
    }
}
