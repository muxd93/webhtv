package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.RelativeLayout;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 详情页 6 个动作按钮（简介/短显/搜索/收藏/缓存/换源）承载在 row2，row2 锚在 actor 之下。
 * 旧间距只有 6dp，按钮块紧贴视频预览的下沿，需要留出可见的间距。
 * 同时锁住锚点：满屏隐藏由 setVideoDetailsVisible 显式控制，锚点不能随手改动（见 cab2d23442 的教训）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class VideoDetailsSpacingTest {

    @Test
    public void actionRowKeepsGapFromDetailText() {
        VideoActivity activity = Robolectric.buildActivity(VideoActivity.class).create().start().get();
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) activity.findViewById(R.id.row2).getLayoutParams();

        assertTrue("动作行与详情文本之间至少留 18dp 间距，否则按钮块贴住视频预览下沿", params.topMargin >= ResUtil.dp2px(18));
        assertEquals("动作行的锚点必须是 actor，改动会牵连满屏隐藏契约", R.id.actor, params.getRule(RelativeLayout.BELOW));
    }

    /** 横向：row2 的 paddingStart 曾是 video.right（0 间隙），六个按钮方块视觉上贴着预览右沿 */
    @Test
    public void actionRowKeepsSideGapFromVideoPreview() {
        VideoActivity activity = Robolectric.buildActivity(VideoActivity.class).create().start().get();
        layout(activity);
        View video = activity.findViewById(R.id.video);
        View row2 = activity.findViewById(R.id.row2);
        int gap = row2.getPaddingStart() - video.getRight();
        assertTrue("按钮块与预览右沿至少留 16dp 横向间隙，实际 " + gap + "px", gap >= ResUtil.dp2px(16));
    }

    private static void layout(VideoActivity activity) {
        View root = activity.getWindow().getDecorView();
        int width = root.getResources().getDisplayMetrics().widthPixels;
        int height = root.getResources().getDisplayMetrics().heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
}
