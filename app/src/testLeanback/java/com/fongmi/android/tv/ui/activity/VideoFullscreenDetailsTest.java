package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;

import android.view.View;
import android.widget.TextView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 满屏播放时详情/选集列不能浮在画面上。
 * 老布局把 scroll 锚在 video 之下，video 撑满屏幕就把整列挤出可视区；改成锚在 row2 之后，
 * 撑满屏幕不再挤出，必须显式隐藏，否则选集列表停在画面中下方。
 * 详情列现在由三套机制各自隐藏（横向溢出 / row2 的 padding 位移 / 显式 setVisibility），
 * 这里要求它们统一由 setVideoDetailsVisible 收口。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class VideoFullscreenDetailsTest {

    private static final int[] DETAIL_IDS = {R.id.name, R.id.remark, R.id.row1, R.id.director, R.id.actor, R.id.row2, R.id.scroll};
    private static final int[] EPISODE_IDS = {R.id.row2, R.id.scroll};

    @Test
    public void detailsColumnHidesInFullscreenAndRestoresAfterExit() throws Exception {
        VideoActivity activity = Robolectric.buildActivity(VideoActivity.class).create().start().get();
        fillDetailTexts(activity);

        invoke(activity, "enterFullscreen");
        assertVisibility(activity, DETAIL_IDS, View.GONE, "满屏时详情/选集列必须全部隐藏");

        invoke(activity, "exitFullscreen");
        assertVisibility(activity, DETAIL_IDS, View.VISIBLE, "退出满屏后详情/选集列必须全部恢复");
    }

    @Test
    public void audioStageKeepsDetailsHiddenAfterLeavingFullscreen() throws Exception {
        VideoActivity activity = Robolectric.buildActivity(VideoActivity.class).create().start().get();
        setField(activity, "mAudioStageVisible", true);

        invoke(activity, "enterFullscreen");
        assertVisibility(activity, EPISODE_IDS, View.GONE, "音频台满屏时选集列必须隐藏");

        invoke(activity, "exitFullscreen");
        assertVisibility(activity, EPISODE_IDS, View.GONE, "音频台仍可见时退出满屏，选集列不得冒出");
    }

    private static void fillDetailTexts(VideoActivity activity) {
        // 这三个视图的可见性依赖文本非空，先填入内容才能验证“退出满屏后恢复可见”
        for (int id : new int[]{R.id.remark, R.id.director, R.id.actor}) ((TextView) activity.findViewById(id)).setText("详情");
    }

    private static void assertVisibility(VideoActivity activity, int[] ids, int expected, String message) {
        for (int id : ids) {
            View view = activity.findViewById(id);
            assertEquals(message + "（id=" + activity.getResources().getResourceEntryName(id) + "）", expected, view.getVisibility());
        }
    }

    private static void invoke(VideoActivity activity, String name) throws Exception {
        Method method = VideoActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }

    private static void setField(VideoActivity activity, String name, Object value) throws Exception {
        Field field = VideoActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }
}
