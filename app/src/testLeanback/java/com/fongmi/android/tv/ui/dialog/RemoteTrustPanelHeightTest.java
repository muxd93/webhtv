package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.widget.NestedScrollView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 远程信任主面板的高度必须绑定窗口高度，内容再多也只能在面板内部滚动，
 * 否则底部按钮会被挤出屏幕（与 ShellProxyDialog 的处理方式一致）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class RemoteTrustPanelHeightTest {

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    public void panelFitsScreenOnNarrowTv() throws Exception {
        assertPanel();
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-land-xhdpi")
    public void panelFitsScreenOnWideTv() throws Exception {
        assertPanel();
    }

    private void assertPanel() throws Exception {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_App);
        Object binding = build(context);
        LinearLayoutCompat root = (LinearLayoutCompat) field(binding, "root");
        NestedScrollView scroll = (NestedScrollView) field(binding, "scroll");
        LinearLayoutCompat content = (LinearLayoutCompat) field(binding, "content");

        int screenWidth = ResUtil.getScreenWidth(context);
        int screenHeight = ResUtil.getScreenHeight(context);
        int panelHeight = RemoteTrustDialog.panelHeight(context);
        assertTrue("面板高度不得超过屏幕高度：" + panelHeight + " > " + screenHeight, panelHeight <= screenHeight);

        LinearLayoutCompat.LayoutParams params = (LinearLayoutCompat.LayoutParams) scroll.getLayoutParams();
        assertEquals("横屏滚动区高度必须交给权重分配", 0, params.height);
        assertEquals("横屏滚动区必须占满剩余高度", 1f, params.weight, 0.001f);
        assertTrue("滚动区与工具条之间需要间距", params.topMargin > 0);

        // 模拟设备列表很长：面板若不绑定高度，内容会把工具栏/按钮挤出屏幕
        for (int i = 0; i < 12; i++) {
            View row = new View(context);
            content.addView(row, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 60)));
        }

        ViewGroup.LayoutParams rootParams = root.getLayoutParams();
        if (rootParams == null) rootParams = new LinearLayoutCompat.LayoutParams(screenWidth, panelHeight);
        rootParams.height = panelHeight;
        root.setLayoutParams(rootParams);
        layout(root, Math.round(screenWidth * 0.72f), panelHeight);

        assertTrue("面板测量高度溢出屏幕：" + root.getMeasuredHeight(), root.getMeasuredHeight() <= screenHeight);
        assertTrue("滚动区没有分到高度：" + scroll.getMeasuredHeight(), scroll.getMeasuredHeight() > dp(context, 80));
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            assertTrue("主面板子视图 " + i + " 底部超出面板：" + child.getBottom() + " > " + root.getHeight(), child.getBottom() <= root.getHeight());
        }
        assertTrue("长内容必须落在滚动区内自行滚动：content=" + content.getMeasuredHeight() + ", scroll=" + scroll.getMeasuredHeight(),
                content.getMeasuredHeight() > scroll.getMeasuredHeight());
    }

    private static Object build(Context context) throws Exception {
        Method method = RemoteTrustDialog.class.getDeclaredMethod("build", Context.class);
        method.setAccessible(true);
        return method.invoke(null, context);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
