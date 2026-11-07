package com.fongmi.android.tv.api.config;

import org.junit.Test;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * 验证多仓子配置的失败推进：候选顺序、尝试上限、失败顺延、全部失败才抛错、取消/预算不重试。
 */
public class DepotFallbackTest {

    @Test
    public void activeChildFirst() {
        int[] order = DepotFallback.order(new boolean[]{false, true, false});
        assertEquals(1, order[0]);
        assertEquals("失败顺延到 urls 顺序的下一个", 0, order[1]);
    }

    @Test
    public void noActiveFallsBackToFirst() {
        int[] order = DepotFallback.order(new boolean[]{false, false, false});
        assertEquals(0, order[0]);
        assertEquals(1, order[1]);
    }

    @Test
    public void attemptsAreCapped() {
        assertEquals("候选再多也只尝试 2 个", 2, DepotFallback.order(new boolean[20]).length);
    }

    @Test
    public void advancesToNextCandidateAndDoesNotFail() throws Throwable {
        AtomicInteger attempts = new AtomicInteger();
        int selected = DepotFallback.run(new boolean[]{false, false}, error -> false, index -> {
            attempts.incrementAndGet();
            if (index == 0) throw new IOException("first child unreachable");
        });
        assertEquals("第 1 个失败后应选中第 2 个", 1, selected);
        assertEquals(2, attempts.get());
    }

    @Test
    public void allCandidatesFailedThrowsLast() {
        try {
            DepotFallback.run(new boolean[]{false, false}, error -> false, index -> {
                throw new IOException("child " + index + " unreachable");
            });
            fail("全部候选失败时应抛出错误");
        } catch (Throwable e) {
            assertEquals("child 1 unreachable", e.getMessage());
        }
    }

    @Test
    public void canceledFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        try {
            DepotFallback.run(new boolean[]{false, false}, error -> error instanceof IOException, index -> {
                attempts.incrementAndGet();
                throw new IOException("Canceled");
            });
            fail("取消应直接抛出");
        } catch (Throwable ignored) {
        }
        assertEquals("取消时不应继续尝试下一个子仓", 1, attempts.get());
    }

    @Test
    public void budgetStopsRetry() {
        assertEquals(true, DepotFallback.allowRetry(0));
        assertEquals(true, DepotFallback.allowRetry(DepotFallback.BUDGET_MS - 1));
        assertEquals(false, DepotFallback.allowRetry(DepotFallback.BUDGET_MS));
    }

    @Test
    public void stageClassifiesFailureLayer() {
        assertEquals("DNS", DepotFallback.stage(new UnknownHostException("no such host")));
        assertEquals("TLS", DepotFallback.stage(new SSLException("handshake failed")));
        assertEquals("HTTP", DepotFallback.stage(new IOException("HTTP 404")));
        assertEquals("JSON", DepotFallback.stage(new IllegalStateException("Not a JSON Object")));
        assertEquals("OTHER", DepotFallback.stage(new IllegalArgumentException("nope")));
    }
}
