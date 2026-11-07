package com.fongmi.android.tv.api.loader;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点初始化失败后的短退避。
 * <p>
 * 初始化失败（脚本语法错、ext 非法、网络抖动）过去会把 {@code SpiderNull} 写进永久缓存，
 * 让该站点在整个进程生命周期内都返回空列表；改成只记住一个时间点：
 * 窗口内直接返回空实现（不重复付出初始化开销），窗口过后允许重新尝试。
 * 窗口取 30s：一次瞬时失败（DNS/首个请求超时）通常几秒内即可恢复，
 * 而 30s 足以避免「反复进入站点 → 反复初始化」的开销，也不会让站点长时间不可用。
 */
final class SpiderRetryGuard {

    static final long BACKOFF_MS = 30_000L;

    private final ConcurrentHashMap<String, Long> until;

    SpiderRetryGuard() {
        until = new ConcurrentHashMap<>();
    }

    /** 窗口内应跳过重试；已过期的记录顺带清掉，让后续失败重新计时 */
    boolean shouldSkip(String key, long now) {
        Long value = until.get(key);
        if (value == null) return false;
        if (now < value) return true;
        until.remove(key, value);
        return false;
    }

    void failed(String key, long now) {
        until.put(key, now + BACKOFF_MS);
    }

    void succeeded(String key) {
        until.remove(key);
    }

    void clear() {
        until.clear();
    }
}
