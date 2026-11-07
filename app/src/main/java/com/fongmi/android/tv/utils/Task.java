package com.fongmi.android.tv.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class Task {

    private static final ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(5));
    // 搜索等大并发任务的共享池：容量可按设置动态调整（见 newSearchExecutor）
    private static final ThreadPoolExecutor searchPool = new ThreadPoolExecutor(20, 20, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private static final ListeningExecutorService largeExecutor = MoreExecutors.listeningDecorator(searchPool);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    // WebView 桥接专用池：首页每次交互都走这里，不能排在 30s 级爬虫/配置任务之后；6 线程覆盖单页常见突发（4~10 个并发）而不放大总连数量
    private static final ListeningExecutorService bridgeExecutor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(6, named("web-bridge")));
    // 内联剧集解析专用单线程：同一 WebView 一次只能执行一段脚本，串行化且执行侧不再占用共享池
    private static final ListeningExecutorService inlineExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor(named("web-inline")));
    // 配置/多仓展开专用池：展开任务会阻塞等待其子任务，放进共享池（5 线程）会与首页、搜索、图片排队甚至自锁；
    // 3 线程足够并发拉取同一层子仓，同时把并发请求量限制在可控范围
    private static final String CONFIG_THREAD_PREFIX = "config-";
    private static final ListeningExecutorService configExecutor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3, named(CONFIG_THREAD_PREFIX)));

    private static ThreadFactory named(String prefix) {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static ListeningExecutorService executor() {
        return executor;
    }

    public static ListeningExecutorService bridge() {
        return bridgeExecutor;
    }

    public static ListeningExecutorService inline() {
        return inlineExecutor;
    }

    public static ListeningExecutorService config() {
        return configExecutor;
    }

    /** 当前线程是否属于配置专用池：池内任务不得再等待池内任务，否则嵌套宽度大于线程数时会自锁 */
    public static boolean isConfigThread() {
        return Thread.currentThread().getName().startsWith(CONFIG_THREAD_PREFIX);
    }

    public static ListeningExecutorService largeExecutor() {
        return largeExecutor;
    }

    /** 调整共享搜索池容量并返回它；增长先放大 maximum、收缩先缩 core（ThreadPoolExecutor 约束） */
    public static ListeningExecutorService newSearchExecutor(int threads) {
        int n = Math.max(1, Math.min(threads, 100));
        if (n >= searchPool.getCorePoolSize()) {
            searchPool.setMaximumPoolSize(n);
            searchPool.setCorePoolSize(n);
        } else {
            searchPool.setCorePoolSize(n);
            searchPool.setMaximumPoolSize(n);
        }
        return largeExecutor;
    }

    public static ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public static Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    public static Future<?> submitLarge(Runnable task) {
        return largeExecutor.submit(task);
    }

    public static void execute(Runnable task) {
        executor.execute(task);
    }

    public static void schedule(Runnable task, long delay, TimeUnit unit) {
        scheduler.schedule(task, delay, unit);
    }

    public static <T> FutureCallback<T> callback(Consumer<T> onSuccess) {
        return callback(onSuccess, null);
    }

    public static <T> FutureCallback<T> callback(Consumer<T> onSuccess, @Nullable Consumer<Throwable> onFailure) {
        return new FutureCallback<>() {
            @Override
            public void onSuccess(T result) {
                onSuccess.accept(result);
            }

            @Override
            public void onFailure(@NonNull Throwable error) {
                if (onFailure != null) onFailure.accept(error);
            }
        };
    }
}
