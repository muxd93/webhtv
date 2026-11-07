package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.assertEquals;

import android.app.Dialog;
import android.view.View;
import android.widget.LinearLayout;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.utils.ResUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * 通用浅色对话框的正文一旦比可用高度高，wrap_content 窗口会被正文吃满，
 * 后面的操作行只能分到 0 高度 —— 这就是「部分电视上复制地址/取消/打开浏览器三个按钮不显示」。
 * 540dp 高屏（电视常见的最不友好条件）下：高正文必须给窗口显式高度且把正文收成可滚动区；短正文保持 wrap_content 不被拉满。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class, qualifiers = "w960dp-h540dp-land-mdpi")
public class LightDialogHeightTest {

    @Test
    public void tallContentKeepsActionsInsideWindow() {
        HomeActivity host = Robolectric.buildActivity(HomeActivity.class).create().start().get();
        LinearLayout tall = rows(host, 20);
        Dialog dialog = LightDialog.create(host, "调试日志", tall, "打开浏览器", null, "取消", null, "复制地址", null);
        dialog.show();
        ShadowLooper.shadowMainLooper().idle();

        int available = ResUtil.getScreenHeight(host) * 9 / 10;
        assertEquals("高正文必须给窗口显式高度，否则正文吃满可用高度、操作行被压没", available, dialog.getWindow().getAttributes().height);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) tall.getLayoutParams();
        assertEquals("正文必须变成可滚动区，把剩余高度让给操作行", 0, params.height);
        assertEquals("正文必须带 weight 才拿得到剩余高度", 1f, params.weight, 0f);
    }

    @Test
    public void shortContentKeepsWrapContentWindow() {
        HomeActivity host = Robolectric.buildActivity(HomeActivity.class).create().start().get();
        LinearLayout shortContent = rows(host, 1);
        Dialog dialog = LightDialog.create(host, "调试日志", shortContent, "打开浏览器", null, "取消", null, "复制地址", null);
        dialog.show();
        ShadowLooper.shadowMainLooper().idle();

        assertEquals("短正文不能被拉满屏", LinearLayout.LayoutParams.WRAP_CONTENT, dialog.getWindow().getAttributes().height);
        assertEquals("短正文保持自然高度", LinearLayout.LayoutParams.WRAP_CONTENT, shortContent.getLayoutParams().height);
    }

    private static LinearLayout rows(HomeActivity host, int count) {
        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < count; i++) {
            View row = new View(host);
            column.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ResUtil.dp2px(40)));
        }
        return column;
    }
}
