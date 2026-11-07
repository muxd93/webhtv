package com.fongmi.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThirdPartyToastPolicyTest {

    @Test
    public void suppressesThirdPartyToastOnlyAfterExplicitDisable() {
        // 用户显式关闭弹幕后，插件（非 com.fongmi.android.tv）的提示才允许被抑制。
        assertTrue(ThirdPartyToastPolicy.shouldSuppress("com.github.catvod.spider.Panda", true, false));
        assertTrue(ThirdPartyToastPolicy.shouldSuppress("a.b.c", true, false));
    }

    @Test
    public void neverSuppressesFreshInstallDefaults() {
        // 弹幕默认值即关闭；"从未开启"不等于"用户主动关闭"，否则新用户会丢掉所有插件提示。
        assertFalse(ThirdPartyToastPolicy.shouldSuppress("com.github.catvod.spider.Panda", false, false));
    }

    @Test
    public void neverSuppressesAppMessages() {
        // App 自身提示（站点 msg、播放错误、danmaku_api_invalid）必须照常弹出。
        assertFalse(ThirdPartyToastPolicy.shouldSuppress("com.fongmi.android.tv.ui.activity.VideoActivity", true, false));
        assertFalse(ThirdPartyToastPolicy.shouldSuppress("com.fongmi.android.tv.web.HomeWebBridge", true, false));
    }

    @Test
    public void degradesToNotSuppressingWhenCallerUnknown() {
        // 调用方不可判定时宁可多弹也不误杀。
        assertFalse(ThirdPartyToastPolicy.shouldSuppress(null, true, false));
        assertFalse(ThirdPartyToastPolicy.shouldSuppress("", true, false));
    }

    @Test
    public void keepsThirdPartyMessagesWhileDanmakuEnabled() {
        // 弹幕开着时照常弹，避免误杀插件其它必要提示（密码错误、解析失败等）。
        assertFalse(ThirdPartyToastPolicy.shouldSuppress("com.github.catvod.spider.Panda", true, true));
    }
}
