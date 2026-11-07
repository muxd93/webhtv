package com.fongmi.android.tv.ui.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.fragment.app.DialogFragment;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.activity.HomeActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 剪贴板浮层注册了全局布局监听：面板展开期间，每一次布局都会重算面板尺寸并重设窗口属性
 * （window.setAttributes 是一次 WindowManager IPC），输入法弹收、列表滚动都会连带触发。
 * 同一组输入重复触发时应该被等值守卫拦掉，尺寸真的变了才刷新——这里用 windowUpdates 计数核对。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class SettingClipboardOverlayLayoutTest {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    @Test
    public void sameSizeGlobalLayoutUpdatesWindowOnce() throws Exception {
        HomeActivity host = Robolectric.buildActivity(HomeActivity.class).create().start().get();
        DialogFragment fragment = new DialogFragment();
        fragment.show(host.getSupportFragmentManager(), "overlay");
        host.getSupportFragmentManager().executePendingTransactions();
        ViewGroup decor = (ViewGroup) host.getWindow().getDecorView();
        size(decor, WIDTH, HEIGHT);

        SettingClipboardOverlay overlay = construct(host, fragment, decor.findViewById(android.R.id.content), decor);
        ShadowLooper.shadowMainLooper().idle();
        // 展开面板：和用户点一下悬浮按钮等价，此时面板窗口才真正显示，updateWindow 才开始有输出
        ((View) field(overlay, "trigger")).performClick();
        ShadowLooper.shadowMainLooper().idle();

        int baseline = overlay.windowUpdates;
        ViewTreeObserver.OnGlobalLayoutListener listener = (ViewTreeObserver.OnGlobalLayoutListener) field(overlay, "layoutListener");
        // 首次布局必须放行（key 初始为空），否则面板尺寸就永远算不出来
        listener.onGlobalLayout();
        assertEquals("首帧布局必须刷新一次窗口", baseline + 1, overlay.windowUpdates);

        int settled = overlay.windowUpdates;
        listener.onGlobalLayout();
        listener.onGlobalLayout();
        assertEquals("输入没变时重复布局必须被拦掉", settled, overlay.windowUpdates);

        size(decor, WIDTH, HEIGHT - 200);
        listener.onGlobalLayout();
        assertEquals("宿主尺寸变化后必须重新刷新窗口", settled + 1, overlay.windowUpdates);
    }

    private static SettingClipboardOverlay construct(Activity activity, DialogFragment fragment, View root, ViewGroup host) throws Exception {
        Constructor<SettingClipboardOverlay> constructor = SettingClipboardOverlay.class.getDeclaredConstructor(Activity.class, DialogFragment.class, View.class, ViewGroup.class);
        constructor.setAccessible(true);
        SettingClipboardOverlay overlay = constructor.newInstance(activity, fragment, root, host);
        invoke(overlay, "attach");
        return overlay;
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        try {
            method.invoke(target);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(target);
        assertNotNull("浮层字段缺失: " + name, value);
        return value;
    }

    /** Robolectric 不会自己跑 layout pass：手动量一次，让 host.getHeight() 有真实值 */
    private static void size(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}
