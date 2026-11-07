package com.fongmi.android.tv.download;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadTask;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DownloadService extends Service {

    public static final String ACTION_SCHEDULE = "schedule";

    private static final long NOTIFY_INTERVAL_MS = 1000;
    private static final long SAVE_INTERVAL_MS = 1000;
    private static final long MIN_FREE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_AUTO_RETRY = 3;
    private static final long[] RETRY_BACKOFF_MS = {20_000L, 120_000L, 600_000L};

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    // 退避重试到期时间（仅内存：进程重启后由 schedule 的僵死复位统一处理）
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    // 调度独立成池：下载任务可能持续数分钟，避免网络变化等调度请求排在下载任务之后
    private final ExecutorService scheduler = Executors.newSingleThreadExecutor();
    private ConnectivityManager.NetworkCallback networkCallback;
    private PowerManager.WakeLock wakeLock;
    private boolean destroyed;
    private int completedCount;

    /** 单测注入点：替换解析阶段，让调度行为可在不起真实网络请求的前提下被观察（生产为 null） */
    static volatile Resolver resolver;

    interface Resolver {

        void resolve(String siteKey, String flag, String episodeUrl, DownloadResolver.Callback callback);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DownloadNotifier.createChannel();
        acquireWakeLock();
        registerNetwork();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(DownloadNotifier.progress(DownloadStore.running().size(), -1, 0, 0));
        scheduler.execute(this::schedule);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        for (Slot slot : slots.values()) slot.cancel();
        slots.clear();
        unregisterNetwork();
        scheduler.shutdownNow();
        executor.shutdownNow();
        DownloadNotifier.cancel(DownloadNotifier.ID);
        if (completedCount > 0) DownloadNotifier.notify(DownloadNotifier.ID + 1, DownloadNotifier.completed(getString(R.string.download_notify_done, completedCount)));
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_SCHEDULE);
        ContextCompat.startForegroundService(context, intent);
    }

    private void schedule() {
        if (destroyed) return;
        pruneSlots();
        // 进程被杀等场景留下的“运行中”僵死任务：无槽位即复位为排队
        for (DownloadTask task : DownloadStore.running()) {
            if (!slots.containsKey(task.getId())) {
                task.setState(DownloadTask.STATE_QUEUED);
                task.save();
            }
        }
        if (!DownloadPath.writable()) {
            SpiderDebug.log("download", "schedule blocked reason=storage-permission");
            failAll(R.string.download_permission, DownloadTask.STOP_PERMISSION);
            return;
        }
        // 权限已恢复：此前因权限失败的任务自动回到队列（用户无需逐条重试）
        for (DownloadTask task : DownloadStore.findFailedPermission()) {
            task.setState(DownloadTask.STATE_QUEUED);
            task.setStopReason(DownloadTask.STOP_NONE);
            task.setRetryCount(0);
            task.save();
            DownloadEvent.state(task.getId());
        }
        boolean unmetered = hasUnmeteredNetwork();
        if (DownloadManager.isWifiOnly() && !unmetered) {
            SpiderDebug.log("download", "schedule blocked reason=no-unmetered-network queued=%d", DownloadStore.queued().size());
            pauseForNetwork();
            return;
        }
        // 网络恢复：因断网自动暂停的任务自动续传（用户手动暂停的不动）
        if (unmetered) {
            for (DownloadTask task : DownloadStore.findAutoResumable()) {
                task.setState(DownloadTask.STATE_QUEUED);
                task.setStopReason(DownloadTask.STOP_NONE);
                task.save();
                DownloadEvent.state(task.getId());
            }
        }
        int max = DownloadManager.maxConcurrent();
        int started = 0;
        long now = System.currentTimeMillis();
        for (DownloadTask task : DownloadStore.queued()) {
            if (slots.size() >= max) break;
            // 退避重试未到期的任务本轮跳过
            Long retryAtTime = retryAt.get(task.getId());
            if (retryAtTime != null && retryAtTime > now) continue;
            retryAt.remove(task.getId());
            // 空间预检：可用空间过低时队首直接失败并给出清理提示，其余保持排队
            if (FileUtil.getAvailableStorageSpace(DownloadPath.root()) < MIN_FREE_BYTES) {
                SpiderDebug.log("download", "schedule blocked reason=low-storage free=%d", FileUtil.getAvailableStorageSpace(DownloadPath.root()));
                task.setState(DownloadTask.STATE_FAILED);
                task.setErrorMsg(getString(R.string.download_no_space));
                task.save();
                DownloadEvent.state(task.getId());
                break;
            }
            startTask(task);
            started++;
        }
        SpiderDebug.log("download", "schedule done slots=%d started=%d queuedLeft=%d", slots.size(), started, DownloadStore.queued().size());
        // 有退避中的排队任务时不能退出：定时唤醒与网络回调都挂在服务实例上，服务一停任务就永远停在排队
        boolean retryPending = hasPendingRetry(now);
        if (slots.isEmpty() && !retryPending) {
            releaseWakeLock();
            DownloadNotifier.cancel(DownloadNotifier.ID);
            stopSelf();
        } else if (slots.isEmpty()) {
            releaseWakeLock();
            DownloadNotifier.notify(DownloadNotifier.ID, DownloadNotifier.waiting(DownloadStore.queued().size()));
        } else {
            updateNotification();
        }
    }

    /** 是否有尚未到期的退避重试（对应任务仍在排队且未被删除） */
    private boolean hasPendingRetry(long now) {
        for (Map.Entry<String, Long> entry : retryAt.entrySet()) {
            if (entry.getValue() <= now) continue;
            DownloadTask task = DownloadStore.find(entry.getKey());
            if (task != null && task.getState() == DownloadTask.STATE_QUEUED) return true;
        }
        return false;
    }

    /** 统一剪枝：任务被暂停/删除/完成后，取消仍在运行的槽位（状态由 DownloadManager 先行落库） */
    private void pruneSlots() {
        if (slots.isEmpty()) return;
        for (Slot slot : new ArrayList<>(slots.values())) {
            DownloadTask task = DownloadStore.find(slot.task.getId());
            if (task == null || (!task.isRunning() && task.getState() != DownloadTask.STATE_QUEUED)) {
                slot.cancel();
                slots.remove(slot.task.getId());
            }
        }
    }

    private void startTask(DownloadTask task) {
        if (slots.containsKey(task.getId())) return;
        retryAt.remove(task.getId());
        acquireWakeLock();
        Slot slot = new Slot(task);
        slots.put(task.getId(), slot);
        task.setState(DownloadTask.STATE_RESOLVING);
        task.setErrorMsg("");
        task.save();
        DownloadEvent.state(task.getId());
        SpiderDebug.log("download", "task start id=%s flag=%s episode=%s", task.getId(), task.getVodFlag(), task.getEpisodeName());
        executor.execute(() -> runTask(slot));
        updateNotification();
    }

    private void runTask(Slot slot) {
        if (slot.canceled) return;
        DownloadTask task = slot.task;
        DownloadResolver.Callback callback = new DownloadResolver.Callback() {

            @Override
            public void onSuccess(String url, Map<String, String> headers) {
                if (slot.canceled) return;
                onResolved(slot, url, headers);
            }

            @Override
            public void onError(String msg) {
                fail(slot, msg);
            }
        };
        Resolver custom = resolver;
        if (custom != null) custom.resolve(task.getSiteKey(), task.getVodFlag(), task.getEpisodeUrl(), callback);
        else DownloadResolver.resolve(task.getSiteKey(), task.getVodFlag(), task.getEpisodeUrl(), callback);
    }

    private void onResolved(Slot slot, String url, Map<String, String> headers) {
        if (slot.canceled) return;
        // 协议相对 URL（部分 spider 返回 //host/path）：补 https:，否则 OkHttp 直接报不支持 scheme
        String mediaUrl = url != null && url.startsWith("//") ? "https:" + url : url;
        DownloadTask task = slot.task;
        SpiderDebug.log("download", "task resolved id=%s urlLen=%d headers=%d", task.getId(), url.length(), headers == null ? 0 : headers.size());
        task.setResolvedUrl(mediaUrl);
        task.setHeaders(App.gson().toJson(headers));
        File vodDir = DownloadPath.vod(task.getVodName());
        File episodeDir = DownloadPath.episode(vodDir, task.getId(), task.getVodFlag(), task.getEpisodeName());
        DownloadPath.ensureVodDir(vodDir);
        if (!episodeDir.isDirectory()) episodeDir.mkdirs();
        task.setDirPath(episodeDir.getAbsolutePath());
        task.setState(DownloadTask.STATE_DOWNLOADING);
        task.save();
        DownloadEvent.state(task.getId());
        // 探测与下载都是网络操作：解析回调经 App.post 回到主线程，必须转投工作线程执行
        // （此前在主线程探测/下载，触发 NetworkOnMainThreadException 导致全部任务失败）
        executor.execute(() -> dispatch(slot, mediaUrl, headers, episodeDir));
    }

    private void dispatch(Slot slot, String url, Map<String, String> headers, File episodeDir) {
        if (slot.canceled) return;
        DownloadTask task = slot.task;
        FileJob.Probe probe = FileJob.probe(url, headers);
        SpiderDebug.log("download", "task probe id=%s kind=%d reason=%s", task.getId(), probe.kind, probe.reason);
        if (probe.kind == FileJob.PROBE_HLS) {
            task.setKind(DownloadTask.KIND_HLS);
            task.save();
            HlsJob job = new HlsJob(url, headers, episodeDir, task.getSourceHash(), hlsListener(slot));
            slot.job = job;
            job.run();
        } else if (probe.kind == FileJob.PROBE_FILE) {
            task.setKind(DownloadTask.KIND_FILE);
            String fileName = "video." + DownloadPath.ext(url, probe.mimeType);
            task.setFileName(fileName);
            task.save();
            FileJob job = new FileJob(url, headers, episodeDir, fileName, task.getSourceHash(), fileListener(slot));
            slot.job = job;
            job.run();
        } else if (probe.kind == FileJob.PROBE_DASH) {
            task.setKind(DownloadTask.KIND_DASH);
            task.save();
            DashJob job = new DashJob(url, headers, episodeDir, task.getSourceHash(), dashListener(slot));
            slot.job = job;
            job.run();
        } else {
            // 精确原因：HTTP 状态码 / 网页内容 / JSON / 网络异常，而非一律“暂不支持”
            fail(slot, probe.reason == null ? "unsupported" : probe.reason);
        }
    }

    private HlsJob.Listener hlsListener(Slot slot) {
        return new HlsJob.Listener() {

            @Override
            public void onSourceHash(String hash) {
                persistSourceHash(slot, hash);
            }

            @Override
            public void onProgress(int doneSegments, int totalSegments, long doneBytes, long totalBytes, long speed) {
                publish(slot, doneSegments, totalSegments, doneBytes, totalBytes, speed);
            }

            @Override
            public void onCompleted(long totalBytes) {
                complete(slot, totalBytes);
            }

            @Override
            public void onError(String msg) {
                fail(slot, msg);
            }
        };
    }

    private FileJob.Listener fileListener(Slot slot) {
        return new FileJob.Listener() {

            @Override
            public void onSourceHash(String hash) {
                persistSourceHash(slot, hash);
            }

            @Override
            public void onProgress(long doneBytes, long totalBytes, long speed) {
                publish(slot, totalBytes > 0 && doneBytes >= totalBytes ? 1 : 0, 1, doneBytes, totalBytes, speed);
            }

            @Override
            public void onCompleted(long totalBytes) {
                complete(slot, totalBytes);
            }

            @Override
            public void onError(String msg) {
                fail(slot, msg);
            }
        };
    }

    private DashJob.Listener dashListener(Slot slot) {
        return new DashJob.Listener() {

            @Override
            public void onSourceHash(String hash) {
                persistSourceHash(slot, hash);
            }

            @Override
            public void onProgress(int doneSegments, int totalSegments, long doneBytes, long totalBytes, long speed) {
                publish(slot, doneSegments, totalSegments, doneBytes, totalBytes, speed);
            }

            @Override
            public void onCompleted(long totalBytes) {
                complete(slot, totalBytes);
            }

            @Override
            public void onError(String msg) {
                fail(slot, msg);
            }
        };
    }

    /** 来源指纹落库：下次续传/重试时比对，指纹变化即清目录重下（防分片错配） */
    private void persistSourceHash(Slot slot, String hash) {
        if (slot.canceled) return;
        DownloadTask task = alive(slot);
        if (task == null || hash == null || hash.isEmpty() || hash.equals(task.getSourceHash())) return;
        task.setSourceHash(hash);
        task.save();
    }

    /**
     * 复活守卫：任务可能已被用户删除/清理（行已删），此时 job 的滞后回调必须放弃，
     * 否则 insertOrUpdate 会把已删行重新插入（任务“复活”并继续下载）。
     */
    private DownloadTask alive(Slot slot) {
        if (slot.canceled) return null;
        DownloadTask task = DownloadStore.find(slot.task.getId());
        if (task == null) return null;
        slot.task = task;
        return task;
    }

    private void publish(Slot slot, int doneSegments, int totalSegments, long doneBytes, long totalBytes, long speed) {
        DownloadTask task = alive(slot);
        if (task == null) return;
        task.setDoneSegments(doneSegments);
        task.setTotalSegments(totalSegments);
        task.setDoneBytes(doneBytes);
        if (totalBytes > 0) task.setTotalBytes(totalBytes);
        long now = SystemClock.elapsedRealtime();
        if (now - slot.lastSaveTime >= SAVE_INTERVAL_MS) {
            slot.lastSaveTime = now;
            task.save();
        }
        DownloadEvent.progress(task.getId(), doneSegments, totalSegments, doneBytes, task.getTotalBytes(), speed);
        if (now - slot.lastNotifyTime >= NOTIFY_INTERVAL_MS) {
            slot.lastNotifyTime = now;
            updateNotification();
        }
    }

    private void complete(Slot slot, long totalBytes) {
        DownloadTask task = alive(slot);
        if (task == null) return;
        SpiderDebug.log("download", "task completed id=%s bytes=%d", task.getId(), totalBytes);
        task.setState(DownloadTask.STATE_COMPLETED);
        task.setFinishTime(System.currentTimeMillis());
        task.setDoneBytes(totalBytes > 0 ? totalBytes : task.getDoneBytes());
        if (totalBytes > 0) task.setTotalBytes(totalBytes);
        task.setErrorMsg("");
        task.save();
        DownloadStore.writeSidecar(task, new File(task.getDirPath()), task.getKind(), task.getKind() == DownloadTask.KIND_HLS ? "playlist.m3u8" : task.getKind() == DownloadTask.KIND_DASH ? "manifest.mpd" : task.getFileName(), task.getDoneBytes());
        File target = task.getKind() == DownloadTask.KIND_HLS ? DownloadPath.playlist(new File(task.getDirPath())) : task.getKind() == DownloadTask.KIND_DASH ? DownloadPath.manifest(new File(task.getDirPath())) : new File(task.getDirPath(), task.getFileName());
        DownloadPath.scanFile(this, target);
        finishSlot(slot);
        completedCount++;
        // 现在 complete 在下载工作线程执行，Toast 必须回主线程创建
        App.post(() -> Notify.show(getString(R.string.download_completed, task.getVodName() + " " + task.getEpisodeName())));
        RefreshEvent.download();
        DownloadManager.enforceCapacity();
        schedule();
    }

    private void fail(Slot slot, String msg) {
        DownloadTask task = alive(slot);
        if (task == null) return;
        if (task.isCompleted()) return;
        SpiderDebug.log("download", "task failed id=%s msg=%s retryCount=%d", task.getId(), msg, task.getRetryCount());
        // 先落定任务状态、再释放槽位：否则调度线程可能撞上「槽位已空、退避尚未记录」的窗口，判定无事可做并退出服务
        // 瞬时错误自动退避重试（最多 3 次）；DRM/格式不支持/直播等确定性错误直接失败
        if (isTransient(msg) && task.getRetryCount() < MAX_AUTO_RETRY) {
            int attempt = task.getRetryCount() + 1;
            task.setRetryCount(attempt);
            task.setState(DownloadTask.STATE_QUEUED);
            task.setStopReason(DownloadTask.STOP_NONE);
            task.setErrorMsg("");
            task.save();
            long delay = RETRY_BACKOFF_MS[Math.min(attempt, RETRY_BACKOFF_MS.length) - 1];
            retryAt.put(task.getId(), System.currentTimeMillis() + delay);
            DownloadEvent.state(task.getId());
            // 退避期间可能没有槽位也没人再触发调度，必须由服务自己在到期时唤醒一次
            scheduleWake(delay);
            finishSlot(slot);
            schedule();
            return;
        }
        task.setState(DownloadTask.STATE_FAILED);
        task.setErrorMsg(errorText(msg));
        task.save();
        DownloadEvent.state(task.getId());
        finishSlot(slot);
        schedule();
    }

    /** 瞬时错误：网络抖动/超时/5xx/401-403（地址过期，重新解析即可换新）/截断——值得自动重试 */
    private boolean isTransient(String msg) {
        if (msg == null || msg.isEmpty()) return true;
        if (msg.matches("HTTP 5\\d{2}.*")) return true;
        if (msg.matches("HTTP 40[13].*")) return true;
        if (msg.contains("timeout") || msg.contains("Timeout") || msg.contains("timed out")) return true;
        if (msg.contains("Connection reset") || msg.contains("Connection closed") || msg.contains("unexpected end of stream") || msg.contains("Unable to resolve host") || msg.contains("failed to connect")) return true;
        if (msg.startsWith("java.net.") || msg.startsWith("java.io.")) return true;
        return "length".equals(msg) || "empty body".equals(msg);
    }

    private void failAll(int resId, int stopReason) {
        List<DownloadTask> tasks = new ArrayList<>(DownloadStore.queued());
        tasks.addAll(DownloadStore.running());
        for (DownloadTask task : tasks) {
            task.setState(DownloadTask.STATE_FAILED);
            task.setStopReason(stopReason);
            task.setErrorMsg(getString(resId));
            task.save();
            DownloadEvent.state(task.getId());
        }
        for (Slot slot : slots.values()) slot.cancel();
        slots.clear();
        if (slots.isEmpty()) releaseWakeLock();
        DownloadNotifier.cancel(DownloadNotifier.ID);
        stopSelf();
    }

    private void finishSlot(Slot slot) {
        slots.remove(slot.task.getId());
        DownloadEvent.state(slot.task.getId());
        if (slots.isEmpty()) releaseWakeLock();
        updateNotification();
    }

    /**
     * 无可用（非计量）网络：运行中与排队中的任务统一转入「等待网络」可见态。
     * 排队任务若留在 QUEUED，既不会显示为等待网络，也不会被 findAutoResumable 自动恢复，
     * 用户看到的就是「一直排队中、一动不动」。两者都记 STOP_NETWORK，网络恢复后由调度自动继续。
     */
    private void pauseForNetwork() {
        for (Slot slot : slots.values()) slot.cancel();
        slots.clear();
        int paused = pauseTasks(DownloadStore.running());
        paused += pauseTasks(DownloadStore.queued());
        releaseWakeLock();
        // 有任务等网络时必须驻留：NetworkCallback 只在服务存活时有效，服务一停网络恢复也没人唤醒调度
        if (paused == 0) {
            DownloadNotifier.cancel(DownloadNotifier.ID);
            stopSelf();
            return;
        }
        DownloadNotifier.notify(DownloadNotifier.ID, DownloadNotifier.waiting(paused));
    }

    private int pauseTasks(List<DownloadTask> tasks) {
        for (DownloadTask task : tasks) {
            task.setState(DownloadTask.STATE_PAUSED);
            task.setStopReason(DownloadTask.STOP_NETWORK);
            task.setErrorMsg("");
            task.save();
            DownloadEvent.state(task.getId());
        }
        return tasks.size();
    }

    /** 退避到期自我唤醒：调度只在本服务存活时有效，所以到期后必须自己把它拉起来一次 */
    private void scheduleWake(long delayMs) {
        Task.schedule(() -> {
            if (destroyed) return;
            scheduler.execute(this::schedule);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void updateNotification() {
        if (destroyed || slots.isEmpty()) return;
        long doneBytes = 0;
        long totalBytes = 0;
        int segmentPercent = -1;
        for (Slot slot : slots.values()) {
            doneBytes += slot.task.getDoneBytes();
            totalBytes += slot.task.getTotalBytes();
            if (slot.task.getTotalSegments() > 0) {
                int percent = (int) (slot.task.getDoneSegments() * 100.0 / slot.task.getTotalSegments());
                if (percent > segmentPercent) segmentPercent = percent;
            }
        }
        int percent = totalBytes > 0 ? (int) (doneBytes * 100 / totalBytes) : segmentPercent;
        DownloadNotifier.notify(DownloadNotifier.ID, DownloadNotifier.progress(slots.size(), percent, doneBytes, totalBytes));
    }

    /**
     * 仅 Wi-Fi 语义：任一 WiFi/以太网网络可用即视为非计量。
     * 不能只看默认活动网络——国产 ROM 常开“网络加速”使 WLAN 与移动数据双通道并存，
     * 默认活动网络可能是移动网络，仅认 TRANSPORT_WIFI 会误判为计量网络导致永远排队。
     */
    private boolean hasUnmeteredNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities == null) continue;
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void registerNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {

                @Override
                public void onAvailable(Network network) {
                    scheduler.execute(DownloadService.this::schedule);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    scheduler.execute(DownloadService.this::schedule);
                }

                @Override
                public void onLost(Network network) {
                    scheduler.execute(DownloadService.this::schedule);
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
        } catch (Exception ignored) {
        }
    }

    private void unregisterNetwork() {
        try {
            if (networkCallback == null) return;
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {
        try {
            // 复用同一个 WakeLock：每次 newWakeLock 会覆盖字段，并发启动多任务时前一个锁被泄漏且永释
            //（日志 WakeLock finalized while still held 即此路径）
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webhtv:download");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(DownloadNotifier.ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(DownloadNotifier.ID, notification);
            }
        } catch (Exception e) {
            startForeground(DownloadNotifier.ID, notification);
        }
    }

    private String errorText(String msg) {
        if ("drm".equals(msg)) return getString(R.string.download_drm);
        if ("dash".equals(msg)) return getString(R.string.download_dash);
        if ("live".equals(msg)) return getString(R.string.download_live);
        if ("length".equals(msg)) return getString(R.string.download_truncated);
        if ("protocol".equals(msg)) return getString(R.string.download_protocol);
        if ("smooth".equals(msg)) return getString(R.string.download_smooth);
        if ("html".equals(msg)) return getString(R.string.download_html);
        if ("json".equals(msg)) return getString(R.string.download_json);
        if (msg != null && (msg.contains("ENOSPC") || msg.contains("No space left"))) return getString(R.string.download_no_space);
        if ("unsupported".equals(msg) || "byterange".equals(msg) || "key-rotation".equals(msg) || "multi-map".equals(msg) || "iv-rotation".equals(msg) || "multi-period".equals(msg) || "no-video".equals(msg)) return getString(R.string.download_unsupported);
        return msg == null || msg.isEmpty() ? getString(R.string.download_failed_unknown) : msg;
    }

    private static class Slot {

        private DownloadTask task;
        private volatile boolean canceled;
        private volatile Object job;
        private volatile long lastSaveTime;
        private volatile long lastNotifyTime;

        private Slot(DownloadTask task) {
            this.task = task;
        }

        private void cancel() {
            canceled = true;
            if (job instanceof HlsJob hlsJob) hlsJob.cancel();
            if (job instanceof FileJob fileJob) fileJob.cancel();
            if (job instanceof DownloadResolver resolver) resolver.cancel();
        }
    }
}
