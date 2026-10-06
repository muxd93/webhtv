package com.fongmi.android.tv.player;

import android.util.Log;

/**
 * 自动换源埋点（FB-T）：logcat 常量 tag "AutoChangeMetric"。
 * attempt 记一次换源尝试（reason: detail_empty/flag_exhausted/play_error/manual），
 * result 记整条换源链的最终结果（成功=播放 READY，失败=链上无源可用或报错终止）。
 */
public final class AutoChangeMetric {

    private static final String TAG = "AutoChangeMetric";

    private AutoChangeMetric() {
    }

    public static void attempt(String reason, String fromKey, String toKey) {
        Log.d(TAG, "attempt reason=" + safe(reason) + " from=" + safe(fromKey) + " to=" + safe(toKey));
    }

    public static void result(boolean success, long sinceFirstAttemptMs, String toKey) {
        Log.d(TAG, "result success=" + success + " " + sinceFirstAttemptMs + "ms to=" + safe(toKey));
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
