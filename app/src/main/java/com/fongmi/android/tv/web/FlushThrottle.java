package com.fongmi.android.tv.web;

/**
 * Cookie 落盘节流：把同一批 Set-Cookie 合并成一次 {@code CookieManager.flush()}。
 * <p>
 * 落盘时机：批内最后一次写入后静默 {@link #QUIET_MS}；若写入连续不断，则最长 {@link #MAX_WAIT_MS} 必落一次盘，
 * 避免「页面一直在拉子资源 → cookie 一直不落盘」。退出/退到后台前调用 {@link #onImmediate()} 立即落盘。
 * CookieManager 的内存 cookie 是即时生效的，节流只影响磁盘持久化，不影响同进程内的读取。
 */
final class FlushThrottle {

    /** 静默窗口：最后一次写入后多久落盘；也是每次写入重排定时器的间隔 */
    static final long QUIET_MS = 300;
    /** 兜底：连续写入时最长延迟，保证不会无限推迟落盘 */
    static final long MAX_WAIT_MS = 2000;

    private boolean pending;
    private long since;
    private long last;

    /** 记录一次写入；返回 true 表示本批已超过最长等待，调用方应立刻落盘 */
    synchronized boolean onWrite(long now) {
        if (!pending) {
            pending = true;
            since = now;
            last = now;
            return false;
        }
        last = now;
        return now - since >= MAX_WAIT_MS;
    }

    /** 定时器到期；返回 true 表示需要落盘，false 表示被新写入续期或已被立即落盘处理 */
    synchronized boolean onTimer(long now) {
        if (!pending) return false;
        if (now - last < QUIET_MS) return false;
        pending = false;
        return true;
    }

    /** 立即落盘（退出/退到后台前）；返回 true 表示确实有待落盘批次 */
    synchronized boolean onImmediate() {
        if (!pending) return false;
        pending = false;
        return true;
    }
}
