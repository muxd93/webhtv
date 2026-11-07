package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 首页空态指引只在"真的没有任何源"时触发。
 * 反向用例：源已配置但加载失败（网络/解析问题）时必须保留原来的错误提示，不能误报"还没有添加源"。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class HomeEmptySourceTest {

    @Test
    public void emptyConfigShowsGuide() {
        assertTrue(HomeActivity.isEmptySource(com.fongmi.android.tv.bean.Config.create(0, "", "")));
    }

    @Test
    public void configuredSourceDoesNotShowGuide() {
        assertFalse(HomeActivity.isEmptySource(com.fongmi.android.tv.bean.Config.create(0, "https://example.test/vod.json", "测试源")));
    }

    @Test
    public void nullConfigDoesNotShowGuide() {
        assertFalse(HomeActivity.isEmptySource(null));
    }
}
