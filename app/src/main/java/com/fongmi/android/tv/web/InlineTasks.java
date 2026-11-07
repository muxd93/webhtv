package com.fongmi.android.tv.web;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * inline 解析的等待语义。
 *
 * <p>{@code Task.inline()} 是单线程池（同一 WebView 一次只能执行一段脚本），所以任务会排队。
 * 外层只等任务完成、不设时限，排队时间因此不计入工作超时；
 * 工作超时由任务内部对结果 future 的等待负责，从真正开始执行时起算。
 *
 * <p>时间来源可注入（{@link Clock}），测试因此能用虚拟时间驱动超时判定，不必真实等待。
 */
final class InlineTasks {

    /** 一次等待切片：外层「不设时限」的等待也要经过注入时钟，故用长切片轮转。 */
    private static final long WAIT_SLICE_NANOS = TimeUnit.DAYS.toNanos(1);

    private InlineTasks() {
    }

    /** 时间源接缝：默认走系统单调时钟，测试可注入可控实现。 */
    interface Clock {

        long nanoTime();

        /** 阻塞到 {@code future} 完成、或按本时钟走过 {@code nanos}；返回 future 是否已完成。 */
        boolean awaitDone(Future<?> future, long nanos) throws InterruptedException;
    }

    private static final Clock SYSTEM = new Clock() {

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public boolean awaitDone(Future<?> future, long nanos) throws InterruptedException {
            try {
                future.get(nanos, TimeUnit.NANOSECONDS);
                return true;
            } catch (TimeoutException e) {
                return false;
            } catch (ExecutionException | CancellationException e) {
                return true; // 已有结果，交给调用方的 get() 抛出真实结局
            }
        }
    };

    static <T> T submit(ExecutorService executor, Callable<T> task) throws InterruptedException, ExecutionException {
        return submit(executor, task, SYSTEM);
    }

    static <T> T submit(ExecutorService executor, Callable<T> task, Clock clock) throws InterruptedException, ExecutionException {
        Future<T> future = executor.submit(task);
        // 外层只等完成、不设时限：排队时间因此不计入工作预算。
        while (!clock.awaitDone(future, WAIT_SLICE_NANOS)) {
            // 切片用完仍未完成就继续等，外层没有超时。
        }
        return future.get();
    }

    static <T> T awaitWork(Future<T> future, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return awaitWork(future, timeout, unit, SYSTEM);
    }

    static <T> T awaitWork(Future<T> future, long timeout, TimeUnit unit, Clock clock) throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = clock.nanoTime() + unit.toNanos(timeout);
        while (true) {
            // 预算从调用时（任务真正开始执行）起算，之前排的队不计入。
            if (future.isDone()) return future.get();
            long remaining = deadline - clock.nanoTime();
            if (remaining <= 0) {
                future.cancel(true);
                throw new TimeoutException();
            }
            if (clock.awaitDone(future, remaining)) return future.get();
        }
    }
}
