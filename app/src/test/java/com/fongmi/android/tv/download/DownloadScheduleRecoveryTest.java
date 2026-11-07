package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Environment;

import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadTask;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.ResUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 调度不能把任务停在「排队中」一动不动：
 * 1) 瞬时失败进入退避后，到期必须由服务自己唤醒调度——没人会再触发它，服务还会因槽位空而退出；
 * 2) 「仅 Wi-Fi 下载」且当前没有可用非计量网络时，排队任务必须进入用户可见的「等待网络」态，
 *    网络恢复后由 NetworkCallback 触发调度自动继续，而不是继续显示「排队中」。
 *
 * 影子对象统一用 {@link Shadow#extract(Object)} 取成 Object 再反射调用：部分影子类的父类引用了
 * 当前 leanback 变体编译类路径里没有的类型，直接引用影子类型会编译失败。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class DownloadScheduleRecoveryTest {

    private static final String SITE = "site";
    private static final String VOD = "vod";
    private static final String URL = "https://example.test/ep1.m3u8";

    private AppDatabase db;
    private DownloadTask task;
    private ServiceController<DownloadService> controller;
    private DownloadService service;
    private boolean originalWifiOnly;
    private long[] originalBackoff;

    @Before
    public void setUp() throws Exception {
        DownloadService.resolver = null;
        originalWifiOnly = DownloadManager.isWifiOnly();
        DownloadManager.setWifiOnly(true);
        originalBackoff = backoff().clone();
        // 缩短首次退避，验证「到期后自己回来」而不必真的等 20 秒
        backoff()[0] = 300L;
        // 下载调度在进入状态判断前会先校验下载目录可写，先补上存储权限与挂载状态
        Object appShadow = Shadow.extract(RuntimeEnvironment.getApplication());
        appShadow.getClass().getMethod("grantPermissions", String[].class).invoke(appShadow, (Object) new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
        Class.forName("org.robolectric.shadows.ShadowEnvironment").getMethod("setExternalStorageState", String.class).invoke(null, Environment.MEDIA_MOUNTED);
        DownloadPath.root().mkdirs();
        // 调度会做空间预检，Robolectric 下 StatFs 默认可用空间为 0，先给下载目录登记一份充足空间
        Class.forName("org.robolectric.shadows.ShadowStatFs").getMethod("registerStats", String.class, int.class, int.class, int.class)
                .invoke(null, DownloadPath.root().getAbsolutePath(), 1 << 20, 1 << 19, 1 << 19);
        assertTrue("前提：下载目录可写 mounted=" + DownloadPath.mounted() + " permission=" + DownloadPath.hasPermission(), DownloadPath.writable());
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
        task.setState(DownloadTask.STATE_QUEUED);
        task.save();
        controller = Robolectric.buildService(DownloadService.class).create();
        service = controller.get();
    }

    @After
    public void tearDown() throws Exception {
        DownloadService.resolver = null;
        DownloadManager.setWifiOnly(originalWifiOnly);
        System.arraycopy(originalBackoff, 0, backoff(), 0, originalBackoff.length);
        if (controller != null) controller.destroy();
        if (db != null) db.close();
    }

    /** 退避到期后任务自己回到下载流程，且退避期间服务不许退出（退出等于把定时唤醒一起带走） */
    @Test
    public void transientFailureReturnsToDownloadingAfterBackoff() throws Exception {
        networkAvailable(true);
        AtomicInteger calls = new AtomicInteger();
        DownloadService.resolver = (site, flag, url, callback) -> {
            // 第一次瞬时失败触发退避；第二次停在「解析中」，用来证明任务确实被重新拉起
            if (calls.incrementAndGet() == 1) callback.onError("timeout");
        };

        controller.startCommand(0, 0);

        assertTrue("任务应被调度并开始解析", awaitCalls(calls, 1, 3000));
        DownloadTask retrying = awaitTask(item -> item.getState() == DownloadTask.STATE_QUEUED && item.getRetryCount() == 1, 3000);
        assertNotNull("瞬时失败应转入退避排队", retrying);
        assertFalse("退避未到期时服务不能退出，否则定时唤醒不会发生", stoppedBySelf());

        assertTrue("退避到期后任务必须自己回到下载流程，无需用户再点一次", awaitCalls(calls, 2, 3000));
        DownloadTask resumed = awaitTask(item -> item.getState() == DownloadTask.STATE_RESOLVING, 3000);
        assertEquals("退避到期后任务应重新进入下载流程", DownloadTask.STATE_RESOLVING, resumed.getState());
        assertEquals(DownloadTask.STOP_NONE, resumed.getStopReason());
    }

    /** 开着「仅 Wi-Fi 下载」又只有移动网络时，排队任务要变成可见的等待网络态，并在网络恢复后自动继续 */
    @Test
    public void queuedTasksWaitForNetworkAndResumeWhenItReturns() throws Exception {
        networkAvailable(false);
        AtomicInteger calls = new AtomicInteger();
        DownloadService.resolver = (site, flag, url, callback) -> calls.incrementAndGet();

        controller.startCommand(0, 0);

        DownloadTask waiting = awaitTask(item -> item.getState() == DownloadTask.STATE_PAUSED, 3000);
        assertEquals("排队任务应转入暂停等待网络，而不是继续显示排队中", DownloadTask.STATE_PAUSED, waiting.getState());
        assertEquals(DownloadTask.STOP_NETWORK, waiting.getStopReason());
        assertEquals("等待网络必须对用户可见", ResUtil.getString(R.string.download_state_wait_network), DownloadManager.stateText(waiting));
        assertEquals("没有非计量网络时不应开始下载", 0, calls.get());
        assertFalse("等待网络期间服务必须存活，否则网络回调不会触发", stoppedBySelf());

        networkAvailable(true);
        dispatchNetworkAvailable();

        assertTrue("网络恢复后应自动继续下载", awaitCalls(calls, 1, 3000));
        DownloadTask resumed = awaitTask(item -> item.getState() == DownloadTask.STATE_RESOLVING, 3000);
        assertEquals(DownloadTask.STATE_RESOLVING, resumed.getState());
        assertEquals("自动恢复后不应再带等待网络标记", DownloadTask.STOP_NONE, resumed.getStopReason());
    }

    /** 模拟系统的网络能力：有非计量网络（Wi-Fi）或只有计量网络（移动数据） */
    private void networkAvailable(boolean available) throws Exception {
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        Object capabilitiesShadow = Shadow.extract(capabilities);
        capabilitiesShadow.getClass().getMethod("addCapability", int.class).invoke(capabilitiesShadow, NetworkCapabilities.NET_CAPABILITY_INTERNET);
        capabilitiesShadow.getClass().getMethod("addTransportType", int.class).invoke(capabilitiesShadow, available ? NetworkCapabilities.TRANSPORT_WIFI : NetworkCapabilities.TRANSPORT_CELLULAR);
        Object managerShadow = Shadow.extract(connectivity());
        Method setCapabilities = managerShadow.getClass().getMethod("setNetworkCapabilities", Network.class, NetworkCapabilities.class);
        for (Network network : connectivity().getAllNetworks()) setCapabilities.invoke(managerShadow, network, capabilities);
    }

    /** 模拟系统在服务注册的网络回调上通知「网络已可用」 */
    private void dispatchNetworkAvailable() throws Exception {
        Object managerShadow = Shadow.extract(connectivity());
        @SuppressWarnings("unchecked")
        Set<ConnectivityManager.NetworkCallback> callbacks = (Set<ConnectivityManager.NetworkCallback>) managerShadow.getClass().getMethod("getNetworkCallbacks").invoke(managerShadow);
        Network network = connectivity().getAllNetworks()[0];
        for (ConnectivityManager.NetworkCallback callback : callbacks) callback.onAvailable(network);
    }

    private static ConnectivityManager connectivity() {
        return (ConnectivityManager) RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private boolean stoppedBySelf() throws Exception {
        Object shadow = Shadow.extract(service);
        return Boolean.TRUE.equals(shadow.getClass().getMethod("isStoppedBySelf").invoke(shadow));
    }

    private DownloadTask awaitTask(Predicate<DownloadTask> condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        DownloadTask fresh = null;
        while (System.currentTimeMillis() < deadline) {
            fresh = DownloadStore.find(task.getId());
            if (fresh != null && condition.test(fresh)) return fresh;
            Thread.sleep(20);
        }
        return fresh;
    }

    private static boolean awaitCalls(AtomicInteger calls, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (calls.get() >= expected) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private static long[] backoff() throws Exception {
        Field field = DownloadService.class.getDeclaredField("RETRY_BACKOFF_MS");
        field.setAccessible(true);
        return (long[]) field.get(null);
    }
}
