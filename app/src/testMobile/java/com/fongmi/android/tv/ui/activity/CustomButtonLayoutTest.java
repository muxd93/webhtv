package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 手机端左右按钮的布局写在 layout 回调里：参数未变时必须短路，否则每次布局回调都会再触发一次
 * requestLayout；参数真的变化时必须更新（反向用例，防止为了省调用把功能静默废掉）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class CustomButtonLayoutTest {

    @Test
    public void unchangedParamsSkipRewrite() {
        assertFalse(VideoActivity.customButtonLayoutChanged(480, 120, 480, 120));
    }

    @Test
    public void changedWidthUpdatesLayout() {
        assertTrue(VideoActivity.customButtonLayoutChanged(720, 120, 480, 120));
    }

    @Test
    public void changedTopUpdatesLayout() {
        assertTrue(VideoActivity.customButtonLayoutChanged(480, 60, 480, 120));
    }
}
