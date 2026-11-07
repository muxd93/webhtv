package com.fongmi.android.tv.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * inline 解析跑在单线程池里，请求之间必然排队。工作超时必须从任务真正开始执行时起算，
 * 否则并发进首页时第二个请求会「一秒都没干活就超时」。
 *
 * <p>时序用毫秒级压缩（排队 1500ms / 预算 1000ms），语义与线上「排队可能吃掉 20s」一致。
 */
public class InlineTasksTest {

    @Test(timeout = 10_000)
    public void queueTimeIsNotCountedAsWorkTime() throws Exception {
        ExecutorService inline = Executors.newSingleThreadExecutor();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            inline.submit(() -> {
                firstStarted.countDown();
                Thread.sleep(1500);
                return null;
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            long submittedAt = System.currentTimeMillis();
            String value = InlineTasks.submit(inline, () -> {
                // 任务真正开始执行后才起算 1000ms 工作预算，此时已经排了 1500ms 队
                CompletableFuture<String> result = new CompletableFuture<>();
                timer.schedule(() -> result.complete("ok"), 1000, TimeUnit.MILLISECONDS);
                return InlineTasks.awaitWork(result, 1, TimeUnit.SECONDS);
            });
            long waited = System.currentTimeMillis() - submittedAt;

            assertEquals("ok", value);
            assertTrue("排队已 " + waited + "ms、超过工作预算，仍然不能超时", waited >= 2000);
        } finally {
            timer.shutdownNow();
            inline.shutdownNow();
        }
    }

    @Test
    public void workExceedingBudgetStillTimesOut() throws Exception {
        CompletableFuture<String> never = new CompletableFuture<>();
        try {
            InlineTasks.awaitWork(never, 200, TimeUnit.MILLISECONDS);
            fail("超过工作预算必须超时，否则去掉外层时限等于把功能废掉");
        } catch (TimeoutException expected) {
            assertTrue("超时后结果 future 要被取消，避免迟到的回调继续占用", never.isCancelled());
        }
    }
}
