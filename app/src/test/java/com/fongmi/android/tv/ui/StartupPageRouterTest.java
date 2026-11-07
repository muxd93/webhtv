package com.fongmi.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.setting.Setting;

import org.junit.Test;

public class StartupPageRouterTest {

    private static void assertRoute(int page, boolean restoring, boolean launcherMain, StartupPageRouter.Target expected) {
        assertEquals(expected, StartupPageRouter.route(page, restoring, launcherMain));
    }

    @Test
    public void coldLauncherRoutesToConfiguredPage() {
        assertRoute(Setting.STARTUP_LIVE, false, true, StartupPageRouter.Target.LIVE);
        assertRoute(Setting.STARTUP_HISTORY, false, true, StartupPageRouter.Target.HISTORY);
        assertRoute(Setting.STARTUP_KEEP, false, true, StartupPageRouter.Target.KEEP);
    }

    @Test
    public void homeSettingStaysOnHome() {
        assertRoute(Setting.STARTUP_HOME, false, true, StartupPageRouter.Target.NONE);
    }

    @Test
    public void unknownPageFallsBackHome() {
        assertRoute(99, false, true, StartupPageRouter.Target.NONE);
        assertRoute(-1, false, true, StartupPageRouter.Target.NONE);
    }

    @Test
    public void internalRelaunchDoesNotRoute() {
        // 应用内 CLEAR_TOP/SINGLE_TOP 拉起 HomeActivity 使用无 action 的显式 Intent
        assertRoute(Setting.STARTUP_LIVE, false, false, StartupPageRouter.Target.NONE);
        assertRoute(Setting.STARTUP_HISTORY, false, false, StartupPageRouter.Target.NONE);
        assertRoute(Setting.STARTUP_KEEP, false, false, StartupPageRouter.Target.NONE);
    }

    @Test
    public void restoringStackDoesNotRoute() {
        assertRoute(Setting.STARTUP_LIVE, true, true, StartupPageRouter.Target.NONE);
        assertRoute(Setting.STARTUP_HISTORY, true, true, StartupPageRouter.Target.NONE);
        assertRoute(Setting.STARTUP_KEEP, true, true, StartupPageRouter.Target.NONE);
    }

    @Test
    public void historyTargetDoesNotDependOnHistoryContent() {
        // 历史为空时仍进入历史页展示空态，不静默回落首页（历史内容判断是异步 DB 查询，路由阶段不可得）
        assertRoute(Setting.STARTUP_HISTORY, false, true, StartupPageRouter.Target.HISTORY);
    }

    @Test
    public void liveStartupPageLeavesLiveConfigLoadToLivePage() {
        // 启动页=直播：直播页已发起加载并持有 success 回调；首页若再发一次会取消该任务且两个回调都不回报，
        // 直播页无缓存首启就会一直停在进度态。因此首页必须跳过，只保留一个加载方。
        StartupPageRouter.Target target = StartupPageRouter.route(Setting.STARTUP_LIVE, false, true);
        assertEquals(StartupPageRouter.Target.LIVE, target);
        assertFalse(StartupPageRouter.shouldHomeLoadLive(target));
    }

    @Test
    public void otherLaunchesKeepHomeLiveLoad() {
        // 启动页=首页/历史/收藏：直播页不是加载方，首页仍是唯一加载方（既有行为不变）
        for (int page : new int[]{Setting.STARTUP_HOME, Setting.STARTUP_HISTORY, Setting.STARTUP_KEEP}) {
            StartupPageRouter.Target target = StartupPageRouter.route(page, false, true);
            assertTrue(StartupPageRouter.shouldHomeLoadLive(target));
        }
        // 进程重建恢复栈、应用内 CLEAR_TOP 拉起：不路由，首页照常加载
        assertTrue(StartupPageRouter.shouldHomeLoadLive(StartupPageRouter.route(Setting.STARTUP_LIVE, true, true)));
        assertTrue(StartupPageRouter.shouldHomeLoadLive(StartupPageRouter.route(Setting.STARTUP_LIVE, false, false)));
    }
}
