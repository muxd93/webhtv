package com.fongmi.android.tv.utils;

import android.os.Handler;
import android.os.Looper;

import com.fongmi.android.tv.remote.RemoteAgent;

import java.util.EnumMap;
import java.util.Map;

/**
 * 按需门控：远程托管、mDNS、DLNA 这类只在特定场景才需要的后台能力，
 * 一律改为“进入页面/打开开关时 acquire，离开时 release”，而不是随进程常驻。
 * <p>
 * release 后延迟 STOP_DELAY_MS 才真正停止，避免页面切换造成的抖动启停。
 * 注意这里只管“显式按需”，进程启动时的自动拉起由 App.startBackgroundServices 决定。
 */
public final class ServiceGate {

    public enum Gate { NSD, REMOTE_AGENT }

    private static final long STOP_DELAY_MS = 30_000L;

    private static volatile ServiceGate instance;

    private final Map<Gate, Integer> counts = new EnumMap<>(Gate.class);
    private final Map<Gate, Runnable> pending = new EnumMap<>(Gate.class);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ServiceGate() {
    }

    public static ServiceGate get() {
        if (instance == null) {
            synchronized (ServiceGate.class) {
                if (instance == null) instance = new ServiceGate();
            }
        }
        return instance;
    }

    public synchronized void acquire(Gate gate) {
        int count = counts.getOrDefault(gate, 0) + 1;
        counts.put(gate, count);
        cancelStop(gate);
        if (count == 1) start(gate);
    }

    public synchronized void release(Gate gate) {
        int count = counts.getOrDefault(gate, 0) - 1;
        if (count < 0) count = 0;
        counts.put(gate, count);
        if (count > 0) return;
        Runnable task = () -> {
            if (counts.getOrDefault(gate, 0) > 0) return;
            stop(gate);
        };
        pending.put(gate, task);
        handler.postDelayed(task, STOP_DELAY_MS);
    }

    public synchronized void stopAll() {
        for (Gate gate : Gate.values()) {
            counts.put(gate, 0);
            cancelStop(gate);
            stop(gate);
        }
    }

    private void cancelStop(Gate gate) {
        Runnable task = pending.remove(gate);
        if (task != null) handler.removeCallbacks(task);
    }

    private void start(Gate gate) {
        try {
            switch (gate) {
                case NSD -> NsdDeviceDiscovery.register();
                case REMOTE_AGENT -> RemoteAgent.get().start();
            }
        } catch (Throwable e) {
            // 门控失败不应影响页面本身
        }
    }

    private void stop(Gate gate) {
        try {
            switch (gate) {
                case NSD -> NsdDeviceDiscovery.unregister();
                case REMOTE_AGENT -> RemoteAgent.get().stop();
            }
        } catch (Throwable ignored) {
        }
    }
}
