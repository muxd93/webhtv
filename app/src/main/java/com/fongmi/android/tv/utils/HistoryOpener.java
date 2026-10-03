package com.fongmi.android.tv.utils;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class HistoryOpener {

    private HistoryOpener() {
    }

    /** 聚合模式下跨配置条目先确认切换配置再进详情；其余按原路径直接打开。 */
    public static void open(AppCompatActivity activity, History item) {
        if (!Setting.isHistoryAggregation() || item.getCid() == VodConfig.getCid()) {
            start(activity, item);
            return;
        }
        Config config = Config.find(item.getCid());
        if (config == null) {
            Notify.show(R.string.history_config_deleted);
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.history_switch_title)
                .setMessage(activity.getString(R.string.history_switch_message, config.getDesc(), item.getVodName()))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    Notify.show(activity.getString(R.string.history_switching, config.getDesc()));
                    VodConfig.load(config, new Callback() {
                        @Override
                        public void success() {
                            HistoryOpener.start(activity, item);
                        }

                        @Override
                        public void error(String msg) {
                            Notify.show(msg);
                        }
                    });
                })
                .show();
    }

    private static void start(AppCompatActivity activity, History item) {
        VideoActivity.start(activity, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic(), null, item.getWallPic());
    }
}
