package com.fongmi.android.tv.download;

import org.greenrobot.eventbus.EventBus;

public class DownloadEvent {

    private final String id;
    private final int doneSegments;
    private final int totalSegments;
    private final long doneBytes;
    private final long totalBytes;
    private final long speed;
    private final boolean stateChange;

    private DownloadEvent(String id, int doneSegments, int totalSegments, long doneBytes, long totalBytes, long speed, boolean stateChange) {
        this.id = id;
        this.doneSegments = doneSegments;
        this.totalSegments = totalSegments;
        this.doneBytes = doneBytes;
        this.totalBytes = totalBytes;
        this.speed = speed;
        this.stateChange = stateChange;
    }

    public static void progress(String id, int doneSegments, int totalSegments, long doneBytes, long totalBytes, long speed) {
        EventBus.getDefault().post(new DownloadEvent(id, doneSegments, totalSegments, doneBytes, totalBytes, speed, false));
    }

    public static void state(String id) {
        EventBus.getDefault().post(new DownloadEvent(id, 0, 0, 0, 0, 0, true));
    }

    /** 状态切换（暂停/继续/完成/失败/删除）而非进度刷新 */
    public boolean isStateChange() {
        return stateChange;
    }

    public String getId() {
        return id;
    }

    public int getDoneSegments() {
        return doneSegments;
    }

    public int getTotalSegments() {
        return totalSegments;
    }

    public long getDoneBytes() {
        return doneBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getSpeed() {
        return speed;
    }
}
