package com.fongmi.android.tv.live;

import android.util.Log;

/**
 * 换台耗时埋点（SYS4/Stage 4）：logcat 常量 tag "ZapMetric"。
 * tune→resolved 为解析/取址腿；首帧腿由既有 PlaybackTelemetry 的 firstFrameElapsedMs 覆盖。
 * 验收以埋点 P95 对比为准。
 */
public final class ZapMetric {

    private static final String TAG = "ZapMetric";

    private ZapMetric() {
    }

    public static void tune(String channel, int lines) {
        Log.d(TAG, "tune channel=" + safe(channel) + " lines=" + lines);
    }

    public static void resolved(long sinceTuneMs, String channel) {
        Log.d(TAG, "resolved " + sinceTuneMs + "ms channel=" + safe(channel));
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
