package com.fongmi.android.tv.utils;

import android.Manifest;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.fongmi.android.tv.player.danmaku.DanmakuGate;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.crawler.SpiderDebug;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class Notify {

    public static final String DEFAULT = "default";
    public static final int ID = 9527;
    /** 诊断时只扫描栈顶 8 帧：Toast 调用链通常 1~3 帧，8 帧可覆盖 App.post 之类的包装，且不遍历整条调用栈。 */
    private static final int TRACE_FRAMES = 8;
    private static final int TRACE_TEXT_LIMIT = 40;
    private AlertDialog mDialog;
    private Toast mToast;

    private static class Loader {
        static volatile Notify INSTANCE = new Notify();
    }

    private static Notify get() {
        return Loader.INSTANCE;
    }

    public static void createChannel() {
        NotificationManagerCompat notifyMgr = NotificationManagerCompat.from(App.get());
        notifyMgr.createNotificationChannel(new NotificationChannelCompat.Builder(DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName("TV").build());
    }

    public static String getError(int resId, Throwable e) {
        if (TextUtils.isEmpty(e.getMessage())) return ResUtil.getString(resId);
        return ResUtil.getString(resId) + "\n" + e.getMessage();
    }

    public static void show(Notification notification) {
        if (ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat.from(App.get()).notify(ID, notification);
    }

    public static void show(int resId) {
        if (resId != 0) show(ResUtil.getString(resId));
    }

    public static void show(String text) {
        if (TextUtils.isEmpty(text)) return;
        boolean tracing = SpiderDebug.isEnabled();
        boolean danmakuEnabled = DanmakuGate.enabled(DanmakuSetting.isLoad(), DanmakuSetting.isShow());
        if (!danmakuEnabled || tracing) {
            StackTraceElement caller = findCaller(Thread.currentThread().getStackTrace());
            String callerName = caller == null ? null : caller.getClassName();
            if (ThirdPartyToastPolicy.shouldSuppress(callerName, DanmakuSetting.isExplicitlyConfigured(), danmakuEnabled)) {
                if (tracing) SpiderDebug.log("notify", "suppressed third-party toast caller=%s text=%s", describe(caller), summarize(text));
                return;
            }
            if (tracing) SpiderDebug.log("notify", "show caller=%s text=%s", describe(caller), summarize(text));
        }
        get().makeText(text);
    }

    private static String describe(StackTraceElement caller) {
        return caller == null ? "unknown reason=unknown-caller" : caller.toString();
    }

    private static StackTraceElement findCaller(StackTraceElement[] stack) {
        int end = Math.min(stack.length, 1 + TRACE_FRAMES);
        for (int i = 1; i < end; i++) {
            String cls = stack[i].getClassName();
            if (Notify.class.getName().equals(cls) || Thread.class.getName().equals(cls)) continue;
            return stack[i];
        }
        return null;
    }

    private static String summarize(String text) {
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= TRACE_TEXT_LIMIT ? oneLine : oneLine.substring(0, TRACE_TEXT_LIMIT) + "…";
    }

    public static void progress(Context context) {
        dismiss();
        get().create(context);
    }

    public static void dismiss() {
        try {
            if (get().mDialog != null) get().mDialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    public static void dismissToast() {
        try {
            if (get().mToast != null) get().mToast.cancel();
        } catch (Exception ignored) {
        }
    }

    private void create(Context context) {
        ViewProgressBinding binding = ViewProgressBinding.inflate(LayoutInflater.from(context));
        mDialog = new MaterialAlertDialogBuilder(context).setView(binding.getRoot()).create();
        mDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        mDialog.show();
    }

    private void makeText(String text) {
        if (mToast != null) mToast.cancel();
        mToast = Toast.makeText(App.get(), text, Toast.LENGTH_LONG);
        mToast.show();
    }
}
