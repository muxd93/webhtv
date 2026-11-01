package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DisposerTest {

    @Test
    public void disposeRunsCloseablesInReverseOrder() {
        List<String> order = new ArrayList<>();
        Disposer disposer = new Disposer();
        disposer.add((AutoCloseable) () -> order.add("a"));
        disposer.add((AutoCloseable) () -> order.add("b"));
        disposer.add((AutoCloseable) () -> order.add("c"));
        disposer.dispose();
        assertEquals(List.of("c", "b", "a"), order);
    }

    @Test
    public void disposeIsIdempotent() {
        List<String> order = new ArrayList<>();
        Disposer disposer = new Disposer();
        disposer.add((AutoCloseable) () -> order.add("x"));
        disposer.dispose();
        disposer.dispose();
        assertEquals(List.of("x"), order);
        assertTrue(disposer.isDisposed());
    }

    @Test
    public void exceptionsAreSwallowed() {
        Disposer disposer = new Disposer();
        disposer.add((AutoCloseable) () -> {
            throw new RuntimeException("boom");
        });
        disposer.add((AutoCloseable) () -> {
        });
        disposer.dispose();
        assertTrue(disposer.isDisposed());
    }

    @Test
    public void addAfterDisposeIsIgnored() {
        List<String> order = new ArrayList<>();
        Disposer disposer = new Disposer();
        disposer.dispose();
        disposer.add((AutoCloseable) () -> order.add("late"));
        assertTrue(order.isEmpty());
        assertTrue(disposer.isDisposed());
    }

    @Test
    public void executorShutdownNowOnDispose() {
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
        Disposer disposer = new Disposer();
        disposer.add(ex);
        disposer.dispose();
        assertTrue(ex.isShutdown());
    }
}
