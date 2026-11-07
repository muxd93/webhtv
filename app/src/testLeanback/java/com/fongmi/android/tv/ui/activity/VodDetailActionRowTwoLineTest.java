package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.widget.LinearLayoutCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * 点播详情面板的动作行（row2）必须以封面右沿为起点自动换行，两行内全部完整可见：
 * 既不能被父容器裁掉，也不能把标志行/集数区挤出屏幕，并且焦点上下能跨行移动。
 * 用例覆盖两种电视宽度，并追加两个动作模拟插件运行期注入的按钮（共 8 项）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class VodDetailActionRowTwoLineTest {

    // Robolectric 的字体度量不可靠，固定每项宽度以稳定制造"单行必然溢出"的前提
    private static final int ITEM_WIDTH_DP = 100;
    private static final int ITEM_MARGIN_DP = 16;
    private static final int TRAILING_DP = 24;

    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    public void actionsWrapIntoTwoLinesOnNarrowScreen() {
        assertTwoLines();
    }

    @Test
    @Config(qualifiers = "w1080dp-h620dp-land")
    public void actionsWrapIntoTwoLinesOnWideScreen() {
        assertTwoLines();
    }

    private void assertTwoLines() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_App);
        ActivityVideoBinding binding = ActivityVideoBinding.inflate(LayoutInflater.from(context));
        int width = context.getResources().getDisplayMetrics().widthPixels;
        int height = context.getResources().getDisplayMetrics().heightPixels;

        List<View> items = new ArrayList<>();
        for (int i = 0; i < binding.row2a.getChildCount(); i++) items.add(binding.row2a.getChildAt(i));
        items.add(addAction(binding.row2a, context, "动作七"));
        items.add(addAction(binding.row2a, context, "动作八"));
        for (View item : items) item.getLayoutParams().width = dp(context, ITEM_WIDTH_DP);

        // 复现 bindRow2()：按钮脱离占位行，交给运行期分行
        for (View item : items) ((ViewGroup) item.getParent()).removeView(item);
        binding.row2.removeView(binding.row2a);

        layout(binding.getRoot(), width, height);
        int lead = binding.video.getRight() + dp(context, VideoActivity.ROW2_START_GAP_DP);
        int available = width - lead - dp(context, TRAILING_DP);
        binding.row2.setPadding(lead, 0, dp(context, TRAILING_DP), 0);

        VideoActivity.distributeRow2(binding.row2, items, available);
        layout(binding.getRoot(), width, height);
        // 详情刷新会重新分配，重复执行不得抛 IllegalStateException，也不得丢按钮
        VideoActivity.distributeRow2(binding.row2, items, available);
        layout(binding.getRoot(), width, height);

        int single = 0;
        for (View item : items) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) item.getLayoutParams();
            single += params.width + params.getMarginEnd();
        }
        assertTrue("该宽度下单行不会溢出，用例失去意义：single=" + single + ", available=" + available, single > available);

        assertEquals("动作应按可用宽度分成两行，实际 " + binding.row2.getChildCount() + " 行", 2, binding.row2.getChildCount());
        int placed = 0;
        for (int i = 0; i < binding.row2.getChildCount(); i++) {
            LinearLayoutCompat row = (LinearLayoutCompat) binding.row2.getChildAt(i);
            placed += row.getChildCount();
            View last = row.getChildAt(row.getChildCount() - 1);
            assertTrue("第 " + (i + 1) + " 行末项超出行宽：right=" + last.getRight() + ", 行宽=" + row.getWidth(), last.getRight() <= row.getWidth());
            assertTrue("第 " + (i + 1) + " 行溢出可用宽度：行宽=" + row.getWidth() + ", 可用=" + available, row.getWidth() <= available);
        }
        assertEquals("八项动作必须全部保留", 8, placed);

        LinearLayoutCompat first = (LinearLayoutCompat) binding.row2.getChildAt(0);
        LinearLayoutCompat second = (LinearLayoutCompat) binding.row2.getChildAt(1);
        View firstLast = first.getChildAt(first.getChildCount() - 1);
        View secondFirst = second.getChildAt(0);
        assertEquals("第一行末项向下应进入第二行首项", secondFirst.getId(), firstLast.getNextFocusDownId());
        assertEquals("第二行首项向上应回到第一行对应项", first.getChildAt(0).getId(), secondFirst.getNextFocusUpId());

        // 垂直空间：两行不能把标志行/集数区压出屏幕，集数区要留有可视高度
        binding.episode.setVisibility(View.VISIBLE);
        layout(binding.getRoot(), width, height);
        assertTrue("动作行底部越过屏幕：" + binding.row2.getBottom() + " > " + height, binding.row2.getBottom() < height);
        assertTrue("集数网格被压到无高度：" + binding.episode.getHeight(), binding.episode.getHeight() > dp(context, 64));
    }

    /**
     * 回归：封面尺寸变化会在 layout 过程中触发 bindRow2 的回调。真机上此刻直接重建 row2，新行会在
     * 同一个 layout 通道里未经 measure 就被 layout，LinearLayoutCompat.layoutHorizontal 读到空的
     * mMaxAscent 数组必然 NullPointerException（11-05 20:44 崩溃即此路径）。生产代码把重排延到本轮
     * 布局结束后执行，本用例按同一条路径验证：不再崩溃且重排结果在下一轮消息里确实落地。
     */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    public void redistributeDuringLayoutDoesNotCrash() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_App);
        ActivityVideoBinding binding = ActivityVideoBinding.inflate(LayoutInflater.from(context));
        int width = context.getResources().getDisplayMetrics().widthPixels;
        int height = context.getResources().getDisplayMetrics().heightPixels;
        List<View> items = new ArrayList<>();
        for (int i = 0; i < binding.row2a.getChildCount(); i++) items.add(binding.row2a.getChildAt(i));
        for (View item : items) ((ViewGroup) item.getParent()).removeView(item);
        binding.row2.removeView(binding.row2a);

        Runnable redistribute = () -> VideoActivity.distributeRow2(binding.row2, items, binding.getRoot().getWidth() - binding.row2.getPaddingStart() - binding.row2.getPaddingEnd());
        // 与 bindRow2() 的回调等价：layout 进行中只更新内边距，重建子视图延后执行
        binding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right == oldRight) return;
            binding.row2.setPadding(right + dp(context, VideoActivity.ROW2_START_GAP_DP), 0, dp(context, TRAILING_DP), 0);
            VideoActivity.postRedistributeRow2(redistribute);
        });

        layout(binding.getRoot(), width, height);
        ShadowLooper.idleMainLooper();
        assertTrue("首帧后动作行未重排", binding.row2.getChildCount() > 0);
        // 首帧后按钮已带测量值；改变封面宽度模拟全屏/音频模式切换等真机场景
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) binding.video.getLayoutParams();
        params.width = binding.video.getWidth() / 2;
        binding.video.setLayoutParams(params);
        layout(binding.getRoot(), width, height);
        ShadowLooper.idleMainLooper();
        assertTrue("重排后动作行丢失", binding.row2.getChildCount() > 0);
        assertTrue("行2 起点未跟随封面右沿：" + binding.row2.getPaddingStart() + " != " + binding.video.getRight(), binding.row2.getPaddingStart() == binding.video.getRight() + dp(context, VideoActivity.ROW2_START_GAP_DP));
    }

    /**
     * 回归：setVideoDetailsVisible(true) 会重排动作行，真机上该调用可能落在 layout 通道内（模式切换会先改
     * 封面尺寸，进而回调详情面板）。同步重排会复现 11-05 崩溃（见上方用例的复现前提），生产代码经
     * postRedistributeRow2 延后到本轮布局结束。本用例走同一条生产路径，验证不再崩溃且重排确实落地。
     */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    public void setVideoDetailsVisibleDuringLayoutDefersRedistribute() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_App);
        ActivityVideoBinding binding = ActivityVideoBinding.inflate(LayoutInflater.from(context));
        int width = context.getResources().getDisplayMetrics().widthPixels;
        int height = context.getResources().getDisplayMetrics().heightPixels;
        List<View> items = new ArrayList<>();
        for (int i = 0; i < binding.row2a.getChildCount(); i++) items.add(binding.row2a.getChildAt(i));
        for (View item : items) ((ViewGroup) item.getParent()).removeView(item);
        binding.row2.removeView(binding.row2a);

        Runnable redistribute = () -> VideoActivity.distributeRow2(binding.row2, items,
                binding.getRoot().getWidth() - binding.row2.getPaddingStart() - binding.row2.getPaddingEnd());
        binding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right == oldRight) return;
            binding.row2.setPadding(right + dp(context, VideoActivity.ROW2_START_GAP_DP), 0, dp(context, TRAILING_DP), 0);
            // 与 setVideoDetailsVisible(true) 相同的生产路径
            VideoActivity.postRedistributeRow2(redistribute);
        });

        layout(binding.getRoot(), width, height);
        ShadowLooper.idleMainLooper();
        assertTrue("首帧后动作行未重排", binding.row2.getChildCount() > 0);

        // 再次触发（模拟详情面板重新显示且封面宽度已变化）
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) binding.video.getLayoutParams();
        params.width = binding.video.getWidth() / 2;
        binding.video.setLayoutParams(params);
        layout(binding.getRoot(), width, height);
        ShadowLooper.idleMainLooper();
        assertTrue("重排后动作行丢失", binding.row2.getChildCount() > 0);
        assertEquals("行2 起点未跟随封面右沿", binding.video.getRight() + dp(context, VideoActivity.ROW2_START_GAP_DP), binding.row2.getPaddingStart());
    }

    private static TextView addAction(LinearLayoutCompat row, Context context, String text) {
        TextView action = new TextView(context);
        action.setText(text);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(context, ITEM_MARGIN_DP));
        row.addView(action, params);
        return action;
    }

    private static void layout(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

}
