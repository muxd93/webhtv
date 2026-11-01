package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.ConfigService;
import com.fongmi.android.tv.api.config.RulePool;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.gson.GsonProvider;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.Prefers;
import com.orhanobut.logger.Logger;
import com.fongmi.android.tv.playback.PlaybackRemoteSyncer;
import com.fongmi.android.tv.player.PlaybackMemoryMonitor;
import com.fongmi.android.tv.player.PlaybackSystemConditionMonitor;
import com.fongmi.android.tv.player.mpv.PlaybackRecoveryMonitor;
import com.fongmi.android.tv.remote.RemoteAgent;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.DanmakuSearchListFocusFixer;
import com.fongmi.android.tv.utils.NsdDeviceDiscovery;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PreviousProcessExitLogger;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.hook.Hook;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private volatile Activity activity;
    private Hook hook;

    public App() {
        instance = this;
        gson = GsonProvider.get();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    /** 启动期单点容错：任一初始化失败只记录日志，不波及后续初始化与整个 App 启动 */
    private void safe(Runnable runnable, String tag) {
        try {
            runnable.run();
        } catch (Throwable e) {
            Logger.e("startup:" + tag, e);
        }
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (PlaybackRecoveryMonitor.isRecoveryProcess(base)) return;
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (PlaybackRecoveryMonitor.isRecoveryProcess(this)) return;
        PlaybackMemoryMonitor.process().initialize(this);
        PlaybackSystemConditionMonitor.process().initialize(this);
        Setting.applyLanguage();
        DebugLogStore.restoreEnabled();
        if (DebugLogStore.isEnabled()) {
            PlaybackRecoveryMonitor.logPreviousResult(this);
            Setting.logDebugEnvironment("restore");
            PreviousProcessExitLogger.log(this);
        }
        Notify.createChannel();
        safe(() -> com.fongmi.android.tv.download.DownloadManager.init(), "download");
        safe(ProxySetting::apply, "proxy");
        safe(RulePool::apply, "rule_pool");
        safe(this::fixLeakedConfigNames, "config_name_fix");
        DanmakuSearchListFocusFixer.start();
        registerActivityLifecycleCallbacks(this);
        post(this::startBackgroundServices, 1200);
    }

    @Override
    public void onTrimMemory(int level) {
        if (!PlaybackRecoveryMonitor.isRecoveryProcess(this)) PlaybackMemoryMonitor.process().onTrimMemory(level);
        super.onTrimMemory(level);
    }

    @Override
    public void onLowMemory() {
        if (!PlaybackRecoveryMonitor.isRecoveryProcess(this)) PlaybackMemoryMonitor.process().onLowMemory();
        super.onLowMemory();
    }

    /** 一次性清理：预设回填连按导致源名称张冠李戴的历史脏数据（修复后不会再有新脏数据） */
    private void fixLeakedConfigNames() {
        if (Prefers.getBoolean("config_name_leak_fixed", false)) return;
        try {
            int fixed = ConfigService.fixLeakedNames();
            if (fixed > 0) Logger.i("config name leak fixed: " + fixed);
        } catch (Exception e) {
            Logger.e("fixLeakedConfigNames", e);
        }
        Prefers.put("config_name_leak_fixed", true);
    }

    private void startBackgroundServices() {
        SpiderDebug.log("startup", "background services start cost=%sms", System.currentTimeMillis() - time);
        // 局域网 HTTP 服务阻塞在 accept，无连接时不唤醒 CPU，成本可忽略，省电模式也保留
        Server.get().start();
        // 历史行物理清理：读取侧本就按 Constant.HISTORY_TIME 窗口过滤，这里同步删除过期行，避免 DB 无限累积
        Task.execute(() -> {
            int pruned = AppDatabase.get().getHistoryDao().deleteBefore(System.currentTimeMillis() - Constant.HISTORY_TIME);
            if (pruned > 0) SpiderDebug.log("startup", "history pruned rows=%s", pruned);
        });
        // 离线缓存恢复：进程重启后继续未完成的任务（用户主动发起，不受省电启动开关影响）
        com.fongmi.android.tv.download.DownloadManager.resumeIfNeeded(this);
        // 省电启动：远程托管(4s 轮询)、观影记录同步、mDNS 注册都不自动启动
        if (Setting.isPowerSaveStartup()) {
            SpiderDebug.log("startup", "power save: skip remote agent, playback sync and nsd");
            return;
        }
        PlaybackRemoteSyncer.start();
        RemoteAgent.get().start();
        NsdDeviceDiscovery.register();
        SpiderDebug.log("startup", "background services ready cost=%sms", System.currentTimeMillis() - time);
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}
