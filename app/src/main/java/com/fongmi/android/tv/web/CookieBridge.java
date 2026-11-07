package com.fongmi.android.tv.web;

import android.text.TextUtils;
import android.webkit.CookieManager;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;

import java.util.List;

import okhttp3.Headers;
import okhttp3.HttpUrl;

public class CookieBridge {

    private static final FlushThrottle THROTTLE = new FlushThrottle();
    private static final Runnable FLUSH_TASK = CookieBridge::onTimer;

    public static String get(String url) {
        try {
            return CookieManager.getInstance().getCookie(url);
        } catch (Throwable e) {
            return "";
        }
    }

    public static void set(String url, Headers headers) {
        List<String> cookies = headers.values("Set-Cookie");
        if (cookies.isEmpty()) return;
        CookieManager manager = CookieManager.getInstance();
        for (String cookie : cookies) manager.setCookie(url, cookie);
        // 每批只落盘一次：静默 300ms 后写盘，连续写入时 2s 兜底，避免每个 Set-Cookie 都做一次磁盘写
        if (THROTTLE.onWrite(System.currentTimeMillis())) flushNow();
        else schedule();
    }

    /** 退出/退到后台前落盘（App.onActivityStopped 调用）：取消待处理批次并立即写盘，防止冷启动丢登录态 */
    public static void flushNow() {
        THROTTLE.onImmediate();
        flush();
    }

    private static void schedule() {
        if (App.get() == null) {
            // 进程级对象尚未建立（极早期调用）：退化为立即落盘，宁可多一次写盘也不丢 cookie
            flushNow();
            return;
        }
        // App.post 自带 removeCallbacks，每次写入等价于重排防抖定时器
        App.post(FLUSH_TASK, FlushThrottle.QUIET_MS);
    }

    private static void onTimer() {
        if (THROTTLE.onTimer(System.currentTimeMillis())) flush();
    }

    private static void flush() {
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable e) {
            // 仅补可见性：失败仍然继续、不抛不重试，与原有语义一致
            SpiderDebug.log("cookie", "flush failed error=%s", e.getClass().getSimpleName());
        }
    }

    public static void apply(HttpUrl url, okhttp3.Request.Builder builder, boolean include, boolean hasCookie) {
        if (!include || hasCookie) return;
        String cookie = get(url.toString());
        if (!TextUtils.isEmpty(cookie)) builder.header("Cookie", cookie);
    }
}
