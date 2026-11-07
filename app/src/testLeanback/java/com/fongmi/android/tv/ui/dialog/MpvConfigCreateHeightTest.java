package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.LinearLayoutCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogMpvConfigCreateBinding;
import com.fongmi.android.tv.utils.ResUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * mpv 配置表单在电视上的内容区必须封顶为屏幕高度九成并内部滚动，
 * 否则脚本配置面板展开后底部按钮会被整块推出屏幕。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class MpvConfigCreateHeightTest {

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    public void bodyFitsScreenOnNarrowTv() {
        assertBody();
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-land-xhdpi")
    public void bodyFitsScreenOnWideTv() {
        assertBody();
    }

    private void assertBody() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_App);
        DialogMpvConfigCreateBinding binding = DialogMpvConfigCreateBinding.inflate(LayoutInflater.from(context));
        // 最高形态：脚本配置 + 脚本按钮 + 编辑区全部展开
        binding.scriptSettingsPanel.setVisibility(View.VISIBLE);
        binding.scriptButtonPanel.setVisibility(View.VISIBLE);
        binding.scriptEdit.setVisibility(View.VISIBLE);
        binding.scriptEnabledRow.setVisibility(View.VISIBLE);
        // 模拟表单内容确实超过一屏
        binding.getRoot().addView(new View(context), new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 900)));

        int screenWidth = ResUtil.getScreenWidth(context);
        int screenHeight = ResUtil.getScreenHeight(context);
        int width = Math.min((int) (screenWidth * 0.56f), ResUtil.dp2px(620));
        int cap = MpvConfigCreateDialog.bodyMaxHeight(context);
        assertTrue("横屏内容区必须封顶：" + cap, cap > 0);
        assertTrue("内容区上限不得超过屏幕高度：" + cap + " > " + screenHeight, cap <= screenHeight);

        View body = MpvConfigCreateDialog.wrapBody(context, binding.getRoot());
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST));
        body.layout(0, 0, body.getMeasuredWidth(), body.getMeasuredHeight());

        assertTrue("内容区高度溢出屏幕：" + body.getMeasuredHeight() + " > " + screenHeight, body.getMeasuredHeight() <= screenHeight);
        assertTrue("内容区高度超出九成屏高上限：" + body.getMeasuredHeight() + " > " + cap, body.getMeasuredHeight() <= cap);
        assertTrue("内容区没有用满封顶高度：" + body.getMeasuredHeight(), body.getMeasuredHeight() > dp(context, 200));
        assertTrue("表单必须比内容区更高，否则用例失去意义", binding.getRoot().getMeasuredHeight() > body.getMeasuredHeight());
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
