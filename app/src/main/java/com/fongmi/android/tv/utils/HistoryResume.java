package com.fongmi.android.tv.utils;

import android.app.Activity;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;

/**
 * 打开一条历史记录，兼容本地/全局历史模式。
 * SEARCH 模式：一律全局按名搜索。
 * AUTO 模式：同配置直开；跨配置且配置存在则切配置后直开；配置缺失则按名搜索。
 * OFF 模式：本地直开（站点缺失时按名搜索兜底）。
 * <p>
 * Activity 启动由调用方通过 Opener 提供（VideoActivity/SearchActivity 位于各 flavor 源集）。
 */
public final class HistoryResume {

    public interface Opener {
        void openVideo(Activity activity, History item);

        void openSearch(Activity activity, String keyword);
    }

    private HistoryResume() {
    }

    public static void open(Activity activity, History item, Opener opener) {
        if (activity == null || item == null || opener == null) return;
        if (Setting.isGlobalHistorySearch()) {
            opener.openSearch(activity, item.getVodName());
            return;
        }
        if (!Setting.isGlobalHistoryEnabled() || item.getCid() == VodConfig.getCid()) {
            openLocal(activity, item, opener);
            return;
        }
        Config config = Config.find(item.getCid());
        if (config == null || config.isEmpty()) {
            opener.openSearch(activity, item.getVodName());
            return;
        }
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                openLocal(activity, item, opener);
            }

            @Override
            public void error(String msg) {
                opener.openSearch(activity, item.getVodName());
            }
        });
    }

    private static void openLocal(Activity activity, History item, Opener opener) {
        Site site = VodConfig.get().getSite(item.getSiteKey());
        if (site == null || site.getKey().isEmpty()) {
            opener.openSearch(activity, item.getVodName());
            return;
        }
        opener.openVideo(activity, item);
    }
}
