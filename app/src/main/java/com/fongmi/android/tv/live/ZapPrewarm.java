package com.fongmi.android.tv.live;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 换台预热（SYS4/SYS5，LIVE10 修正）：停留当前台 4 秒后，对"最近换台方向上的邻台"前两条
 * 可预热线路依次预热（DNS/TCP/TLS 连接建立 + 最多 64KB 首段字节；首条网络级失败自动顺延
 * 第二条），换台即取消上一轮（代数校验防旧预热串台），换台命中在飞预热同端点则保留。
 * 仅 http(s) 直连线路参与（loopback 本地代理行除外）；单飞（同一时刻至多一个在途预热），
 * 总带宽预算 ≤128KB，预热结果不计入健康分（探测≠可看）。
 *
 * <p>LIVE10：预热客户端必须从 {@link OkHttp#player()} 派生而非 client(long)——播放（Exo
 * OkHttpDataSource）持有 player 客户端的连接池，从 player 派生才共享连接池与 TLS 会话，
 * 预热建立的连接换台后才可被播放器复用。凡"为播放预热"的 HTTP 资源都应从 player 客户端派生。</p>
 */
public final class ZapPrewarm {

    private static final String TAG = "zap_prewarm";
    private static final long DELAY_MS = 4000;
    private static final long TIMEOUT_MS = 3000;
    private static final int BYTE_BUDGET = 65536;
    private static final int MAX_LINES = 2;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static volatile OkHttpClient prewarmClient;
    /** 最近一次尝试预热的线路 URL（host+port 端点判定用；残留仅在"无在飞但同端点"时少一次空 cancel，无害）。 */
    private static volatile String inFlightUrl;

    private ZapPrewarm() {
    }

    /** 播放器派生客户端（共享连接池/TLS 会话），惰性构建一次。 */
    private static OkHttpClient client() {
        OkHttpClient client = prewarmClient;
        if (client == null) {
            synchronized (ZapPrewarm.class) {
                if (prewarmClient == null) {
                    prewarmClient = OkHttp.player().newBuilder()
                            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .build();
                }
                client = prewarmClient;
            }
        }
        return client;
    }

    private static void cancelInFlight() {
        // 客户端未初始化说明从无预热，跳过以免换台路径无谓构建播放器客户端
        OkHttpClient client = prewarmClient;
        if (client != null) OkHttp.cancel(client, TAG);
    }

    /** 仅 http(s) 直连线路可预热；loopback 本地代理行无预热价值，一并排除（纯函数，供单测）。 */
    public static boolean isPrewarmable(String url) {
        if (url == null) return false;
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) return false;
        String scheme = parsed.scheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        return !isLoopbackHost(parsed.host());
    }

    /** loopback 主机：localhost / 127.x / ::1 / 0.0.0.0（HttpUrl.host 已小写并去 IPv6 括号；纯函数，供单测）。 */
    static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) return false;
        return host.equals("localhost") || host.equals("::1") || host.equals("0.0.0.0") || host.startsWith("127.");
    }

    /** 新 tune 的当前线路与在飞预热目标同端点（host+port）时保留预热；无在飞或无当前线路则不保留。 */
    private static boolean matchesInFlight(Channel channel) {
        String inFlight = inFlightUrl;
        if (inFlight == null || channel == null) return false;
        List<String> urls = channel.getUrls();
        int index = channel.getIndex();
        if (index < 0 || index >= urls.size()) return false;
        return sameEndpoint(inFlight, urls.get(index));
    }

    /** 同端点判定：host+port 相同即命中（OkHttp 连接池按路由复用，路径/查询不影响路由键）。纯函数，供单测。 */
    static boolean sameEndpoint(String left, String right) {
        if (left == null || right == null) return false;
        HttpUrl a = HttpUrl.parse(left);
        HttpUrl b = HttpUrl.parse(right);
        return a != null && b != null && a.host().equals(b.host()) && a.port() == b.port();
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

    /**
     * 换台后调用：延迟预热目标频道（tune 时主线程快照，scheduler 线程不再读会话状态）；
     * 期间再次换台则本轮作废。命中在飞预热（新当前线路与预热目标同 host+port 端点）时保留
     * 该请求——它正是新频道起播可直接复用的连接，杀掉反而错过最需要的一次预热。
     */
    public static void schedule(Channel channel) {
        long generation = GENERATION.incrementAndGet();
        if (!matchesInFlight(channel)) cancelInFlight();
        Task.schedule(() -> {
            if (GENERATION.get() == generation) run(channel, generation);
        }, DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** 取消在途预热（会话重置/换配置时调用）。 */
    public static void cancel() {
        GENERATION.incrementAndGet();
        cancelInFlight();
    }

    private static void run(Channel channel, long generation) {
        if (channel == null) return;
        List<String> lines = prewarmableLines(channel.getUrls(), MAX_LINES);
        if (lines.isEmpty()) return;
        prewarm(lines, 0, generation);
    }

    private static void prewarm(List<String> lines, int index, long generation) {
        try {
            String url = lines.get(index);
            inFlightUrl = url;
            Request request = new Request.Builder().url(url).tag(TAG)
                    .header("Range", "bytes=0-" + (BYTE_BUDGET - 1)).build();
            client().newCall(request).enqueue(new Callback() {
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
