package com.fongmi.android.tv.download;

import org.greenrobot.eventbus.EventBus;

public class DownloadEvent {

    private final String id;
    private final int doneSegments;
    private final int totalSegments;
    private final long doneBytes;
    private final long totalBytes;
    private final long speed;

    private DownloadEvent(String id, int doneSegments, int totalSegments, long doneBytes, long totalBytes, long speed) {
        this.id = id;
        this.doneSegments = doneSegments;
        this.totalSegments = totalSegments;
        this.doneBytes = doneBytes;
        this.totalBytes = totalBytes;
        this.speed = speed;
    }

    public static void progress(String id, int doneSegments, int totalSegments, long doneBytes, long totalBytes, long speed) {
        EventBus.getDefault().post(new DownloadEvent(id, doneSegments, totalSegments, doneBytes, totalBytes, speed));
    }

    public static void state(String id) {
        EventBus.getDefault().post(new DownloadEvent(id, 0, 0, 0, 0, 0));
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
