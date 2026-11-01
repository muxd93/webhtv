package com.fongmi.android.tv.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Lightweight reverse-order disposer used to guarantee that lifecycle-bound
 * resources (executors, streams, listeners, service connections) are released on
 * every exit path. Registration is opt-in and {@link #dispose()} is idempotent.
 *
 * <p>A single {@link AutoCloseable}-typed entry point is exposed on purpose: a
 * no-arg {@code void} method reference ({@code this::stopX}) is unambiguous only
 * when there is exactly one {@code () -> void} overload, otherwise it clashes
 * with both {@code Runnable} and the functional {@code AutoCloseable} shape.
 */
public final class Disposer implements AutoCloseable {

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private volatile boolean disposed;

    public synchronized Disposer add(AutoCloseable closeable) {
        if (closeable != null && !disposed) closeables.add(closeable);
        return this;
    }

    public synchronized Disposer add(ExecutorService executor) {
        if (executor != null && !disposed) {
            closeables.add(() -> executor.shutdownNow());
        }
        return this;
    }

    public synchronized void dispose() {
        if (disposed) return;
        disposed = true;
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try {
                closeables.get(i).close();
            } catch (Exception ignored) {
            }
        }
        closeables.clear();
    }

    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void close() {
        dispose();
    }
}
