package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
 * 详情页缓存按钮文案：进度事件风暴只查一次库；状态切换必须立刻反映新文案。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class DownloadButtonTextCacheTest {

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
        DownloadManager.resetButtonTextForTest();
        task = new DownloadTask();
        task.setId(DownloadTask.id(SITE, VOD, "flag", "第1集", URL));
        task.setSiteKey(SITE);
        task.setVodId(VOD);
        task.setVodFlag("flag");
        task.setEpisodeName("第1集");
        task.setEpisodeUrl(URL);
        task.setState(DownloadTask.STATE_PAUSED);
        task.save();
    }

    @Test
    public void burstOfProgressEventsQueriesOnce() {
        for (int i = 0; i < 200; i++) DownloadManager.buttonTextCached(SITE, VOD, URL);
        assertEquals("200 条进度事件只允许查库 1 次", 1, DownloadManager.buttonTextQueries());
    }

    @Test
    public void stateChangeIsReflectedImmediately() {
        String paused = ResUtil.getString(R.string.download_state_paused);
        assertEquals(paused, DownloadManager.buttonTextCached(SITE, VOD, URL));
        assertEquals("TTL 内命中缓存", 1, DownloadManager.buttonTextQueries());

        // 模拟暂停 -> 完成：服务端会先发 DownloadEvent.state，界面据此失效缓存
        task.setState(DownloadTask.STATE_COMPLETED);
        task.save();
        DownloadManager.invalidateButtonText();
        String completed = DownloadManager.buttonTextCached(SITE, VOD, URL);

        assertEquals(ResUtil.getString(R.string.download_state_completed), completed);
        assertNotEquals("状态切换后不能停在旧文案上", paused, completed);
        assertEquals("失效后只补查一次", 2, DownloadManager.buttonTextQueries());
    }
}
