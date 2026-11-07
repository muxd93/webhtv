package com.fongmi.android.tv.api.config;

/**
 * 子仓拉取失败的负缓存与阶梯退避（纯逻辑，不依赖 Android，便于单测）。
 *
 * <p>状态串形如 {@code attempts:until:stage}，由调用方持久化；解析容错，坏数据当作"没有记录"（宁可多试一次）。
 */
final class DepotBackoff {

    /** 首次失败窗口 5 分钟：同一次会话内反复展开不再发请求，同时保留较快的自动恢复 */
    static final long BASE_MS = 5 * 60 * 1000L;

    /** 窗口上限 1 小时：上游修好后最迟 1 小时自动恢复，等价于每个坏子仓每天最多 24 次请求 */
    static final long MAX_MS = 60 * 60 * 1000L;

    private static final int SHIFT_CAP = 10;

    static final class State {

        final int attempts;
        final long until;
        final String stage;

        State(int attempts, long until, String stage) {
            this.attempts = attempts;
            this.until = until;
            this.stage = stage == null ? "" : stage;
        }

        String encode() {
            return attempts + ":" + until + ":" + stage;
        }
    }

    private DepotBackoff() {
    }

    static State parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            String[] parts = raw.split(":");
            int attempts = Integer.parseInt(parts[0]);
            long until = Long.parseLong(parts[1]);
            if (attempts <= 0 || until <= 0) return null;
            return new State(attempts, until, parts.length > 2 ? parts[2] : "");
        } catch (Throwable e) {
            return null;
        }
    }

    /** 第 n 次失败后的等待窗口：5、10、20、40、60（封顶）分钟 */
    static long window(int attempts) {
        long window = BASE_MS * (1L << Math.min(Math.max(attempts - 1, 0), SHIFT_CAP));
        return Math.min(window, MAX_MS);
    }

    static boolean blocked(State state, long now) {
        return state != null && now < state.until;
    }

    /** 记一次失败：次数 +1，窗口按阶梯放大，保留失败层用于日志 */
    static State fail(State state, long now, String stage) {
        int attempts = state == null ? 1 : state.attempts + 1;
        return new State(attempts, now + window(attempts), stage == null ? "" : stage.replace(':', '/'));
    }
}
