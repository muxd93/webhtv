package com.fongmi.android.tv.player.exo;

import android.os.Handler;
import android.os.HandlerThread;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.source.preload.PreCacheHelper;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One-shot disk pre-cache of the next episode's opening segment. Shares the
 * process SimpleCache and the preload priority lane so playback hits the
 * cached opening right after an auto episode switch. Gated on the EXO kernel
 * with disk preload enabled; every failure is silent because the normal
 * resolve path remains the fallback.
 */
public final class NextEpisodePreCache {

    private static final long OPENING_PRECACHE_MS = 60_000;

    private static final Object LOCK = new Object();
    private static PreCacheHelper helper;
    private static HandlerThread worker;
    private static ThreadPoolExecutor downloadExecutor;
    private static ExoPreloadTrafficCoordinator.Registration trafficRegistration;

    private NextEpisodePreCache() {
    }

    public static void preCache(PlaySpec spec) {
        if (spec == null || PlayerSetting.getPlayer() != PlayerSetting.EXO
                || !PreloadSetting.isPreload(PlayerSetting.EXO)) return;
        MediaItem item = ExoUtil.getMediaItem(spec, PlayerEngine.HARD);
        if (!eligible(item)) return;
        if (!MediaSourceFactory.getCacheWriteDecision().writeAllowed()) return;
        synchronized (LOCK) {
            stopLocked();
            worker = new HandlerThread("NextEpisodePreCache");
            worker.start();
            downloadExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
            DataSource.Factory upstreamFactory = MediaSourceFactory.createUpstreamDataSourceFactory(ExoUtil.extractHeaders(item));
            PreCacheHelper started = new PreCacheHelper.Factory(MediaSourceFactory.getCache(), upstreamFactory, ExoUtil.buildRenderersFactory(), worker.getLooper())
                    .setDownloadExecutor(downloadExecutor)
                    .setListener(new PreCacheHelper.Listener() {
                        @Override
                        public void onPrepared(MediaItem originalMediaItem, MediaItem preparedMediaItem) {
                        }

                        @Override
                        public void onPreCacheCompleted(MediaItem mediaItem) {
                            stop();
                        }

                        @Override
                        public void onPrepareError(MediaItem mediaItem, IOException exception) {
                            stop();
                        }

                        @Override
                        public void onDownloadError(MediaItem mediaItem, IOException exception) {
                            stop();
                        }
                    })
                    .create(item);
            helper = started;
            trafficRegistration = ExoPreloadTrafficCoordinator.process().acquire(
                    spec.getPlaybackTraceId(), ExoPreloadTrafficCoordinator.Source.CUSTOM);
            started.preCache(0, OPENING_PRECACHE_MS);
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        if (helper == null && worker == null && downloadExecutor == null && trafficRegistration == null) return;
        PreCacheHelper retiringHelper = helper;
        HandlerThread retiringWorker = worker;
        ThreadPoolExecutor retiringExecutor = downloadExecutor;
        ExoPreloadTrafficCoordinator.Registration retiringTraffic = trafficRegistration;
        helper = null;
        worker = null;
        downloadExecutor = null;
        trafficRegistration = null;
        if (retiringTraffic != null) retiringTraffic.close();
        if (retiringHelper != null) retiringHelper.release(false);
        if (retiringWorker == null) {
            shutdownExecutor(retiringExecutor);
            return;
        }
        // PreCacheHelper.release() posts cancellation to this same looper.
        // Queue resource teardown behind it so SegmentDownloader cannot submit
        // work to an executor which has already entered SHUTTING_DOWN.
        new Handler(retiringWorker.getLooper()).post(() -> {
            shutdownExecutor(retiringExecutor);
            retiringWorker.quitSafely();
        });
    }

    private static boolean eligible(MediaItem item) {
        if (item.localConfiguration == null) return false;
        String scheme = item.localConfiguration.uri.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        return http && !MediaSourceFactory.isConcatenatingUrl(item.localConfiguration.uri.toString());
    }

    private static void shutdownExecutor(ThreadPoolExecutor target) {
        if (target == null) return;
        target.shutdownNow();
    }
}
