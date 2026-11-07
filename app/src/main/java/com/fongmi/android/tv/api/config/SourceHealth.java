package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 源健康度的只读聚合：把已经沉淀在 {@code depot-retry-*} 与 {@code jar-retry-*} 里的失败状态
 * 换算成列表行尾的一行字。不新增持久化、不发起探活，读不到任何状态就当作「看起来正常」。
 *
 * <p>站点初始化的 30 秒退避（{@code SpiderRetryGuard}）键是「jar md5 + 站点 key」，无法反推回配置源 url，
 * 因此不参与聚合。
 */
public final class SourceHealth {

    private static final long MINUTE = 60_000L;

    private SourceHealth() {
    }

    /** 单条状态的聚合结果（纯数据，便于无 Android 依赖地验证判定语义） */
    static final class Summary {

        final int failed;
        final int attempts;
        final long until;
        final String stage;

        Summary(int failed, int attempts, long until, String stage) {
            this.failed = failed;
            this.attempts = attempts;
            this.until = until;
            this.stage = stage == null ? "" : stage;
        }

        boolean isEmpty() {
            return failed <= 0;
        }
    }

    /** 给一组 url 生成行尾摘要；窗口已过或没有记录时返回空串 */
    public static String text(Collection<String> urls) {
        Summary summary = summarize(states(urls), System.currentTimeMillis());
        if (summary.isEmpty()) return "";
        int minutes = minutes(summary.until - System.currentTimeMillis());
        if (summary.failed > 1) return App.get().getString(R.string.config_badge_health_multi, summary.failed, minutes);
        return summary.stage.isEmpty() ? App.get().getString(R.string.config_badge_health_plain, summary.attempts, minutes) : App.get().getString(R.string.config_badge_health, summary.attempts, summary.stage, minutes);
    }

    /** 读取这些 url 的原始状态串（去重，含空值占位由 summarize 过滤） */
    static List<String> states(Collection<String> urls) {
        Set<String> states = new LinkedHashSet<>();
        if (urls != null) for (String url : urls) {
            if (url == null || url.isEmpty()) continue;
            states.add(state(SourceRetry.depotKey(url)));
            states.add(state(SourceRetry.jarKey(url)));
        }
        return new ArrayList<>(states);
    }

    /** 纯逻辑：原始状态串 + 当前时刻 → 聚合结果；只看仍在窗口内的记录 */
    static Summary summarize(Collection<String> states, long now) {
        int failed = 0;
        int attempts = 0;
        long earliest = Long.MAX_VALUE;
        String stage = "";
        if (states != null) for (String raw : states) {
            DepotBackoff.State state = DepotBackoff.parse(raw);
            if (!DepotBackoff.blocked(state, now)) continue;
            failed++;
            if (state.until < earliest) {
                earliest = state.until;
                attempts = state.attempts;
                stage = state.stage;
            }
        }
        return new Summary(failed, attempts, earliest == Long.MAX_VALUE ? 0 : earliest, stage);
    }

    /** 剩余时间取上限到分钟，至少 1 分钟，避免出现「0 分钟后重试」 */
    private static int minutes(long remaining) {
        long value = (Math.max(remaining, 0) + MINUTE - 1) / MINUTE;
        return (int) Math.max(value, 1);
    }

    private static String state(String key) {
        try {
            return Prefers.getString(key);
        } catch (Throwable e) {
            return "";
        }
    }
}
