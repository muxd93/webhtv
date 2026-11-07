package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.view.ContextThemeWrapper;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 浅色对话框必须整窗一个口径：M3 把容器背景取到 ?attr/colorSurfaceContainerHigh，
 * 只覆盖 colorSurface 时窗口仍继承 Theme.Material3.Dark，出现"深色外框 + 白色内容卡"的夹层，
 * 且标题色 ?attr/colorOnSurface（#202124）落在深色容器上几乎不可读。
 * 这里同时钉住容器颜色与标题对比度，任一被改回深色都会失败。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class DialogThemeContrastTest {

    @Test
    public void windowContainerSharesTheWhiteContent() {
        Context context = themed();
        assertEquals("对话框容器背景必须与内容白卡同色，否则出现深色外框", Color.WHITE, color(context, com.google.android.material.R.attr.colorSurfaceContainerHigh));
        assertEquals("对话框容器背景必须与内容白卡同色，否则出现深色外框", Color.WHITE, color(context, com.google.android.material.R.attr.colorSurface));
    }

    @Test
    public void titleStaysReadableOnTheContainer() {
        Context context = themed();
        double ratio = contrast(color(context, com.google.android.material.R.attr.colorOnSurface), color(context, com.google.android.material.R.attr.colorSurfaceContainerHigh));
        assertTrue("标题与容器对比度必须 ≥ 4.5，实际 " + ratio, ratio >= 4.5);
    }

    private static Context themed() {
        return new ContextThemeWrapper(new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_App), R.style.ThemeOverlay_WebHTV_LightDialog);
    }

    private static int color(Context context, int attr) {
        TypedArray array = context.obtainStyledAttributes(new int[]{attr});
        int value = array.getColor(0, 0);
        array.recycle();
        return value;
    }

    private static double contrast(int foreground, int background) {
        double a = luminance(foreground);
        double b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        double[] channel = {Color.red(color) / 255d, Color.green(color) / 255d, Color.blue(color) / 255d};
        for (int i = 0; i < channel.length; i++) channel[i] = channel[i] <= 0.03928 ? channel[i] / 12.92 : Math.pow((channel[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
    }
}
