package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.bean.Config;
import com.github.catvod.net.OkHttp;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 多仓子源可用性预检（DEPOT2）：对仓内子源并发 GET（Range 0-0、4s 超时），
 * 收到任何 HTTP 响应（含 4xx/5xx 之外的非 2xx 判死）即完成；非 http 子源
 * （本地/内建地址）视为健康。结果由调用方决定是否写入 InterfaceOrderStore 健康档，
 * 用于"首个加载源跳过死源"与接口容灾候选排序。仅用户触发，不在后台轮询。
 */
public final class DepotProbe {

    private static final String TAG = "depot_probe";
    private static final int TIMEOUT_S = 4;
    private static final long JOIN_MS = 6000;

    private static Handler mainHandler;

    private DepotProbe() {
    }

    private static Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    public static class Result {

        public final String url;
        public final boolean ok;
        public final long latency;

        private Result(String url, boolean ok, long latency) {
            this.url = url;
            this.ok = ok;
            this.latency = latency;
        }
    }

    /** 异步探测回调：进度逐条回传（主线程），全部完成后回传结果（主线程）。 */
    public interface ProbeCallback {
        void onProgress(int done, int total);

        void onComplete(List<Result> results);
    }

    private interface Sink {
        void accept(Result result);
    }

    /** 同步探测全部子源；至多等待 JOIN_MS，超时未返回的子源不计入结果。 */
    public static List<Result> probe(List<Config> children) {
        List<Result> results = Collections.synchronizedList(new ArrayList<>());
        runProbe(children, results::add, null);
        return results;
    }

    /** 后台并发探测并在主线程回调进度/结果；多仓刷新场景用于不阻塞 UI、不重建整个配置。 */
    public static void probeAsync(List<Config> children, ProbeCallback cb) {
        new Thread(() -> {
            int total = children == null ? 0 : children.size();
            AtomicInteger done = new AtomicInteger(0);
            List<Result> collected = Collections.synchronizedList(new ArrayList<>());
            runProbe(children, result -> {
                collected.add(result);
                int current = done.incrementAndGet();
                main().post(() -> safe(cb, () -> cb.onProgress(current, total)));
            }, () -> main().post(() -> safe(cb, () -> cb.onComplete(new ArrayList<>(collected)))));
        }).start();
    }

    private static void safe(ProbeCallback cb, Runnable r) {
        if (cb == null) return;
        try {
            r.run();
        } catch (Throwable ignored) {
        }
    }

    /** 并发探测核心（同步执行于调用线程）：每条子源完成即回调 onEach，全部结束后回调 onDone。 */
    private static void runProbe(List<Config> children, Sink onEach, Runnable onDone) {
        if (children == null || children.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(children.size());
        for (Config child : children) {
            String url = child.getUrl();
            if (url == null || url.isEmpty() || !url.startsWith("http")) {
                if (onEach != null) onEach.accept(new Result(url == null ? "" : url, true, 0));
                latch.countDown();
                continue;
            }
            long begin = System.currentTimeMillis();
            Request request = new Request.Builder().url(url).tag(TAG).header("Range", "bytes=0-0").build();
            try {
                OkHttp.client(TIMEOUT_S).newCall(request).enqueue(new okhttp3.Callback() {
                    @Override
                    public void onResponse(okhttp3.Call call, Response response) {
                        boolean ok = response.isSuccessful();
                        response.close();
                        if (onEach != null) onEach.accept(new Result(url, ok, System.currentTimeMillis() - begin));
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(okhttp3.Call call, java.io.IOException e) {
                        if (onEach != null) onEach.accept(new Result(url, false, System.currentTimeMillis() - begin));
                        latch.countDown();
                    }
                });
            } catch (Throwable e) {
                if (onEach != null) onEach.accept(new Result(url, false, System.currentTimeMillis() - begin));
                latch.countDown();
            }
        }
        try {
            latch.await(JOIN_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (onDone != null) onDone.run();
    }
}
