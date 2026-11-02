package com.fongmi.android.tv.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/** Factory for executor pools with consistent, explicit daemon behaviour. */
public final class ExecutorsX {

    private ExecutorsX() {
    }

    /** Fixed pool whose threads are daemon threads (safe to abandon on exit). */
    public static ExecutorService daemonPool(int nThreads, String name) {
        return Executors.newFixedThreadPool(nThreads, daemonFactory(name));
    }

    /** Single-thread executor whose thread is a daemon thread. */
    public static ExecutorService singleDaemon(String name) {
        return Executors.newSingleThreadExecutor(daemonFactory(name));
    }

    /** Fixed pool whose threads are NOT daemon (for durable write paths). */
    public static ExecutorService fixedNonDaemon(int nThreads, String name) {
        return Executors.newFixedThreadPool(nThreads, namedFactory(name));
    }

    public static ThreadFactory daemonFactory(String name) {
        return new ThreadFactory() {
            private int count;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + (++count));
                t.setDaemon(true);
                return t;
            }
        };
    }

    public static ThreadFactory namedFactory(String name) {
        return new ThreadFactory() {
            private int count;

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, name + "-" + (++count));
            }
        };
    }

    /** Single-thread scheduled executor whose thread is a daemon thread. */
    public static ScheduledExecutorService scheduledDaemon(String name) {
        return Executors.newSingleThreadScheduledExecutor(daemonFactory(name));
    }
}
