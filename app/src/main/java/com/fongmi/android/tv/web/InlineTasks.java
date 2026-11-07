package com.fongmi.android.tv.web;

import java.util.concurrent.Callable;
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
 */
final class InlineTasks {

    private InlineTasks() {
    }

    static <T> T submit(ExecutorService executor, Callable<T> task) throws InterruptedException, ExecutionException {
        return executor.submit(task).get();
    }

    static <T> T awaitWork(Future<T> future, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }
}
