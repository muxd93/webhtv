package com.fongmi.android.tv.download;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.DownloadTask;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadStore {

    private static final Gson GSON = new Gson();

    private DownloadStore() {
    }

    public static List<DownloadTask> all() {
        return AppDatabase.get().getDownloadDao().findAll();
    }

    public static DownloadTask find(String id) {
        return AppDatabase.get().getDownloadDao().find(id);
    }

    public static List<DownloadTask> findByEpisodeUrl(String siteKey, String vodId, String episodeUrl) {
        return AppDatabase.get().getDownloadDao().findByEpisodeUrl(siteKey, vodId, episodeUrl);
    }

    public static List<DownloadTask> findAutoResumable() {
        return AppDatabase.get().getDownloadDao().findAutoResumable();
    }

    public static List<DownloadTask> findFailedPermission() {
        return AppDatabase.get().getDownloadDao().findFailedPermission();
    }

    public static List<DownloadTask> findByVod(String siteKey, String vodId) {
        return AppDatabase.get().getDownloadDao().findByVod(VodConfig.getCid(), siteKey, vodId);
    }

    public static List<DownloadTask> queued() {
        return AppDatabase.get().getDownloadDao().findQueued();
    }

    public static List<DownloadTask> running() {
        return AppDatabase.get().getDownloadDao().findRunning();
    }

    public static List<DownloadTask> completed() {
        return AppDatabase.get().getDownloadDao().findCompleted();
    }

    public static long totalBytes() {
        return AppDatabase.get().getDownloadDao().totalBytes();
    }

    public static void save(DownloadTask task) {
        task.save();
    }

    public static void delete(DownloadTask task) {
        deleteFiles(task);
        task.delete();
    }

    public static void deleteFiles(DownloadTask task) {
        if (TextUtils.isEmpty(task.getDirPath())) return;
        Path.clear(new File(task.getDirPath()));
    }

    public static void deleteAll() {
        for (DownloadTask task : all()) deleteFiles(task);
        AppDatabase.get().getDownloadDao().deleteAll();
    }

    /** 侧车对账：数据库丢失但文件仍在时，按 info.json 恢复 COMPLETED 任务；并清理无主的孤儿目录 */
    public static void reconcile() {
        Task.execute(() -> {
            File root = DownloadPath.root();
            File[] vodDirs = root.listFiles(File::isDirectory);
            if (vodDirs == null) return;
            List<DownloadTask> tasks = new ArrayList<>();
            for (File vodDir : vodDirs) {
                File[] episodeDirs = vodDir.listFiles(File::isDirectory);
                if (episodeDirs == null) continue;
                for (File episodeDir : episodeDirs) {
                    DownloadTask task = fromSidecar(episodeDir);
                    if (task != null) tasks.add(task);
                }
            }
            if (!tasks.isEmpty()) AppDatabase.get().getDownloadDao().insertOrUpdate(tasks);
            cleanOrphans(vodDirs);
        });
    }

    /**
     * 孤儿目录清理：无任务记录、无 info.json、且 15 分钟内无写入（避开正在下载的目录）的剧集目录删除。
     * 覆盖：DB 被清/任务被删但文件残留、以及失败取消后的半成品目录。
     */
    private static void cleanOrphans(File[] vodDirs) {
        List<String> activeDirs = new ArrayList<>();
        for (DownloadTask task : all()) if (!task.getDirPath().isEmpty()) activeDirs.add(task.getDirPath());
        long threshold = System.currentTimeMillis() - 15 * 60 * 1000;
        for (File vodDir : vodDirs) {
            File[] episodeDirs = vodDir.listFiles(File::isDirectory);
            if (episodeDirs == null) continue;
            for (File episodeDir : episodeDirs) {
                if (activeDirs.contains(episodeDir.getAbsolutePath())) continue;
                if (DownloadPath.info(episodeDir).exists()) continue;
                if (episodeDir.lastModified() > threshold) continue;
                Path.clear(episodeDir);
            }
            File[] left = vodDir.listFiles();
            if (left != null && left.length == 0) Path.clear(vodDir);
        }
    }

    private static DownloadTask fromSidecar(File episodeDir) {
        File info = DownloadPath.info(episodeDir);
        if (!info.exists()) return null;
        try {
            Sidecar sidecar = GSON.fromJson(Path.read(info), Sidecar.class);
            if (sidecar == null || TextUtils.isEmpty(sidecar.id)) return null;
            if (find(sidecar.id) != null) return null;
            DownloadTask task = new DownloadTask();
            task.setId(sidecar.id);
            task.setCid(VodConfig.getCid());
            task.setSiteKey(sidecar.siteKey);
            task.setVodId(sidecar.vodId);
            task.setVodName(sidecar.vodName);
            task.setVodPic(sidecar.vodPic);
            task.setVodFlag(sidecar.vodFlag);
            task.setEpisodeName(sidecar.episodeName);
            task.setEpisodeUrl(sidecar.episodeUrl);
            task.setKind(sidecar.kind);
            task.setState(DownloadTask.STATE_COMPLETED);
            task.setDirPath(episodeDir.getAbsolutePath());
            task.setFileName(sidecar.fileName);
            task.setTotalBytes(sidecar.totalBytes);
            task.setDoneBytes(sidecar.totalBytes);
            task.setCreateTime(sidecar.createTime);
            task.setFinishTime(sidecar.finishTime);
            return task;
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeSidecar(DownloadTask task, File dir, int kind, String fileName, long bytes) {
        Sidecar sidecar = new Sidecar();
        sidecar.id = task.getId();
        sidecar.siteKey = task.getSiteKey();
        sidecar.vodId = task.getVodId();
        sidecar.vodName = task.getVodName();
        sidecar.vodPic = task.getVodPic();
        sidecar.vodFlag = task.getVodFlag();
        sidecar.episodeName = task.getEpisodeName();
        sidecar.episodeUrl = task.getEpisodeUrl();
        sidecar.kind = kind;
        sidecar.fileName = fileName;
        sidecar.totalBytes = bytes;
        sidecar.createTime = task.getCreateTime();
        sidecar.finishTime = System.currentTimeMillis();
        Path.write(DownloadPath.info(dir), GSON.toJson(sidecar).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static class Sidecar {

        @SerializedName("id")
        public String id;
        @SerializedName("siteKey")
        public String siteKey;
        @SerializedName("vodId")
        public String vodId;
        @SerializedName("vodName")
        public String vodName;
        @SerializedName("vodPic")
        public String vodPic;
        @SerializedName("vodFlag")
        public String vodFlag;
        @SerializedName("episodeName")
        public String episodeName;
        @SerializedName("episodeUrl")
        public String episodeUrl;
        @SerializedName("kind")
        public int kind;
        @SerializedName("fileName")
        public String fileName;
        @SerializedName("totalBytes")
        public long totalBytes;
        @SerializedName("createTime")
        public long createTime;
        @SerializedName("finishTime")
        public long finishTime;
    }
}
