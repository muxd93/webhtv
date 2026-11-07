package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.DownloadTask;
import com.fongmi.android.tv.db.AppDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 暂停/继续的意图必须先落库，再启动下载调度。
 * 调度侧（DownloadService.onStartCommand → schedule → pruneSlots）只读数据库决定是否取消槽位，
 * 所以「服务启动那一刻数据库里是什么状态」就是用户点暂停/继续能否生效的全部依据：
 * 若那一刻仍是 DOWNLOADING，正在跑的槽位不会被取消，暂停会被下一次进度回调/完成覆盖；
 * 若那一刻仍是 PAUSED，已暂停任务不会被重新拉起。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class DownloadPauseIntentTest {

    private static final String SITE = "site";
    private static final String VOD = "vod";
    private static final String URL = "https://example.test/ep1.m3u8";

    private AppDatabase db;
    private DownloadTask task;
    private Runnable originalStarter;

    @Before
    public void setUp() throws Exception {
        originalStarter = DownloadManager.serviceStarter;
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase.class).allowMainThreadQueries().build();
        Field field = AppDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, db);
        task = new DownloadTask();
        task.setId(DownloadTask.id(SITE, VOD, "flag", "第1集", URL));
        task.setSiteKey(SITE);
        task.setVodId(VOD);
        task.setVodFlag("flag");
        task.setEpisodeName("第1集");
        task.setEpisodeUrl(URL);
        task.setState(DownloadTask.STATE_DOWNLOADING);
        task.save();
    }

    @After
    public void tearDown() {
        DownloadManager.serviceStarter = originalStarter;
        db.close();
    }

    /** 调度启动时必须读到 PAUSED，否则 pruneSlots 不会取消正在跑的槽位 */
    @Test
    public void pauseIntentIsPersistedBeforeSchedulerStarts() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger stateAtStart = new AtomicInteger(-1);
        DownloadManager.serviceStarter = () -> {
            DownloadTask fresh = DownloadStore.find(task.getId());
            stateAtStart.set(fresh == null ? -1 : fresh.getState());
            started.countDown();
        };

        DownloadManager.pause(task);

        assertTrue("暂停后必须启动调度，否则没有下一步动作", started.await(5, TimeUnit.SECONDS));
        assertEquals("调度启动时数据库里必须已是 PAUSED，否则正在跑的槽位不会被取消",
                DownloadTask.STATE_PAUSED, stateAtStart.get());
        assertEquals(DownloadTask.STATE_PAUSED, DownloadStore.find(task.getId()).getState());
    }

    /** 调度启动时必须读到 QUEUED，否则「继续」不会把任务重新排上 */
    @Test
    public void resumeIntentIsPersistedBeforeSchedulerStarts() throws Exception {
        task.setState(DownloadTask.STATE_PAUSED);
        task.setStopReason(DownloadTask.STOP_USER);
        task.save();

        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger stateAtStart = new AtomicInteger(-1);
        DownloadManager.serviceStarter = () -> {
            DownloadTask fresh = DownloadStore.find(task.getId());
            stateAtStart.set(fresh == null ? -1 : fresh.getState());
            started.countDown();
        };

        DownloadManager.resume(task);

        assertTrue("继续后必须启动调度，否则没有下一步动作", started.await(5, TimeUnit.SECONDS));
        assertEquals("调度启动时数据库里必须已是 QUEUED，否则已暂停任务不会被重新拉起",
                DownloadTask.STATE_QUEUED, stateAtStart.get());
        assertEquals(DownloadTask.STATE_QUEUED, DownloadStore.find(task.getId()).getState());
    }
}
