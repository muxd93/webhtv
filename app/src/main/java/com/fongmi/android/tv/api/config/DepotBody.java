package com.fongmi.android.tv.api.config;

import java.util.function.Predicate;

/**
 * 多仓子仓正文的缓存/拉取决策（纯逻辑，不依赖 Android，便于单测）。
 *
 * <p>写入前校验：无效正文（HTML/拦截页/杂图等）不再落库、不刷新时间戳；
 * 读取侧校验：历史遗留的无效正文当作未命中，重新拉取（否则它会被 TTL 续命 24 小时）；
 * 负缓存：连续失败按 {@link DepotBackoff} 的窗口跳过请求，只复用有效旧正文。
 */
final class DepotBody {

    /** 决策结果：{@code json} 为可用正文（空串表示没有）；{@code store} 非空表示需要落库 */
    static final class Outcome {

        final String json;
        final String store;
        /** 本次是否发起了网络拉取 */
        final boolean fetched;
        /** 拉取失败或拉到无效正文时的失败层（DNS/TLS/HTTP/JSON），成功为 null */
        final String stage;
        /** 是否因负缓存跳过请求 */
        final boolean blocked;

        private Outcome(String json, String store, boolean fetched, String stage, boolean blocked) {
            this.json = json;
            this.store = store;
            this.fetched = fetched;
            this.stage = stage;
            this.blocked = blocked;
        }
    }

    interface Fetch {
        String get() throws Throwable;
    }

    private DepotBody() {
    }

    /**
     * @param cached 已落库的正文（可能为 null 或历史遗留的无效正文）
     * @param fresh  缓存是否在 TTL 内
     * @param now    当前时间
     * @param state  负缓存状态串（{@link DepotBackoff#parse} 的输入，可为空）
     * @param fetch  网络拉取（抛出异常视为拉取失败）
     * @param valid  正文有效性判断（null/空串都算无效）
     */
    static Outcome resolve(String cached, boolean fresh, long now, String state, Fetch fetch, Predicate<String> valid) {
        String usable = usable(cached, valid);
        if (!usable.isEmpty() && fresh) return new Outcome(usable, null, false, null, false);
        if (DepotBackoff.blocked(DepotBackoff.parse(state), now)) return new Outcome(usable, null, false, null, true);
        try {
            String fetched = fetch.get();
            if (usable(fetched, valid).isEmpty()) return new Outcome(usable, null, true, "JSON", false);
            return new Outcome(fetched, fetched, true, null, false);
        } catch (Throwable e) {
            // 拉取失败：有效旧缓存兜底；无效旧缓存绝不返回
            return new Outcome(usable, null, true, DepotFallback.stage(e), false);
        }
    }

    /** 可用正文：null/空串/非配置正文一律视为没有 */
    static String usable(String json, Predicate<String> valid) {
        return json != null && !json.isEmpty() && valid.test(json) ? json : "";
    }
}
