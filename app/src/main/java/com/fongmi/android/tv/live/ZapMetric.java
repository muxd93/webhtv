package com.fongmi.android.tv.live;

import android.util.Log;

/**
 * 换台耗时埋点（SYS4/Stage 4）：logcat 常量 tag "ZapMetric"。
 * tune→resolved 为解析/取址腿；首帧腿由既有 PlaybackTelemetry 的 firstFrameElapsedMs 覆盖。
 * LIVE10 Stage3 补齐预热腿：prewarm start/result/keep 与 reuse 存活提示（推断级；
 * 精确连接复用验证走 debug SpiderDebug 的 "okhttp-player" 日志——connectStart 缺失即复用）。
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

    /** 预热发起：4 秒停留后对邻台方向的首条可预热线路。 */
    public static void prewarmStart(String channel, String endpoint) {
        Log.d(TAG, "prewarm start channel=" + safe(channel) + " endpoint=" + endpoint);
    }

    /** 预热单条线路结果：code=-1 表示未收到响应（连接级失败/被取消）。 */
    public static void prewarmResult(String endpoint, int attempt, int limit, int code, int bytes, long costMs) {
        Log.d(TAG, "prewarm result endpoint=" + endpoint + " attempt=" + attempt + "/" + limit + " code=" + code + " bytes=" + bytes + " cost=" + costMs + "ms");
    }

    /** 换台命中在飞预热同端点，保留该连接未取消。 */
    public static void prewarmKeep(String endpoint) {
        Log.d(TAG, "prewarm keep endpoint=" + endpoint);
    }

    /** 起播复用提示：true=起播端点在预热池存活窗口内（推断级，仅直播起播记录）。 */
    public static void reuse(boolean warmed, String channel) {
        Log.d(TAG, "reuse warm=" + warmed + " channel=" + safe(channel));
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
