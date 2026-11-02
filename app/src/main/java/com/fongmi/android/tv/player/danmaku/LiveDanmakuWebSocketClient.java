package com.fongmi.android.tv.player.danmaku;

import com.fongmi.android.tv.utils.AppHttpClient;
import com.github.catvod.net.OkHttp;

import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class LiveDanmakuWebSocketClient {

    private static final long CONNECT_TIMEOUT_SECONDS = 10L;
    private static final long HANDSHAKE_TIMEOUT_SECONDS = 20L;

    private final OkHttpClient client;
    private boolean released;

    public LiveDanmakuWebSocketClient() {
        // 在统一客户端(AppHttpClient.api)之上派生：保留弹幕专用的并发限制与代理选择器，
        // 同时继承应用统一的 DNS / 代理 / SSL 配置（原实现缺失，存在潜在连接问题）。
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(4);
        dispatcher.setMaxRequestsPerHost(2);
        client = AppHttpClient.api().newBuilder()
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(2, 1L, TimeUnit.MINUTES))
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(0L, TimeUnit.MILLISECONDS)
                .pingInterval(0L, TimeUnit.MILLISECONDS)
                .proxySelector(new LiveDanmakuProxySelector(OkHttp.selector()))
                .build();
    }

    public synchronized WebSocket newWebSocket(String url, WebSocketListener listener) {
        if (released) throw new IllegalStateException("WebSocket client is released");
        Request request = new Request.Builder().url(url).build();
        return client.newWebSocket(request, listener);
    }

    public synchronized void release() {
        if (released) return;
        released = true;
        client.dispatcher().cancelAll();
    }
}
