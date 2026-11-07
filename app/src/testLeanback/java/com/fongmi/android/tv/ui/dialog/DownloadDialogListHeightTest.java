package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;

import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.databinding.DialogDownloadBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.utils.ResUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/**
 * 缓存剧集对话框的列表高度必须跟随可用高度收缩。
 * 540dp 高的电视上，固定 300dp 的列表加上标题/提示/操作行/内边距会超过屏高，底部按钮被推到屏幕外。
 * 这里按 540dp 横屏（电视最不友好的常见条件）断言列表不超过屏高的 40%，旧实现固定 300dp 必然失败。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class, qualifiers = "w960dp-h540dp-land-mdpi")
public class DownloadDialogListHeightTest {

    private AppDatabase db;

    @Before
    public void setUp() throws Exception {
        // 适配器按集数查「已缓存」状态；这里放行主线程查询，避免把无关的主线程 DB 问题混进本用例
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase.class).allowMainThreadQueries().build();
        Field field = AppDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, db);
    }

    @After
    public void tearDown() throws Exception {
        Field field = AppDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
        db.close();
    }

    @Test
    public void episodeListShrinksOnShortScreens() throws Exception {
        HomeActivity host = Robolectric.buildActivity(HomeActivity.class).create().start().get();
        DownloadDialog dialog = DownloadDialog.create().siteKey("test").vodId("1").vodName("测试剧").flags(flags(8));
        dialog.show(host);
        ShadowLooper.shadowMainLooper().idle();

        DialogDownloadBinding binding = binding(dialog);
        int screen = ResUtil.getScreenHeight(host);
        int height = binding.recycler.getLayoutParams().height;
        assertTrue("矮屏上列表高度必须跟随屏高收缩，不能是固定的 300dp", height <= screen * 2 / 5);
        assertTrue("收缩后仍要保留可用高度", height >= ResUtil.dp2px(120));
        assertEquals("有剧集时列表必须可见", View.VISIBLE, binding.recycler.getVisibility());
    }

    private static List<Flag> flags(int count) {
        Flag flag = new Flag("测试线路");
        for (int i = 0; i < count; i++) flag.getEpisodes().add(Episode.create("第" + (i + 1) + "集", "https://example.test/" + i + ".m3u8"));
        return Collections.singletonList(flag);
    }

    private static DialogDownloadBinding binding(DownloadDialog dialog) throws Exception {
        Field field = DownloadDialog.class.getDeclaredField("binding");
        field.setAccessible(true);
        return (DialogDownloadBinding) field.get(dialog);
    }
}
