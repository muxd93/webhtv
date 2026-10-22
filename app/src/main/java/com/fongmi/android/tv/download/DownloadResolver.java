package com.fongmi.android.tv.download;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.ParseJob;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;

import java.util.Map;
import java.util.concurrent.Executors;

/** 后台解析剧集地址：与播放同链路（playerContent + ParseJob），成功后返回真实媒体地址与 headers */
public class DownloadResolver implements ParseCallback {

    // 独立单线程池：解析走 WebView 嗅探可能耗时较久，避免与搜索/蜘蛛等任务争抢共享池导致调度饿死
    private static final java.util.concurrent.ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface Callback {

        void onSuccess(String url, Map<String, String> headers);

        void onError(String msg);
    }

    private final String siteKey;
    private final String flag;
    private final String episodeUrl;
    private final Callback callback;
    private ParseJob job;
    private volatile boolean canceled;

    public DownloadResolver(String siteKey, String flag, String episodeUrl, Callback callback) {
        this.siteKey = siteKey;
        this.flag = flag;
        this.episodeUrl = episodeUrl;
        this.callback = callback;
    }

    public static void resolve(String siteKey, String flag, String episodeUrl, Callback callback) {
        DownloadResolver resolver = new DownloadResolver(siteKey, flag, episodeUrl, callback);
        resolver.start();
    }

    public void start() {
        EXECUTOR.execute(() -> {
            try {
                if (canceled) return;
                Result result = SiteApi.playerContent(siteKey, flag, episodeUrl, PlayerSetting.getPlayer());
                if (canceled) return;
                SpiderDebug.log("download", "resolver content key=%s flag=%s msg=%s", siteKey, flag, result == null ? "null" : result.getMsg());
                if (result == null || result.hasMsg() || result.getRealUrl().isEmpty()) {
                    error(result == null ? "empty" : result.hasMsg() ? result.getMsg() : "empty");
                    return;
                }
                SpiderDebug.log("download", "resolver parsed key=%s flag=%s urlLen=%d useParse=%s", siteKey, flag, episodeUrl.length(), result.shouldUseParse());
                if (result.shouldUseParse()) {
                    job = ParseJob.create(this).start(result, true);
                } else {
                    success(result.getHeader(), result.getUrl().v());
                }
            } catch (Throwable e) {
                error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }

    public void cancel() {
        canceled = true;
        if (job != null) job.stop();
    }

    private void success(Map<String, String> headers, String url) {
        App.post(() -> {
            if (!canceled) callback.onSuccess(url, headers == null ? new java.util.HashMap<>() : headers);
        });
    }

    private void error(String msg) {
        App.post(() -> {
            if (!canceled) callback.onError(msg);
        });
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        success(headers, url);
    }

    @Override
    public void onParseError() {
        error("parse failed");
    }
}
