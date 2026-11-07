package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.setting.PlayerSetting;

/**
 * 集中管理 {@code BuildConfig.FEATURE_*} 与播放器引擎可用性，供代码层守卫与 UI 显隐使用。
 *
 * <p>规则：默认全部开启（保持现行为）。通过 {@code -Pwebhtv.feature.<name>=false} 或
 * {@code -Pwebhtv.profile=lite|minimal} 关闭后，对应重型资产/原生库不打进 APK，且本类会让
 * 相关功能在运行时自动降级/禁用，避免崩溃。
 */
public final class FeatureGate {

    private FeatureGate() {
    }

    public static boolean mpv() {
        return BuildConfig.FEATURE_MPV;
    }

    public static boolean ijk() {
        return BuildConfig.FEATURE_IJK;
    }

    public static boolean exoAss() {
        return BuildConfig.FEATURE_EXOASS;
    }

    public static boolean danmaku() {
        return BuildConfig.FEATURE_DANMAKU;
    }

    public static boolean webHome() {
        return BuildConfig.FEATURE_WEBHOME;
    }

    public static boolean newPipe() {
        return BuildConfig.FEATURE_NEWPIPE;
    }

    /** YouTube 解析通道：TRUE 时保留 jar 加载的 iframe_api 探测与 Youtube 提取器，FALSE 时零探测、跳过该通道。 */
    public static boolean youtube() {
        return BuildConfig.FEATURE_YOUTUBE;
    }

    /** 播放器引擎是否可用：EXO 始终可用；MPV/IJK 受对应开关约束。 */
    public static boolean playerEnabled(int player) {
        if (player == PlayerSetting.MPV) return mpv();
        if (player == PlayerSetting.IJK) return ijk();
        return true;
    }
}
