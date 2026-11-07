package com.fongmi.android.tv.utils;

/**
 * 判定一条 Toast 是否应在"用户显式关闭弹幕"时被抑制。
 * 纯函数，便于 JVM 单测；不依赖 ClassLoader（{@link StackTraceElement} 只给类名字符串）。
 */
public final class ThirdPartyToastPolicy {

    private static final String APP_PREFIX = "com.fongmi.android.tv";

    private ThirdPartyToastPolicy() {
    }

    /**
     * @param className            调用方类名；null/空 表示调用方不可判定
     * @param explicitlyConfigured 用户是否显式设置过弹幕开关。默认值即关闭，不能把"从未开启"当成"用户关闭"
     * @param danmakuEnabled       弹幕是否仍启用（加载或显示任一开启）
     */
    public static boolean shouldSuppress(String className, boolean explicitlyConfigured, boolean danmakuEnabled) {
        if (!explicitlyConfigured || danmakuEnabled) return false;
        if (className == null || className.isEmpty()) return false;
        return !className.startsWith(APP_PREFIX);
    }
}
