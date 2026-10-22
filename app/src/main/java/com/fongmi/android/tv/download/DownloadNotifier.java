package com.fongmi.android.tv.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.DownloadActivity;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

public class DownloadNotifier {

    // Notify.ID=9527；+1=DLNARendererService、+2=ManageService、+5=RemoteAgentService 已占用，取 +6 避免互相覆盖
    public static final int ID = Notify.ID + 6;
    private static final String CHANNEL = "download";

    private DownloadNotifier() {
    }

    public static void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, ResUtil.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getManager().createNotificationChannel(channel);
    }

    public static Notification progress(int running, int percent, long doneBytes, long totalBytes) {
        Context context = App.get();
        StringBuilder text = new StringBuilder(running + " " + ResUtil.getString(R.string.download_notify_tasks));
        if (percent >= 0) text.append(" · ").append(percent).append("%");
        if (totalBytes > 0) text.append(" · ").append(FileUtil.byteCountToDisplaySize(doneBytes)).append("/").append(FileUtil.byteCountToDisplaySize(totalBytes));
        else if (doneBytes > 0) text.append(" · ").append(FileUtil.byteCountToDisplaySize(doneBytes));
        return base(context)
                .setContentText(text.toString())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    public static Notification waiting(int queued) {
        Context context = App.get();
        String text = queued > 0
                ? ResUtil.getString(R.string.download_notify_waiting) + " · " + queued + " " + ResUtil.getString(R.string.download_notify_tasks)
                : ResUtil.getString(R.string.download_notify_waiting);
        return base(context)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    public static Notification completed(String text) {
        return base(App.get())
                .setContentTitle(ResUtil.getString(R.string.download_notify_completed))
                .setContentText(text)
                .setAutoCancel(true)
                .build();
    }

    private static NotificationCompat.Builder base(Context context) {
        return new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_detail_download)
                .setContentTitle(ResUtil.getString(R.string.download_notify_title))
                .setContentIntent(intent(context));
    }

    public static void notify(int id, Notification notification) {
        try {
            getManager().notify(id, notification);
        } catch (Exception ignored) {
        }
    }

    public static void cancel(int id) {
        try {
            getManager().cancel(id);
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent intent(Context context) {
        Intent intent = new Intent(context, DownloadActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static NotificationManager getManager() {
        return (NotificationManager) App.get().getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
