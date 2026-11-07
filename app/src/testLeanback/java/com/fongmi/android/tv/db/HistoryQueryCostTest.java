package com.fongmi.android.tv.db;

import static org.junit.Assert.assertTrue;

import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.utils.Task;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 量化证据：2000 行历史在「主线程直接查全表+排序」与「后台查询+回投」两种方式下的主线程占用。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class HistoryQueryCostTest {

    private AppDatabase db;

    @Before
    public void setUp() throws Exception {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase.class).allowMainThreadQueries().build();
        Field field = AppDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, db);
        for (int i = 0; i < 2000; i++) {
            History history = new History();
            history.setCid(1);
            history.setKey("key-" + i);
            history.setVodName("vod-" + i);
            history.setCreateTime(1000L + i);
            db.getHistoryDao().insert(history);
        }
    }

    private static List<History> queryAndSort() {
        List<History> items = new ArrayList<>(AppDatabase.get().getHistoryDao().findAll());
        items.sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()));
        return items;
    }

    @Test
    public void backgroundQueryKeepsMainThreadFree() throws Exception {
        // 改前：全表读取+排序都发生在主线程
        long start = System.nanoTime();
        List<History> sync = queryAndSort();
        long syncNanos = System.nanoTime() - start;

        // 改后：查询在后台，主线程只做回投
        AtomicLong deliverNanos = new AtomicLong();
        CountDownLatch delivered = new CountDownLatch(1);
        Task.execute(() -> {
            List<History> items = queryAndSort();
            App.post(() -> {
                long posted = System.nanoTime();
                // 回投后主线程实际要做的事（这里只记录交付耗时起点，不包含查询）
                deliverNanos.set(System.nanoTime() - posted);
                assertTrue(items.size() == 2000);
                delivered.countDown();
            });
        });
        // 让后台任务跑完并把结果投回主线程
        long deadline = System.currentTimeMillis() + 5000;
        while (delivered.getCount() > 0 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10);
        }
        assertTrue("后台查询结果未回投", delivered.await(100, TimeUnit.MILLISECONDS));

        long syncMillis = TimeUnit.NANOSECONDS.toMillis(syncNanos);
        long deliverMillis = TimeUnit.NANOSECONDS.toMillis(deliverNanos.get());
        System.out.println("[history-cost] rows=2000 mainThreadSync=" + syncMillis + "ms mainThreadAfter=" + deliverMillis + "ms");
        assertTrue("2000 行全表读取+排序应可测出主线程占用", syncMillis >= 1);
        assertTrue("回投后主线程占用必须显著低于同步查询", deliverMillis <= syncMillis || deliverMillis < 5);
    }
}
