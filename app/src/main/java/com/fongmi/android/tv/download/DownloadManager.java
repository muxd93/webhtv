package com.fongmi.android.tv.download;

import android.app.Activity;
import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.DownloadTask;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadManager {

    private static final String KEY_WIFI_ONLY = "download_wifi_only";
    private static final String KEY_CONCURRENT = "download_concurrent";
    private static final String KEY_CAPACITY = "download_capacity";
    private static final long DEFAULT_CAPACITY = 10L * 1024 * 1024 * 1024;

    private DownloadManager() {
    }

    public static void init() {
        DownloadStore.reconcile();
    }

    /** 进程重启后恢复：存在未完成任务才拉起服务（避免无任务时前台服务闪烁） */
    public static void resumeIfNeeded(Context context) {
        Task.execute(() -> {
            if (AppDatabase.get().getDownloadDao().activeCount() > 0) DownloadService.start(context);
        });
    }

    public static boolean isWifiOnly() {
        return Prefers.getBoolean(KEY_WIFI_ONLY, true);
    }

    public static void setWifiOnly(boolean value) {
        Prefers.put(KEY_WIFI_ONLY, value);
    }

    public static int maxConcurrent() {
        return Math.max(1, Math.min(3, Prefers.getInt(KEY_CONCURRENT, 2)));
    }

    public static void setMaxConcurrent(int value) {
        Prefers.put(KEY_CONCURRENT, Math.max(1, Math.min(3, value)));
    }

    /** 容量上限（字节），0 = 不限 */
    public static long capacityBytes() {
        return Prefers.getLong(KEY_CAPACITY, DEFAULT_CAPACITY);
    }

    public static void setCapacityBytes(long value) {
        Prefers.put(KEY_CAPACITY, Math.max(0, value));
        enforceCapacity();
    }

    /**
     * 容量淘汰：超限时按“最久未播放优先”删除（未播放过的按完成时间），
     * 正在下载与最近播放的任务天然排在最后，不会被误删。
     */
    public static void enforceCapacity() {
        Task.execute(() -> {
            long cap = capacityBytes();
            if (cap <= 0) return;
            List<DownloadTask> completed = DownloadStore.completed();
            long total = DownloadStore.totalBytes();
            if (total <= cap) return;
            List<DownloadTask> removed = new ArrayList<>();
            for (DownloadTask task : completed) {
                if (total <= cap) break;
                total -= Math.max(0, task.getDoneBytes());
                DownloadStore.delete(task);
                removed.add(task);
            }
            if (removed.isEmpty()) return;
            RefreshEvent.download();
            App.post(() -> Notify.show(ResUtil.getString(R.string.download_evicted, removed.size())));
        });
    }

    public static void clearAll() {
        Task.execute(() -> {
            DownloadStore.deleteAll();
            RefreshEvent.download();
            App.post(() -> Notify.show(R.string.download_cleared));
        });
    }

    /** 批量入队：已存在且未失败的跳过；失败的原地重置为排队（视为重试） */
    public static int add(String siteKey, String vodId, String vodName, String vodPic, String flag, List<Episode> episodes) {
        int added = 0;
        for (Episode episode : episodes) {
            String id = DownloadTask.id(siteKey, vodId, flag, episode.getName(), episode.getUrl());
            DownloadTask task = DownloadStore.find(id);
            if (task == null) {
                task = new DownloadTask();
                task.setId(id);
                task.setCid(VodConfig.getCid());
                task.setSiteKey(siteKey);
                task.setVodId(vodId);
                task.setVodName(vodName);
                task.setVodPic(vodPic);
                task.setVodFlag(flag);
                task.setEpisodeName(episode.getName());
                task.setEpisodeUrl(episode.getUrl());
                task.setCreateTime(System.currentTimeMillis());
                task.setState(DownloadTask.STATE_QUEUED);
                task.save();
                added++;
            } else if (task.isFailed()) {
                task.setState(DownloadTask.STATE_QUEUED);
                task.setStopReason(DownloadTask.STOP_NONE);
                task.setRetryCount(0);
                task.setErrorMsg("");
                task.save();
                added++;
            }
        }
        if (added > 0) {
            RefreshEvent.download();
            DownloadService.start(App.get());
        }
        return added;
    }

    public static void pause(String id) {
        DownloadTask task = DownloadStore.find(id);
        if (task == null || task.isCompleted()) return;
        task.setState(DownloadTask.STATE_PAUSED);
        task.setStopReason(DownloadTask.STOP_USER);
        task.setErrorMsg("");
        task.save();
        DownloadEvent.state(id);
        DownloadService.start(App.get());
    }

    public static void resume(String id) {
        DownloadTask task = DownloadStore.find(id);
        if (task == null || !task.canResume()) return;
        task.setState(DownloadTask.STATE_QUEUED);
        task.setStopReason(DownloadTask.STOP_NONE);
        task.setRetryCount(0);
        task.setErrorMsg("");
        task.save();
        DownloadEvent.state(id);
        DownloadService.start(App.get());
    }

    public static void retry(String id) {
        resume(id);
    }

    public static void delete(String id) {
        DownloadTask task = DownloadStore.find(id);
        if (task == null) return;
        DownloadStore.delete(task);
        DownloadEvent.state(id);
        RefreshEvent.download();
        DownloadService.start(App.get());
    }

    public static List<DownloadTask> tasks() {
        return DownloadStore.all();
    }

    public static DownloadTask find(String id) {
        return DownloadStore.find(id);
    }

    public static String stateText(DownloadTask task) {
        switch (task.getState()) {
            case DownloadTask.STATE_QUEUED:
                return ResUtil.getString(R.string.download_state_queued);
            case DownloadTask.STATE_RESOLVING:
                return ResUtil.getString(R.string.download_state_resolving);
            case DownloadTask.STATE_DOWNLOADING:
                return ResUtil.getString(R.string.download_state_downloading);
            case DownloadTask.STATE_PAUSED:
                return task.getStopReason() == DownloadTask.STOP_NETWORK
                        ? ResUtil.getString(R.string.download_state_wait_network)
                        : ResUtil.getString(R.string.download_state_paused);
            case DownloadTask.STATE_COMPLETED:
                return ResUtil.getString(R.string.download_state_completed);
            case DownloadTask.STATE_FAILED:
                return ResUtil.getString(R.string.download_state_failed);
            default:
                return "";
        }
    }

    public static int progress(DownloadTask task) {
        if (task.isCompleted()) return 100;
        if (task.getTotalSegments() > 0) return (int) (task.getDoneSegments() * 100.0 / task.getTotalSegments());
        if (task.getTotalBytes() > 0) return (int) (task.getDoneBytes() * 100.0 / task.getTotalBytes());
        return 0;
    }

    public static void toastAdded(int count) {
        Notify.show(ResUtil.getString(R.string.download_added, count));
    }

    /** 详情页缓存按钮文案：null=无相关任务保持默认“缓存”；否则给出该集当前缓存状态 */
    public static String buttonText(String siteKey, String vodId, String episodeUrl) {
        List<DownloadTask> tasks = DownloadStore.findByEpisodeUrl(siteKey, vodId, episodeUrl);
        if (tasks.isEmpty()) return null;
        DownloadTask task = null;
        for (DownloadTask item : tasks) {
            if (item.isCompleted()) {
                task = item;
                break;
            }
        }
        if (task == null) task = tasks.get(0);
        if (task.isCompleted()) return ResUtil.getString(R.string.download_state_completed);
        if (task.isRunning()) return ResUtil.getString(R.string.download_button_progress, progress(task));
        if (task.getState() == DownloadTask.STATE_QUEUED) return ResUtil.getString(R.string.download_state_queued);
        if (task.getState() == DownloadTask.STATE_PAUSED) return ResUtil.getString(R.string.download_state_paused);
        if (task.isFailed()) return ResUtil.getString(R.string.download_retry);
        return null;
    }

    /**
     * 播放命中：已完成任意任务优先；否则下载中的 HLS/DASH 已有完整分片可边下边播
     * （HLS 本地 playlist 无 ENDLIST，播放器按 live 轮询追加；DASH 分段轨的本地 mpd
     * 用 SegmentTimeline 只写已完成分片。单文件 DASH 未下完时 doneSegments=0 自然排除）。
     */
    public static DownloadTask findPlayable(String siteKey, String vodId, String episodeUrl) {
        List<DownloadTask> tasks = DownloadStore.findByEpisodeUrl(siteKey, vodId, episodeUrl);
        if (tasks.isEmpty()) return null;
        for (DownloadTask task : tasks) if (task.isCompleted() && localFile(task) != null) return task;
        for (DownloadTask task : tasks) {
            if (task.getState() == DownloadTask.STATE_DOWNLOADING
                    && (task.getKind() == DownloadTask.KIND_HLS || task.getKind() == DownloadTask.KIND_DASH)
                    && task.getDoneSegments() > 0
                    && localFile(task) != null) return task;
        }
        return null;
    }

    public static String localUrl(DownloadTask task) {
        File file = localFile(task);
        return file == null ? "" : android.net.Uri.fromFile(file).toString();
    }

    private static File localFile(DownloadTask task) {
        if (task.getDirPath().isEmpty()) return null;
        File file = task.getKind() == DownloadTask.KIND_HLS
                ? DownloadPath.playlist(new File(task.getDirPath()))
                : task.getKind() == DownloadTask.KIND_DASH
                ? DownloadPath.manifest(new File(task.getDirPath()))
                : new File(task.getDirPath(), task.getFileName());
        return file.exists() && file.length() > 0 ? file : null;
    }

    public static void markPlayed(String id) {
        DownloadTask task = DownloadStore.find(id);
        if (task == null) return;
        task.setLastPlayTime(System.currentTimeMillis());
        task.save();
    }

    /** 设置页摘要：未完成任务数 · 已占用空间（/ 容量上限） */
    public static String summary(Context context) {
        int active = AppDatabase.get().getDownloadDao().activeCount();
        String size = com.fongmi.android.tv.utils.FileUtil.byteCountToDisplaySize(DownloadStore.totalBytes());
        String text = ResUtil.getString(R.string.download_summary, active, size);
        long cap = capacityBytes();
        return cap > 0 ? text + " / " + com.fongmi.android.tv.utils.FileUtil.byteCountToDisplaySize(cap) : text;
    }

    public static void startPage(Activity activity) {
        com.fongmi.android.tv.ui.activity.DownloadActivity.start(activity);
    }
}
