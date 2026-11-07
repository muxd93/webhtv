package com.fongmi.android.tv.player.danmaku;

/**
 * 弹幕开关的组合判定：加载=是否拉取（可只预加载不上屏），显示=是否上屏。
 * 只有两者都关闭才算完全禁用，此时不应对弹幕发起任何请求。
 */
public final class DanmakuGate {

    private DanmakuGate() {
    }

    public static boolean enabled(boolean load, boolean show) {
        return load || show;
    }

    /** 编译期开关是最终权威：关闭时即使偏好为真也不参与；为 true 时结果与偏好完全一致。 */
    public static boolean allowed(boolean featureAvailable, boolean preferenceEnabled) {
        return featureAvailable && preferenceEnabled;
    }
}
