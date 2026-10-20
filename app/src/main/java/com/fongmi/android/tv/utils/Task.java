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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class Task {

    private static final ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(5));
    // 搜索等大并发任务的共享池：容量可按设置动态调整（见 newSearchExecutor）
    private static final ThreadPoolExecutor searchPool = new ThreadPoolExecutor(20, 20, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private static final ListeningExecutorService largeExecutor = MoreExecutors.listeningDecorator(searchPool);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static ListeningExecutorService executor() {
        return executor;
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
