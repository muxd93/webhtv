package com.fongmi.android.tv.player.danmaku;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DanmakuGateTest {

    @Test
    public void fullyDisabledOnlyWhenNeitherLoadNorShow() {
        // 用户显式关闭"加载"后，仅剩的预加载/上屏语义必须一起失效，才允许短路网络请求。
        assertFalse(DanmakuGate.enabled(false, false));
    }

    @Test
    public void keepsPreloadWhenDisplayIsOff() {
        // 关闭上屏但保留加载属于合法用法：仍然拉取数据，只是不渲染。
        assertTrue(DanmakuGate.enabled(true, false));
    }

    @Test
    public void buildFlagIsAuthoritativeEvenWhenPreferenceIsOn() {
        // 编译期关闭后，历史偏好（曾经打开过弹幕）不得再触发加载或连接；开关为 true 时结果必须与偏好一致。
        assertFalse(DanmakuGate.allowed(false, true));
        assertFalse(DanmakuGate.allowed(false, false));
        assertTrue(DanmakuGate.allowed(true, true));
        assertFalse(DanmakuGate.allowed(true, false));
    }

    @Test
    public void keepsLoadingWhenLoadSwitchIsOffButPlayerToggleIsOn() {
        // 播放器内开关只写显示位，不能因为设置页关闭"加载"就丢掉用户本次的显式开启。
        assertTrue(DanmakuGate.enabled(false, true));
        assertTrue(DanmakuGate.enabled(true, true));
    }
}
