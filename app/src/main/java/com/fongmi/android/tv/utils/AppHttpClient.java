package com.fongmi.android.tv.utils;

import com.github.catvod.net.OkHttp;

import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * 统一、按用途调优的 OkHttp 客户端。所有客户端都派生自 catvod 的 {@link OkHttp#client()}，
 * 因而继承其 DNS / 代理 / SSL / 拦截器配置；仅在连接池、并发与超时上按场景调优。
 *
 * <p>性能来源：跨功能复用同一连接池 + OkHttp 默认启用的 HTTP/2 多路复用，
 * 避免了原先每处 {@code new OkHttpClient} 各建独立池、无法跨功能复用 socket 的问题
 * （同一图片 CDN / API 域名在海报墙、网盘检测、Web 请求间可复用连接，省去重复 TCP+TLS 握手）。
 *
 * <p>内存平衡：空闲连接按场景限时回收（短连接 30s、长连接 5min），Dispatcher 并发上限有界，
 * 不会因突发请求无限涨线程。
 */
public final class AppHttpClient {

    private AppHttpClient() {
    }

    // 短连接 API（网盘检测 / 更新 / 推送 / Web 请求 / 图片加载）：
    // 较高并发上限，让海报墙等大量并行请求更快；keep-alive 复用 socket。
    private static final OkHttpClient API = tuned(12_000L, 16, 30_000L, 64, 16);

    // 长连接 / 流（DLNA 等）：更长 keep-alive，避免频繁建连；并发略低。
    private static final OkHttpClient STREAM = tuned(60_000L, 8, 300_000L, 64, 12);

    public static OkHttpClient api() {
        return API;
    }

    public static OkHttpClient stream() {
        return STREAM;
    }

    private static OkHttpClient tuned(long timeoutMs, int maxIdle, long keepAliveMs, int maxRequests, int maxPerHost) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(maxRequests);
        dispatcher.setMaxRequestsPerHost(maxPerHost);
        return OkHttp.client().newBuilder()
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(maxIdle, keepAliveMs, TimeUnit.MILLISECONDS))
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
}
