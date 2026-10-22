package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Diffable;
import com.google.gson.annotations.SerializedName;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

@Entity(tableName = "DownloadTask", indices = {@Index(value = {"cid", "siteKey", "vodId"})})
public class DownloadTask implements Diffable<DownloadTask> {

    public static final int KIND_UNKNOWN = 0;
    public static final int KIND_HLS = 1;
    public static final int KIND_FILE = 2;
    public static final int KIND_DASH = 3;

    public static final int STOP_NONE = 0;
    public static final int STOP_NETWORK = 1;
    public static final int STOP_USER = 2;
    public static final int STOP_STORAGE = 3;
    public static final int STOP_PERMISSION = 4;

    public static final int STATE_QUEUED = 0;
    public static final int STATE_RESOLVING = 1;
    public static final int STATE_DOWNLOADING = 2;
    public static final int STATE_PAUSED = 3;
    public static final int STATE_COMPLETED = 4;
    public static final int STATE_FAILED = 5;

    @NonNull
    @PrimaryKey
    @SerializedName("id")
    private String id;
    @SerializedName("cid")
    private int cid;
    @SerializedName("siteKey")
    private String siteKey;
    @SerializedName("vodId")
    private String vodId;
    @SerializedName("vodName")
    private String vodName;
    @SerializedName("vodPic")
    private String vodPic;
    @SerializedName("vodFlag")
    private String vodFlag;
    @SerializedName("episodeName")
    private String episodeName;
    @SerializedName("episodeUrl")
    private String episodeUrl;
    @SerializedName("kind")
    private int kind;
    @SerializedName("state")
    private int state;
    @SerializedName("dirPath")
    private String dirPath;
    @SerializedName("fileName")
    private String fileName;
    @SerializedName("totalBytes")
    private long totalBytes;
    @SerializedName("doneBytes")
    private long doneBytes;
    @SerializedName("totalSegments")
    private int totalSegments;
    @SerializedName("doneSegments")
    private int doneSegments;
    @SerializedName("resolvedUrl")
    private String resolvedUrl;
    @SerializedName("headers")
    private String headers;
    @SerializedName("createTime")
    private long createTime;
    @SerializedName("finishTime")
    private long finishTime;
    @SerializedName("lastPlayTime")
    private long lastPlayTime;
    @SerializedName("errorMsg")
    private String errorMsg;
    @SerializedName("stopReason")
    @ColumnInfo(defaultValue = "0")
    private int stopReason;
    @SerializedName("retryCount")
    @ColumnInfo(defaultValue = "0")
    private int retryCount;
    @SerializedName("sourceHash")
    private String sourceHash;

    public DownloadTask() {
        this.id = "";
        this.state = STATE_QUEUED;
        this.createTime = System.currentTimeMillis();
    }

    // 稳定内容键：同一站点/影片/线路/剧集始终得到同一 id，重新解析后可复用同一条任务（纯 Java MD5，JVM 可测）
    public static String id(String siteKey, String vodId, String flag, String episodeName, String episodeUrl) {
        String src = Objects.toString(siteKey, "") + "|" + Objects.toString(vodId, "") + "|" + Objects.toString(flag, "") + "|" + Objects.toString(episodeName, "") + "|" + Objects.toString(episodeUrl, "");
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(src.getBytes(StandardCharsets.UTF_8));
            BigInteger no = new BigInteger(1, bytes);
            StringBuilder sb = new StringBuilder(no.toString(16));
            while (sb.length() < 32) sb.insert(0, "0");
            return sb.toString().toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            return src;
        }
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    public int getCid() {
        return cid;
    }

    public void setCid(int cid) {
        this.cid = cid;
    }

    public String getSiteKey() {
        return Objects.toString(siteKey, "");
    }

    public void setSiteKey(String siteKey) {
        this.siteKey = siteKey;
    }

    public String getVodId() {
        return Objects.toString(vodId, "");
    }

    public void setVodId(String vodId) {
        this.vodId = vodId;
    }

    public String getVodName() {
        return Objects.toString(vodName, "");
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getVodPic() {
        return Objects.toString(vodPic, "");
    }

    public void setVodPic(String vodPic) {
        this.vodPic = vodPic;
    }

    public String getVodFlag() {
        return Objects.toString(vodFlag, "");
    }

    public void setVodFlag(String vodFlag) {
        this.vodFlag = vodFlag;
    }

    public String getEpisodeName() {
        return Objects.toString(episodeName, "");
    }

    public void setEpisodeName(String episodeName) {
        this.episodeName = episodeName;
    }

    public String getEpisodeUrl() {
        return Objects.toString(episodeUrl, "");
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl;
    }

    public int getKind() {
        return kind;
    }

    public void setKind(int kind) {
        this.kind = kind;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public boolean isCompleted() {
        return state == STATE_COMPLETED;
    }

    public boolean isRunning() {
        return state == STATE_RESOLVING || state == STATE_DOWNLOADING;
    }

    public boolean isFailed() {
        return state == STATE_FAILED;
    }

    public boolean canResume() {
        return state == STATE_PAUSED || state == STATE_FAILED;
    }

    public String getDirPath() {
        return Objects.toString(dirPath, "");
    }

    public void setDirPath(String dirPath) {
        this.dirPath = dirPath;
    }

    public String getFileName() {
        return Objects.toString(fileName, "");
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public long getDoneBytes() {
        return doneBytes;
    }

    public void setDoneBytes(long doneBytes) {
        this.doneBytes = doneBytes;
    }

    public int getTotalSegments() {
        return totalSegments;
    }

    public void setTotalSegments(int totalSegments) {
        this.totalSegments = totalSegments;
    }

    public int getDoneSegments() {
        return doneSegments;
    }

    public void setDoneSegments(int doneSegments) {
        this.doneSegments = doneSegments;
    }

    public String getResolvedUrl() {
        return Objects.toString(resolvedUrl, "");
    }

    public void setResolvedUrl(String resolvedUrl) {
        this.resolvedUrl = resolvedUrl;
    }

    public String getHeaders() {
        return Objects.toString(headers, "");
    }

    public void setHeaders(String headers) {
        this.headers = headers;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(long finishTime) {
        this.finishTime = finishTime;
    }

    public long getLastPlayTime() {
        return lastPlayTime;
    }

    public void setLastPlayTime(long lastPlayTime) {
        this.lastPlayTime = lastPlayTime;
    }

    public String getErrorMsg() {
        return Objects.toString(errorMsg, "");
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public int getStopReason() {
        return stopReason;
    }

    public void setStopReason(int stopReason) {
        this.stopReason = stopReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getSourceHash() {
        return Objects.toString(sourceHash, "");
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }

    /** 因网络等非用户原因停止的任务，条件恢复后可自动续传 */
    public boolean canAutoResume() {
        return state == STATE_PAUSED && stopReason == STOP_NETWORK;
    }

    public DownloadTask copy() {
        DownloadTask item = new DownloadTask();
        item.id = id;
        item.cid = cid;
        item.siteKey = siteKey;
        item.vodId = vodId;
        item.vodName = vodName;
        item.vodPic = vodPic;
        item.vodFlag = vodFlag;
        item.episodeName = episodeName;
        item.episodeUrl = episodeUrl;
        item.kind = kind;
        item.state = state;
        item.dirPath = dirPath;
        item.fileName = fileName;
        item.totalBytes = totalBytes;
        item.doneBytes = doneBytes;
        item.totalSegments = totalSegments;
        item.doneSegments = doneSegments;
        item.resolvedUrl = resolvedUrl;
        item.headers = headers;
        item.createTime = createTime;
        item.finishTime = finishTime;
        item.lastPlayTime = lastPlayTime;
        item.errorMsg = errorMsg;
        item.stopReason = stopReason;
        item.retryCount = retryCount;
        item.sourceHash = sourceHash;
        return item;
    }

    public void save() {
        AppDatabase.get().getDownloadDao().insertOrUpdate(this);
    }

    public void delete() {
        AppDatabase.get().getDownloadDao().delete(getId());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DownloadTask it)) return false;
        return Objects.equals(getId(), it.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }

    @Override
    public boolean isSameItem(DownloadTask other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(DownloadTask other) {
        return getState() == other.getState() && getDoneBytes() == other.getDoneBytes() && getErrorMsg().equals(other.getErrorMsg()) && getLastPlayTime() == other.getLastPlayTime();
    }
}
