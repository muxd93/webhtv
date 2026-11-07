package com.fongmi.android.tv.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * inline 解析跑在单线程池里，请求之间必然排队。工作超时必须从任务真正开始执行时起算，
 * 否则并发进首页时第二个请求会「一秒都没干活就超时」。
 *
 * <p>时序由注入的虚拟时钟驱动，断言不再依赖真实墙钟，也不会真睡。
 */
public class InlineTasksTest {

    @Test(timeout = 10_000)
    public void queueTimeIsNotCountedAsWorkTime() throws Exception {
        VirtualClock clock = new VirtualClock();
        ExecutorService inline = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch queueStarted = new CountDownLatch(1);
            CountDownLatch queueReleased = new CountDownLatch(1);
            CountDownLatch workStarted = new CountDownLatch(1);
            inline.submit(() -> {
                queueStarted.countDown();
                queueReleased.await();
                return null;
            });
            assertTrue(queueStarted.await(2, TimeUnit.SECONDS));

            CompletableFuture<String> work = new CompletableFuture<>();
            Future<String> resolved = caller.submit(() -> InlineTasks.submit(inline, () -> {
                workStarted.countDown();
                return InlineTasks.awaitWork(work, 1, TimeUnit.SECONDS, clock);
            }, clock));

            clock.awaitParked(1);                                // 外层已经进入「只等完成」的状态
            // 排队期间虚拟时间走了 20s：预算若从提交时刻起算，这里必定已经超时。
            clock.advance(TimeUnit.SECONDS.toNanos(20));
            queueReleased.countDown();
            assertTrue(workStarted.await(2, TimeUnit.SECONDS));   // 工作阶段真正开始（排队已结束）
            clock.advance(TimeUnit.MILLISECONDS.toNanos(500));   // 工作走了 500ms，预算 1s
            work.complete("ok");
            clock.wake();

            assertEquals("ok", resolved.get(2, TimeUnit.SECONDS));
            assertFalse("排队 20s 不能耗光工作预算、更不能取消工作", work.isCancelled());
        } finally {
            caller.shutdownNow();
            inline.shutdownNow();
        }
    }

    @Test(timeout = 10_000)
    public void workExceedingBudgetStillTimesOut() throws Exception {
        VirtualClock clock = new VirtualClock();
        CompletableFuture<String> never = new CompletableFuture<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> pending = worker.submit(() -> InlineTasks.awaitWork(never, 60, TimeUnit.SECONDS, clock));
            clock.awaitParked(1);                        // 工作阶段已经开始等待
            clock.advance(TimeUnit.SECONDS.toNanos(61)); // 虚拟时间越过预算
            try {
                pending.get(2, TimeUnit.SECONDS);
                fail("超过工作预算必须超时，否则去掉外层时限等于把功能废掉");
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof TimeoutException);
                assertTrue("超时后结果 future 要被取消，避免迟到的回调继续占用", never.isCancelled());
            }
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * 手动推进的时钟：时间只在 {@link #advance} 时前进；{@link #wake} 用于「时间没变但世界变了」的唤醒。
     * 等待者用短真实切片轮询作为唤醒上界，因此整类用例的墙钟开销是毫秒级而不是秒级。
     */
    private static final class VirtualClock implements InlineTasks.Clock {

        private static final long WAKEUP_MS = 5;

        private long nowNanos;
        private int parked;

        @Override
        public synchronized long nanoTime() {
            return nowNanos;
        }

        @Override
        public synchronized boolean awaitDone(Future<?> future, long nanos) throws InterruptedException {
            long deadline = nowNanos + nanos;
            parked++;
            notifyAll();
            try {
                while (!future.isDone() && nowNanos < deadline) wait(WAKEUP_MS);
                return future.isDone();
            } finally {
                parked--;
            }
        }

        synchronized void advance(long nanos) {
            nowNanos += nanos;
            notifyAll();
        }

        synchronized void wake() {
            notifyAll();
        }

        synchronized void awaitParked(int count) throws InterruptedException {
            while (parked < count) wait();
        }
    }
}
