package com.fongmi.android.tv.live;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 直播线路有效性探测：GET 读到响应头即判活（2xx），不读流实体。
 * 手动全量（直播控制条「检测」，运行中再点即取消）+ 聚合写盘后增量；
 * 结果按轮次语义批量写入 meta（成功清零 streak 记延迟，失败累加 streak），
 * 由 {@link LiveAggregator#applyProbe} 驱动线路重排与全死频道隔离。
 */
public class LiveProbe {

    /** 页面级进度订阅（如聚合源管理页）；回调统一切主线程，置 null 取消订阅。 */
    public interface ProgressListener {

        void onProgress(int done, int total);

        void onFinished(int results, int ok);
    }

    private static volatile ProgressListener listener;

    public static void setProgressListener(ProgressListener l) {
        listener = l;
    }

    public static class Result {

        public final boolean ok;
        public final long latency;

        public Result(boolean ok, long latency) {
            this.ok = ok;
            this.latency = latency;
        }
    }

    private static final class Entry {

        final String url;
        final Map<String, String> headers;

        Entry(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    private static final String TAG = "LiveProbe";
    private static final int THREADS = 6;
    private static final long TIMEOUT = 5000;
    private static volatile boolean running;

    /** 直播控制条入口：未运行开始全量，运行中再点即取消。 */
    public static void toggle() {
        if (running) {
            cancel();
            App.post(() -> Notify.show(R.string.live_probe_cancel));
            return;
        }
        startFull();
    }

    public static void cancel() {
        running = false;
        OkHttp.cancel(TAG);
    }

    public static void startFull() {
        if (!LiveAggregator.isAggregate(LiveConfig.get().getConfig())) {
            App.post(() -> Notify.show(R.string.live_probe_unsupported));
            return;
        }
        run(() -> collect(false));
    }

    /** 聚合写盘后的增量探测：probe 状态缺失的线路 + 隔离区线路。 */
    public static void startMissing() {
        run(() -> collect(true));
    }

    /** 采集在后台执行（需解析整个聚合文件），入口线程只做运行态标记，避免主线程文件 I/O。 */
    private static void run(Supplier<List<Entry>> supplier) {
        if (running) return;
        running = true;
        Task.submit(() -> {
            List<Entry> entries = supplier.get();
            if (entries.isEmpty()) {
                running = false;
                ProgressListener idle = listener;
                if (idle != null) App.post(() -> idle.onFinished(0, 0));
                return;
            }
            App.post(() -> Notify.show(ResUtil.getString(R.string.live_probe_start, entries.size())));
            execute(entries);
        });
    }

    private static void execute(List<Entry> entries) {
        Map<String, Result> results = new HashMap<>();
        CountDownLatch latch = new CountDownLatch(entries.size());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        OkHttpClient client = OkHttp.client(TIMEOUT);
        int total = entries.size();
        int step = Math.max(total / 4, 1);
        try {
            for (Entry entry : entries) {
                if (!running) break;
                pool.submit(() -> {
                    try {
                        if (running) {
                            Result result = probe(client, entry);
                            synchronized (results) {
                                results.put(entry.url, result);
                            }
                        }
                    } finally {
                        latch.countDown();
                        int done = total - (int) latch.getCount();
                        ProgressListener l = listener;
                        if (l != null) App.post(() -> l.onProgress(done, total));
                        if (running && done < total && done % step == 0) App.post(() -> Notify.show(ResUtil.getString(R.string.live_probe_progress, done, total)));
                    }
                });
            }
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        boolean cancelled = !running;
        running = false;
        if (results.isEmpty()) {
            ProgressListener empty = listener;
            if (empty != null) App.post(() -> empty.onFinished(0, 0));
            return;
        }
        boolean changed;
        try {
            changed = LiveAggregator.applyProbe(results);
        } catch (Throwable e) {
            e.printStackTrace();
            return;
        }
        int ok = 0;
        for (Result result : results.values()) if (result.ok) ok++;
        String summary = cancelled ? ResUtil.getString(R.string.live_probe_partial, results.size(), ok) : ResUtil.getString(R.string.live_probe_done, ok, results.size() - ok);
        if (changed) App.post(() -> {
            Notify.show(summary);
            LiveConfig.get().reloadQuietly();
        });
        else App.post(() -> Notify.show(summary));
        ProgressListener l = listener;
        int okCount = ok;
        if (l != null) App.post(() -> l.onFinished(results.size(), okCount));
    }

    private static Result probe(OkHttpClient client, Entry entry) {
        long start = System.currentTimeMillis();
        try {
            Request.Builder builder = new Request.Builder().url(entry.url).tag(TAG);
            for (Map.Entry<String, String> header : entry.headers.entrySet()) {
                try {
                    builder.header(header.getKey(), header.getValue());
                } catch (Throwable ignored) {
                }
            }
            try (Response response = client.newCall(builder.build()).execute()) {
                return new Result(response.isSuccessful(), System.currentTimeMillis() - start);
            }
        } catch (Throwable e) {
            return new Result(false, -1);
        }
    }

    /** 采集线路：聚合文件全部（或仅缺失状态）可探测线路 + 隔离区线路（复活探测），按 url 去重。 */
    private static List<Entry> collect(boolean missingOnly) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonObject probe = LiveAggregator.probeMap();
        for (Channel channel : LiveAggregator.fileChannels()) {
            for (String url : channel.getUrls()) {
                if (!isProbeable(url) || !seen.add(url)) continue;
                if (missingOnly && probe.has(url)) continue;
                entries.add(new Entry(url, channel.getHeaders()));
            }
        }
        for (Channel channel : LiveAggregator.quarantined()) {
            for (String url : channel.getUrls()) {
                if (!isProbeable(url) || !seen.add(url)) continue;
                entries.add(new Entry(url, channel.getHeaders()));
            }
        }
        return entries;
    }

    private static boolean isProbeable(String url) {
        // rtp/rtsp 等协议 OkHttp 探测不了但未必无效，一律跳过、视为未探测，绝不误杀
        return url.startsWith("http");
    }
}
