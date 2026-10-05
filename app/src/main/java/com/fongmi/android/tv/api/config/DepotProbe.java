package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.bean.Config;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 多仓子源可用性预检（DEPOT2）：对仓内子源并发 GET（Range 0-0、4s 超时），
 * 收到任何 HTTP 响应（含 4xx/5xx 之外的非 2xx 判死）即完成；非 http 子源
 * （本地/内建地址）视为健康。结果写入 InterfaceOrderStore 健康档，
 * 用于"首个加载源跳过死源"与接口容灾候选排序。仅仓展开时用户触发，不在后台轮询。
 */
public final class DepotProbe {

    private static final String TAG = "depot_probe";
    private static final int TIMEOUT_S = 4;
    private static final long JOIN_MS = 6000;

    private DepotProbe() {
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

    /** 并发探测全部子源；至多等待 JOIN_MS，超时未返回的子源不写入结果（排序视为未知=健康桶）。 */
    public static List<Result> probe(List<Config> children) {
        List<Result> results = Collections.synchronizedList(new ArrayList<>());
        if (children == null || children.isEmpty()) return results;
        CountDownLatch latch = new CountDownLatch(children.size());
        for (Config child : children) {
            String url = child.getUrl();
            if (url == null || url.isEmpty() || !url.startsWith("http")) {
                results.add(new Result(url == null ? "" : url, true, 0));
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
                        results.add(new Result(url, ok, System.currentTimeMillis() - begin));
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(okhttp3.Call call, java.io.IOException e) {
                        results.add(new Result(url, false, System.currentTimeMillis() - begin));
                        latch.countDown();
                    }
                });
            } catch (Throwable e) {
                results.add(new Result(url, false, System.currentTimeMillis() - begin));
                latch.countDown();
            }
        }
        try {
            latch.await(JOIN_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return results;
    }
}
