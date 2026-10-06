package com.fongmi.android.tv.live;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 换台预热（SYS4/SYS5）：停留当前台 4 秒后，对"下一频道"前两条可预热线路依次预热
 * （DNS/TCP/TLS 连接建立 + 最多 64KB 首段字节；首条网络级失败自动顺延第二条），
 * 换台即取消上一轮（代数校验防旧预热串台）。仅 http(s) 直连线路参与；
 * 单飞（同一时刻至多一个在途预热），总带宽预算 ≤128KB，预热结果不计入健康分（探测≠可看）。
 */
public final class ZapPrewarm {

    private static final String TAG = "zap_prewarm";
    private static final long DELAY_MS = 4000;
    /** 注意：catvod OkHttp.client(long) 参数单位是毫秒。 */
    private static final long TIMEOUT_MS = 3000;
    private static final int BYTE_BUDGET = 65536;
    private static final int MAX_LINES = 2;
    private static final AtomicLong GENERATION = new AtomicLong();

    private ZapPrewarm() {
    }

    /** 仅 http(s) 直连线路可预热（纯函数，供单测）。 */
    public static boolean isPrewarmable(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 按原序取前 limit 条可预热线路（跳过用户屏蔽的线路；纯函数，供单测）。 */
    static List<String> prewarmableLines(List<String> urls, int limit) {
        List<String> result = new ArrayList<>();
        if (urls == null) return result;
        for (String url : urls) {
            if (!isPrewarmable(url) || LineBlockStore.isBlocked(url)) continue;
            result.add(url);
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** 换台后调用：延迟预热 target 提供的下一频道；期间再次换台则本轮作废。 */
    public static void schedule(Supplier<Channel> target) {
        long generation = GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
        Task.schedule(() -> {
            if (GENERATION.get() == generation) run(target.get(), generation);
        }, DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** 取消在途预热（会话重置/换配置时调用）。 */
    public static void cancel() {
        GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
    }

    private static void run(Channel channel, long generation) {
        if (channel == null) return;
        List<String> lines = prewarmableLines(channel.getUrls(), MAX_LINES);
        if (lines.isEmpty()) return;
        prewarm(lines, 0, generation);
    }

    private static void prewarm(List<String> lines, int index, long generation) {
        try {
            Request request = new Request.Builder().url(lines.get(index)).tag(TAG)
                    .header("Range", "bytes=0-" + (BYTE_BUDGET - 1)).build();
            OkHttp.client(TIMEOUT_MS).newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(Call call, Response response) {
                    try (Response resp = response) {
                        drain(resp);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onFailure(Call call, IOException e) {
                    // 网络级失败顺延下一条预热；用户已换台（代数变化）则整轮作废
                    if (GENERATION.get() == generation && index + 1 < lines.size()) prewarm(lines, index + 1, generation);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void drain(Response response) {
        if (response.body() == null) return;
        try (InputStream in = response.body().byteStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            while (total < BYTE_BUDGET) {
                int read = in.read(buffer, 0, Math.min(buffer.length, BYTE_BUDGET - total));
                if (read == -1) break;
                total += read;
            }
        } catch (Throwable ignored) {
        }
    }
}
