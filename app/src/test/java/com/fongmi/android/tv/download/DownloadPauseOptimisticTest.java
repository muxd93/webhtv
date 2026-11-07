package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;

import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadTask;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.ResUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/**
 * 暂停/继续按钮：点击当次调用就必须返回新状态（列表行按同一内存对象重绑），
 * 落库放到后台；已完成任务不允许被改状态（反向用例）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class DownloadPauseOptimisticTest {

    private static final String SITE = "site";
    private static final String VOD = "vod";
    private static final String URL = "https://example.test/ep1.m3u8";

    private AppDatabase db;
    private DownloadTask task;

    @Before
    public void setUp() throws Exception {
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

    @Test
    public void pauseFlipsRowStateInSameCall() {
        String downloading = DownloadManager.stateText(task);
        DownloadManager.pause(task);

        // 不等后台落库，内存对象与列表行文案必须立刻变
        assertEquals(DownloadTask.STATE_PAUSED, task.getState());
        assertEquals(DownloadTask.STOP_USER, task.getStopReason());
        assertEquals(ResUtil.getString(R.string.download_state_paused), DownloadManager.stateText(task));
        org.junit.Assert.assertNotEquals("暂停后不能停在旧文案上", downloading, DownloadManager.stateText(task));
    }

    @Test
    public void resumeFlipsRowStateInSameCall() {
        task.setState(DownloadTask.STATE_PAUSED);
        task.setStopReason(DownloadTask.STOP_USER);
        DownloadManager.resume(task);

        assertEquals(DownloadTask.STATE_QUEUED, task.getState());
        assertEquals(DownloadTask.STOP_NONE, task.getStopReason());
        assertEquals(0, task.getRetryCount());
    }

    @Test
    public void completedTaskIsNotPausedOrResumed() {
        task.setState(DownloadTask.STATE_COMPLETED);
        DownloadManager.pause(task);
        assertEquals("已完成任务不能被改成暂停", DownloadTask.STATE_COMPLETED, task.getState());

        DownloadManager.resume(task);
        assertEquals("已完成任务不能被改成继续", DownloadTask.STATE_COMPLETED, task.getState());
    }
}
