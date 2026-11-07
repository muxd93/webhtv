package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TaskBridgePoolTest {

    @Test
    public void bridgeWorkRunsOnDedicatedThread() throws Exception {
        AtomicReference<String> name = new AtomicReference<>();
        Task.bridge().submit(() -> name.set(Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
        assertTrue(name.get().startsWith("web-bridge-"));
    }

    // 结构性断言：共享池 5 线程全部占满时桥接任务仍能立即完成；改动前它只能排在共享池队列末尾
    @Test
    public void bridgeWorkDoesNotQueueBehindSaturatedSharedPool() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch saturated = new CountDownLatch(5);
        try {
            for (int i = 0; i < 5; i++) {
                Task.execute(() -> {
                    saturated.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                });
            }
            assertTrue(saturated.await(5, TimeUnit.SECONDS));
            AtomicReference<String> name = new AtomicReference<>();
            Task.bridge().submit(() -> name.set(Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
            assertTrue(name.get().startsWith("web-bridge-"));
        } finally {
            release.countDown();
        }
    }

    // 内联解析串行在单一专属线程上，不落在共享池线程
    @Test
    public void inlineWorkIsSerializedOnSingleDedicatedThread() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        Task.inline().submit(() -> first.set(Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
        Task.inline().submit(() -> second.set(Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
        assertEquals("web-inline-1", first.get());
        assertEquals(first.get(), second.get());
    }
}
