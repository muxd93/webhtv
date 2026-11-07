package com.fongmi.android.tv.api.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.utils.Task;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多仓展开调度：配置展开的父任务会阻塞等待子任务，因此必须满足
 * <ol>
 *   <li>工作跑在配置专用池，不占用共享池（首页/搜索/图片）；</li>
 *   <li>池内任务绝不等待池内任务 —— 嵌套展开就地执行，池线程数小于嵌套宽度也不会饿死。</li>
 * </ol>
 * 若有人把嵌套展开改回「提交同一池 + 阻塞等待」，第 1、2 条测试会因线程自锁而超时失败。
 */
public class DepotExpandSchedulerTest {

    private static List<Integer> range(int count) {
        List<Integer> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) items.add(i);
        return items;
    }

    @Test(timeout = 10000)
    public void nestedLayerWiderThanPoolStillCompletes() {
        // 顶层 6 个关系 > 池的 3 线程，每个关系再展开 3 个子关系（同样 > 3）：
        // 子任务若重新提交并阻塞，3 个池线程会全部卡在等待自己的子任务上，测试超时。
        AtomicInteger leaves = new AtomicInteger();
        DepotExpander.forEachLayer(range(6), top ->
                DepotExpander.forEachLayer(range(3), leaf -> leaves.incrementAndGet()));
        assertEquals(18, leaves.get());
    }

    @Test(timeout = 10000)
    public void nestingDeeperThanPoolThreadsStillCompletes() {
        // 三层嵌套（深度 3 == 线程数，宽度 4 > 线程数）：任何一层重新入池都会自锁
        AtomicInteger leaves = new AtomicInteger();
        DepotExpander.forEachLayer(range(4), a ->
                DepotExpander.forEachLayer(range(4), b ->
                        DepotExpander.forEachLayer(range(4), leaf -> leaves.incrementAndGet())));
        assertEquals(64, leaves.get());
    }

    @Test(timeout = 10000)
    public void expansionRunsOnDedicatedPoolInsteadOfSharedPool() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        DepotExpander.forEachLayer(range(12), item -> {
            threads.add(Thread.currentThread().getName());
            assertTrue("展开必须跑在配置专用池，实际线程: " + Thread.currentThread().getName(), Task.isConfigThread());
        });
        assertTrue("池线程数不应超过 3，实际: " + threads, threads.size() <= 3);
        for (String name : threads) assertTrue("线程名必须可辨识: " + name, name.startsWith("config-"));
    }

    @Test(timeout = 15000)
    public void expansionDrainsAfterSaturatedPoolWithoutSelfLock() throws Exception {
        // 用 latch 把 3 个配置线程全部占满：展开只能排队，但一旦释放就必须完整跑完，不能改用共享池或死等
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            Task.config().submit(() -> {
                occupied.countDown();
                hold.await();
                return null;
            });
        }
        assertTrue("配置池未被占满", occupied.await(5, TimeUnit.SECONDS));
        AtomicInteger leaves = new AtomicInteger();
        Thread expander = new Thread(() -> DepotExpander.forEachLayer(range(6),
                top -> DepotExpander.forEachLayer(range(3), leaf -> leaves.incrementAndGet())));
        expander.start();
        hold.countDown();
        expander.join(5000);
        assertFalse("池被占满释放后展开仍未完成", expander.isAlive());
        assertEquals(18, leaves.get());
    }
}
