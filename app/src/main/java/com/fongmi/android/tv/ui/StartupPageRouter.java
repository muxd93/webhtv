package com.fongmi.android.tv.ui;

import com.fongmi.android.tv.setting.Setting;

/**
 * 启动页路由判定（纯函数，便于单测）。
 * <p>
 * 只按 {@code ACTION_MAIN} 判定外部启动，不要求 {@code CATEGORY_LAUNCHER}：TV 启动器
 * {@code getLeanbackLaunchIntentForPackage()} 只带 {@code CATEGORY_LEANBACK_LAUNCHER}，
 * 若强制要求 {@code CATEGORY_LAUNCHER}，这些设备上的启动页设置会静默失效。
 * <p>
 * 应用内的 CLEAR_TOP/SINGLE_TOP 拉起使用无 action 的显式 Intent，因此不会被误路由。
 */
public final class StartupPageRouter {

    public enum Target {
        NONE, LIVE, HISTORY, KEEP
    }

    private StartupPageRouter() {
    }

    /**
     * @param startupPage  {@link Setting#getStartupPage()} 当前设置值
     * @param restoring    savedInstanceState != null（进程重建恢复栈，不重复路由）
     * @param launcherMain 启动 Intent 的 action 是否为 {@code ACTION_MAIN}
     */
    public static Target route(int startupPage, boolean restoring, boolean launcherMain) {
        if (restoring || !launcherMain) return Target.NONE;
        if (startupPage == Setting.STARTUP_LIVE) return Target.LIVE;
        if (startupPage == Setting.STARTUP_HISTORY) return Target.HISTORY;
        if (startupPage == Setting.STARTUP_KEEP) return Target.KEEP;
        return Target.NONE;
    }

    /**
     * 首页首帧后是否还要发起直播配置加载。
     * 启动页已路由到直播时，直播页自己发起 {@code LiveConfig.load(callback)} 并持有回调；
     * 首页再发一次会让第二次加载递增 taskId 取消第一次，被取消的一侧既不 success 也不 error，
     * 直播页因此可能一直停在进度态。因此该场景由直播页独占，其余路由保持首页原行为。
     */
    public static boolean shouldHomeLoadLive(Target target) {
        return target != Target.LIVE;
    }
}
