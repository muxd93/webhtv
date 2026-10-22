package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.DownloadTask;

import java.util.List;

@Dao
public abstract class DownloadDao extends BaseDao<DownloadTask> {

    @Query("SELECT * FROM DownloadTask ORDER BY createTime DESC")
    public abstract List<DownloadTask> findAll();

    @Query("SELECT * FROM DownloadTask WHERE id = :id")
    public abstract DownloadTask find(String id);

    /** 播放命中口径：不按 cid 过滤（本地文件自含可播，切换点播源/离线都应命中），以 siteKey+vodId+episodeUrl 精确定位 */
    @Query("SELECT * FROM DownloadTask WHERE siteKey = :siteKey AND vodId = :vodId AND episodeUrl = :episodeUrl")
    public abstract List<DownloadTask> findByEpisodeUrl(String siteKey, String vodId, String episodeUrl);

    @Query("SELECT * FROM DownloadTask WHERE state = 3 AND stopReason = 1 ORDER BY createTime ASC")
    public abstract List<DownloadTask> findAutoResumable();

    @Query("SELECT * FROM DownloadTask WHERE state = 5 AND stopReason = 4 ORDER BY createTime ASC")
    public abstract List<DownloadTask> findFailedPermission();

    @Query("SELECT * FROM DownloadTask WHERE cid = :cid AND siteKey = :siteKey AND vodId = :vodId ORDER BY createTime ASC")
    public abstract List<DownloadTask> findByVod(int cid, String siteKey, String vodId);

    @Query("SELECT * FROM DownloadTask WHERE state = 0 ORDER BY createTime ASC")
    public abstract List<DownloadTask> findQueued();

    @Query("SELECT * FROM DownloadTask WHERE state = 1 OR state = 2")
    public abstract List<DownloadTask> findRunning();

    @Query("SELECT * FROM DownloadTask WHERE state = 4 ORDER BY CASE WHEN lastPlayTime > 0 THEN lastPlayTime ELSE finishTime END ASC")
    public abstract List<DownloadTask> findCompleted();

    @Query("SELECT COALESCE(SUM(doneBytes), 0) FROM DownloadTask")
    public abstract long totalBytes();

    @Query("SELECT COUNT(*) FROM DownloadTask WHERE state != 4")
    public abstract int activeCount();

    @Query("DELETE FROM DownloadTask WHERE id = :id")
    public abstract void delete(String id);

    @Query("DELETE FROM DownloadTask")
    public abstract void deleteAll();
}
