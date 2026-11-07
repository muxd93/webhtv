package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.ui.HomeRows;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页功能行新增「缓存」入口：位置固定在收藏与推送之间，点击直达下载管理页，且不打乱相邻按钮的去向。
 * 焦点链由这一行的结构决定（左右相邻由 HorizontalGridView 按 adapter 顺序走，两端由 FuncPresenter 环绕），
 * 所以顺序断言同时钉住了「遥控器左右移动不会跳号」这一条。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class HomeFuncEntryTest {

    private AppDatabase db;

    @Before
    public void setUp() throws Exception {
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
    public void downloadEntrySitsBetweenKeepAndPush() throws Exception {
        List<Func> funcs = funcs();

        int keep = indexOf(funcs, R.string.home_keep);
        int download = indexOf(funcs, R.string.download_action);
        int push = indexOf(funcs, R.string.home_push);

        assertTrue("缓存入口必须存在", download >= 0);
        assertEquals("缓存入口必须紧挨收藏之后", keep + 1, download);
        assertEquals("推送必须紧挨缓存之后", download + 1, push);
        assertNotEquals("缓存入口不能没有图标", 0, funcs.get(download).getDrawable());
    }

    @Test
    public void eachEntryStillOpensItsOwnPage() throws Exception {
        HomeActivity home = Robolectric.buildActivity(HomeActivity.class).create().get();
        List<Func> funcs = funcs(home);

        assertEquals(KeepActivity.class.getName(), started(home, funcs.get(indexOf(funcs, R.string.home_keep))).getComponent().getClassName());
        assertEquals(DownloadActivity.class.getName(), started(home, funcs.get(indexOf(funcs, R.string.download_action))).getComponent().getClassName());
        assertEquals(PushActivity.class.getName(), started(home, funcs.get(indexOf(funcs, R.string.home_push))).getComponent().getClassName());
    }

    /** 无直播源时这一排就是「搜索/收藏/缓存/推送/设置」，缓存固定在第三个位置 */
    @Test
    public void downloadEntryIsThirdWithoutLiveConfig() throws Exception {
        List<Func> funcs = funcs();
        assertEquals(5, funcs.size());
        assertEquals(R.string.home_search, funcs.get(0).getResId());
        assertEquals(R.string.home_keep, funcs.get(1).getResId());
        assertEquals(R.string.download_action, funcs.get(2).getResId());
        assertEquals(R.string.home_push, funcs.get(3).getResId());
        assertEquals(R.string.home_setting, funcs.get(4).getResId());
    }

    private List<Func> funcs() throws Exception {
        return funcs(Robolectric.buildActivity(HomeActivity.class).create().get());
    }

    private List<Func> funcs(HomeActivity home) throws Exception {
        Method setFunc = HomeActivity.class.getDeclaredMethod("setFunc");
        setFunc.setAccessible(true);
        setFunc.invoke(home);

        Field rows = HomeActivity.class.getDeclaredField("mRows");
        rows.setAccessible(true);
        HomeRows homeRows = (HomeRows) rows.get(home);
        Field adapter = HomeRows.class.getDeclaredField("mFuncAdapter");
        adapter.setAccessible(true);
        ArrayObjectAdapter funcAdapter = (ArrayObjectAdapter) adapter.get(homeRows);

        List<Func> items = new ArrayList<>();
        for (int i = 0; i < funcAdapter.size(); i++) items.add((Func) funcAdapter.get(i));
        return items;
    }

    private static int indexOf(List<Func> funcs, int resId) {
        for (int i = 0; i < funcs.size(); i++) if (funcs.get(i).getResId() == resId) return i;
        return -1;
    }

    private Intent started(HomeActivity home, Func item) {
        home.onItemClick(item);
        return nextStartedActivity(home);
    }

    /** 反射取 ShadowActivity：直接引用该 shadow 会牵出编译期缺失的 android.hardware.fingerprint 类 */
    private static Intent nextStartedActivity(Activity activity) {
        try {
            Object shadow = Shadow.extract(activity);
            Intent intent = (Intent) shadow.getClass().getMethod("getNextStartedActivity").invoke(shadow);
            assertNotNull("点击功能按钮必须启动对应页面", intent);
            return intent;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
