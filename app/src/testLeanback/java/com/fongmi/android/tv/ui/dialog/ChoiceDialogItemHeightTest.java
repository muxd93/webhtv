package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * ChoiceDialog 的条目行高必须由内容决定：长站点名/长规则名需要换行显示，
 * 固定行高会把第二行之后的文本裁掉。放宽到两行上限并带省略号，避免单条占满整屏。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class ChoiceDialogItemHeightTest {

    private static final String LONG = "这是一个用于验证设置对话框条目行高是否足够容纳多行文本的超长站点名称，"
            + "字符数量足以保证在电视宽度下至少需要三行才能完整显示，因此必须允许条目自身增高或省略收尾";

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    public void itemGrowsWithLongTextOnNarrowScreen() {
        assertItem();
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-land-xhdpi")
    public void itemGrowsWithLongTextOnWideScreen() {
        assertItem();
    }

    private void assertItem() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_App);
        MaterialButton button = new MaterialButton(context);
        ChoiceDialog.applyItemLayout(button);
        button.setText(LONG);

        ViewGroup.LayoutParams params = button.getLayoutParams();
        assertEquals("条目高度必须由内容决定，固定 dp 行高会在多行文本时裁切", ViewGroup.LayoutParams.WRAP_CONTENT, params.height);
        assertEquals("条目文本最多两行", 2, button.getMaxLines());
        assertEquals("超过两行的文本必须以省略号收尾", TextUtils.TruncateAt.END, button.getEllipsize());

        int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.52f);
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST));

        assertTrue("条目高度不应低于单行高度：" + button.getMeasuredHeight(), button.getMeasuredHeight() >= dp(context, 46));
        assertTrue("条目高度应被限制在两行以内：" + button.getMeasuredHeight(), button.getMeasuredHeight() <= dp(context, 72));
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
