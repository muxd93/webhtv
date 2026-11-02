package com.fongmi.android.tv.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 统一、有界的线程池。取代散落的 {@code Executors.new*} 与无界 {@code newCachedThreadPool}，
 * 避免突发任务（解析 / 弹幕）线程与内存暴涨。
 *
 * <p>线程均为 daemon，进程退出即可被回收，不会阻止 JVM 关闭。
 */
public final class AppExecutors {

    private AppExecutors() {
    }

    private static final int CPU = Math.max(2, Runtime.getRuntime().availableProcessors());

    /** 阻塞 I/O（网络 / 文件）。 */
    public static final ExecutorService IO = ExecutorsX.daemonPool(CPU * 2, "app-io");

    /** CPU 密集（解析 / 解码前处理）。 */
    public static final ExecutorService COMPUTE = ExecutorsX.daemonPool(CPU, "app-compute");

    /** 解析任务（原 Source.parse / 弹幕的无界池）：有界，防峰值。 */
    public static final ExecutorService PARSE = ExecutorsX.daemonPool(Math.min(CPU, 4), "app-parse");

    /** 串行有序任务。 */
    public static final ExecutorService SERIAL = ExecutorsX.singleDaemon("app-serial");

    /** 周期 / 延时任务。 */
    public static final ScheduledExecutorService SCHEDULED = ExecutorsX.scheduledDaemon("app-scheduled");
}
