package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.widget.LinearLayoutCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 详情列的三套隐藏机制分工契约（改动任一条都要跑本用例 + {@link VideoFullscreenDetailsTest}
 * + {@link VideoDetailsSpacingTest} + {@link VodDetailActionRowTwoLineTest}）：
 * <ol>
 *   <li><b>锚点</b>：{@code row2} 锚在 {@code actor} 之后、{@code scroll} 锚在 {@code row2} 之后，
 *       整列靠布局关系串起来（activity_video.xml，历史教训 cab2d23442）；</li>
 *   <li><b>显隐</b>：{@code setVideoDetailsVisible} 是唯一收口点，7 个视图同进同出，
 *       满屏/音频台都在这里判，且只改 visibility 不重建视图树；</li>
 *   <li><b>位移</b>：{@code bindRow2} 的 video layout 回调只 {@code setPadding}，
 *       动作行的重建一律延后到本帧布局结束（历史教训 e6b85f7e3d）。</li>
 * </ol>
 * 三条互相兜底，任何一条被单独“简化”都会让某类电视/某类切换路径漏出详情列。
 *
 * <p>注意：本用例不排空主线程消息队列。Robolectric 下 {@code PlaybackService} 的绑定回调会在
 * {@code idleMainLooper()} 里抛 NPE，而“延后重排最终落地”已由
 * {@link VodDetailActionRowTwoLineTest#redistributeDuringLayoutDoesNotCrash()} 覆盖，
 * 这里只钉住 layout 通道内“只位移、不动视图树”这一半契约。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class, qualifiers = "w960dp-h540dp-land-mdpi")
public class VideoDetailsContractTest {

    private static final int[] DETAIL_IDS = {R.id.name, R.id.remark, R.id.row1, R.id.director, R.id.actor, R.id.row2, R.id.scroll};

    /** 机制一：锚点关系一改，隐藏一侧就失去隐式支撑，必须显式钉住。 */
    @Test
    public void detailColumnStaysAnchoredInOrder() {
        VideoActivity activity = Robolectric.buildActivity(VideoActivity.class).create().start().get();
        RelativeLayout.LayoutParams row2 = (RelativeLayout.LayoutParams) activity.findViewById(R.id.row2).getLayoutParams();
        assertEquals("row2 必须仍锚在 actor 之后", R.id.actor, row2.getRule(RelativeLayout.BELOW));

        RelativeLayout.LayoutParams scroll = (RelativeLayout.LayoutParams) activity.findViewById(R.id.scroll).getLayoutParams();
        // scroll 原来锚在 video 之下（cab2d23442），video 撑满屏幕就把它挤出可视区
        assertEquals("选集列必须仍锚在 row2 之后", R.id.row2, scroll.getRule(RelativeLayout.BELOW));
        assertEquals("选集列必须仍贴底，靠 row2 之后的剩余空间展示", RelativeLayout.TRUE, scroll.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM));
    }

    /** 机制二：显隐只改 visibility、由单一入口收口，且不重建动作行视图树。 */
    @Test
    public void visibilitySwitchHidesAllDetailsWithoutRebuilding() throws Exception {
        VideoActivity activity = Robolectric.buildActivity(VideoActivity.class).create().start().get();
        fillDetailTexts(activity);
        ViewGroup row2 = prepareRow2(activity);
        assertTrue("动作行未分配，用例失去意义", row2.getChildCount() > 0);
        View firstRow = row2.getChildAt(0);

        invoke(activity, "setVideoDetailsVisible", false);
        assertVisibility(activity, View.GONE, "隐藏时详情/选集列必须全部收起");
        assertSame("显隐不得重建动作行视图树", firstRow, row2.getChildAt(0));

        invoke(activity, "setVideoDetailsVisible", true);
        assertVisibility(activity, View.VISIBLE, "恢复时详情/选集列必须全部可见");
        assertSame("恢复可见同样不得重建视图树", firstRow, row2.getChildAt(0));

        // 满屏是同一入口的另一个输入，不能绕开它自己改 visibility
        invoke(activity, "enterFullscreen");
        assertVisibility(activity, View.GONE, "满屏时同一入口必须仍然收口");
    }

    /** 机制三：layout 通道内只位移（padding），结构性重建必须延后到本帧布局结束。 */
    @Test
    public void layoutCallbackOnlyMovesWithoutTouchingViewTree() throws Exception {
        VideoActivity activity = Robolectric.buildActivity(VideoActivity.class).create().start().get();
        View video = activity.findViewById(R.id.video);
        ViewGroup row2 = prepareRow2(activity);
        View firstRow = row2.getChildAt(0);
        int itemsBefore = countItems(row2);

        // 模拟封面尺寸变化（全屏/音频台切换前必然发生），触发 production 里的 bindRow2 回调
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) video.getLayoutParams();
        params.width = video.getWidth() / 2;
        video.setLayoutParams(params);
        layout(activity);

        assertEquals("layout 回调必须同步把 row2 起点跟随封面右沿并留出横向间隙",
                video.getRight() + ResUtil.dp2px(VideoActivity.ROW2_START_GAP_DP), row2.getPaddingStart());
        assertSame("layout 通道内不得重建动作行", firstRow, row2.getChildAt(0));
        assertEquals("layout 通道内不得增删动作按钮", itemsBefore, countItems(row2));
    }

    /**
     * 复现 bindRow2 之后的运行期状态：占位行已摘除、按钮已进 mRow2Items，按生产同一入口完成首轮分配。
     * 不走消息队列，避免 Robolectric 的播放服务绑定回调干扰。
     */
    private static ViewGroup prepareRow2(VideoActivity activity) throws Exception {
        LinearLayoutCompat row2 = activity.findViewById(R.id.row2);
        layout(activity);
        List<View> items = row2Items(activity);
        int available = activity.findViewById(android.R.id.content).getWidth() - row2.getPaddingStart() - row2.getPaddingEnd();
        VideoActivity.distributeRow2(row2, items, available);
        return row2;
    }

    @SuppressWarnings("unchecked")
    private static List<View> row2Items(VideoActivity activity) throws Exception {
        Field field = VideoActivity.class.getDeclaredField("mRow2Items");
        field.setAccessible(true);
        return (List<View>) field.get(activity);
    }

    private static int countItems(ViewGroup row2) {
        int count = 0;
        for (int i = 0; i < row2.getChildCount(); i++) count += ((ViewGroup) row2.getChildAt(i)).getChildCount();
        return count;
    }

    private static void fillDetailTexts(VideoActivity activity) {
        // 这三个视图的可见性依赖文本非空
        for (int id : new int[]{R.id.remark, R.id.director, R.id.actor}) ((TextView) activity.findViewById(id)).setText("详情");
    }

    private static void assertVisibility(VideoActivity activity, int expected, String message) {
        for (int id : DETAIL_IDS) {
            View view = activity.findViewById(id);
            assertEquals(message + "（id=" + activity.getResources().getResourceEntryName(id) + "）", expected, view.getVisibility());
        }
    }

    private static void layout(VideoActivity activity) {
        // 与真机一致：从 decor 根节点开始测量布局，活动窗口在 Robolectric 下不会自动排版
        View root = activity.getWindow().getDecorView();
        int width = root.getResources().getDisplayMetrics().widthPixels;
        int height = root.getResources().getDisplayMetrics().heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static void invoke(VideoActivity activity, String name, boolean value) throws Exception {
        Method method = VideoActivity.class.getDeclaredMethod(name, boolean.class);
        method.setAccessible(true);
        method.invoke(activity, value);
    }

    private static void invoke(VideoActivity activity, String name) throws Exception {
        Method method = VideoActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }
}
