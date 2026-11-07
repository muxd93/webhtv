package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.DialogHistoryBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.ui.activity.HomeActivity;

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

/**
 * 打开源列表时的那次全表查询必须在后台线程。
 * 这里刻意不给 Room 开 allowMainThreadQueries：旧实现同步构建，主线程一查库就抛
 * IllegalStateException，红点直接落在对话框创建期间；改成 Task.execute 后同一路径不再有主线程 DB 调用，
 * 且后台回填后列表必须真的填上（不能停在占位行）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class ConfigListAsyncLoadTest {

    private static final String URL = "https://example.test/vod.json";
    private static final String NAME = "测试源";

    private AppDatabase db;

    @Before
    public void setUp() throws Exception {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase.class).build();
        Field field = AppDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, db);
        seed(com.fongmi.android.tv.bean.Config.create(0, URL, NAME));
    }

    @After
    public void tearDown() throws Exception {
        Field field = AppDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
        db.close();
    }

    /** 一条单源应被整理成「单源」分区行 + 该源行 */
    @Test
    public void openingConfigListHidesMainThreadQuery() throws Exception {
        HomeActivity host = Robolectric.buildActivity(HomeActivity.class).create().start().get();
        HistoryDialog dialog = HistoryDialog.create().vod();
        dialog.show(host, null);
        ShadowLooper.shadowMainLooper().idle();

        RecyclerView.Adapter<?> adapter = adapter(dialog);
        assertNotNull("对话框必须已经显示并挂上适配器", adapter);
        awaitRows(adapter, 2);
    }

    /** 写入也要避开主线程：同一个 Room 实例对主线程读写一视同仁地拦截 */
    private void seed(com.fongmi.android.tv.bean.Config config) throws Exception {
        Thread thread = new Thread(() -> db.getConfigDao().insert(config));
        thread.start();
        thread.join();
    }

    private static void awaitRows(RecyclerView.Adapter<?> adapter, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && adapter.getItemCount() != expected) {
            Thread.sleep(5);
            ShadowLooper.shadowMainLooper().idle();
        }
        assertEquals("后台构建必须回填到列表，不能停在占位行", expected, adapter.getItemCount());
    }

    private static RecyclerView.Adapter<?> adapter(HistoryDialog dialog) throws Exception {
        Field field = HistoryDialog.class.getDeclaredField("binding");
        field.setAccessible(true);
        return ((DialogHistoryBinding) field.get(dialog)).recycler.getAdapter();
    }
}
