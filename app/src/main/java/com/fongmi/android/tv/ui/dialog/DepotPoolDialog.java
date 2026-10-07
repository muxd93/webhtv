package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.DepotPool;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 多仓聚合池管理（DEPOT3）：聚合开关、直播条目与广告规则的启用/禁用/删除、恢复默认与清空。
 * 程序化列表对话框，双 flavor 共用；状态变更即时写回 DepotPool 并重载当前直播配置
 * （缓存先行）使并集/剔除生效，随后重建本对话框反映最新状态。
 */
public class DepotPoolDialog {

    private final Activity activity;

    public static void show(Activity activity) {
        new DepotPoolDialog(activity).showPool();
    }

    private DepotPoolDialog(Activity activity) {
        this.activity = activity;
    }

    private void showPool() {
        boolean enabled = LiveSetting.isPool();
        List<DepotPool.PoolLive> lives = DepotPool.lives(null, false);
        List<DepotPool.AdItem> ads = DepotPool.ads(false);
        List<Object> refs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        labels.add(ResUtil.getString(enabled ? R.string.depot_pool_switch_on : R.string.depot_pool_switch_off));
        refs.add(null);
        if (lives.isEmpty() && ads.isEmpty()) {
            labels.add(ResUtil.getString(R.string.depot_pool_empty));
            refs.add(null);
        }
        if (!lives.isEmpty()) {
            labels.add(ResUtil.getString(R.string.depot_pool_section_lives, lives.size()));
            refs.add(null);
            for (DepotPool.PoolLive item : lives) {
                labels.add(liveLabel(item));
                refs.add(item);
            }
        }
        if (!ads.isEmpty()) {
            labels.add(ResUtil.getString(R.string.depot_pool_section_ads, ads.size()));
            refs.add(null);
            for (DepotPool.AdItem item : ads) {
                labels.add(adLabel(item));
                refs.add(item);
            }
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.depot_pool_title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= refs.size()) return;
                    Object ref = refs.get(which);
                    if (ref instanceof DepotPool.PoolLive) showLiveActions((DepotPool.PoolLive) ref);
                    else if (ref instanceof DepotPool.AdItem) showAdActions((DepotPool.AdItem) ref);
                    else if (which == 0) toggle();
                })
                .setPositiveButton(R.string.depot_pool_reset, (dialog, which) -> {
                    DepotPool.resetStates();
                    Notify.show(R.string.depot_pool_reset_done);
                    refreshLive();
                    showPool();
                })
                .setNegativeButton(R.string.depot_pool_clear, (dialog, which) -> confirmClear())
                .show();
    }

    private String liveLabel(DepotPool.PoolLive item) {
        String label = item.origName() + "（" + item.childName() + "）";
        String state = stateLabel(item.state());
        return state.isEmpty() ? label : label + " " + state;
    }

    private String adLabel(DepotPool.AdItem item) {
        String state = stateLabel(item.state());
        return state.isEmpty() ? item.rule() : item.rule() + " " + state;
    }

    private String stateLabel(int state) {
        if (state == DepotPool.DISABLED) return ResUtil.getString(R.string.depot_pool_state_disabled);
        if (state == DepotPool.DELETED) return ResUtil.getString(R.string.depot_pool_state_deleted);
        return "";
    }

    private void showLiveActions(DepotPool.PoolLive item) {
        List<String> options = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        if (item.state() == DepotPool.NORMAL) option(R.string.depot_pool_action_disable, DepotPool.DISABLED, options, targets);
        else option(R.string.depot_pool_action_enable, DepotPool.NORMAL, options, targets);
        if (item.state() != DepotPool.DELETED) option(R.string.depot_pool_action_delete, DepotPool.DELETED, options, targets);
        else option(R.string.depot_pool_action_restore, DepotPool.NORMAL, options, targets);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(item.origName())
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    DepotPool.setState(item.url(), DepotPool.DOM_LIVE, item.origName(), targets.get(which));
                    refreshLive();
                    showPool();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showAdActions(DepotPool.AdItem item) {
        List<String> options = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        if (item.state() == DepotPool.NORMAL) option(R.string.depot_pool_action_disable, DepotPool.DISABLED, options, targets);
        else option(R.string.depot_pool_action_enable, DepotPool.NORMAL, options, targets);
        if (item.state() != DepotPool.DELETED) option(R.string.depot_pool_action_delete, DepotPool.DELETED, options, targets);
        else option(R.string.depot_pool_action_restore, DepotPool.NORMAL, options, targets);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.depot_pool_title)
                .setMessage(item.rule())
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    DepotPool.setState(null, DepotPool.DOM_AD, item.rule(), targets.get(which));
                    refreshLive();
                    showPool();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void option(int res, int target, List<String> options, List<Integer> targets) {
        options.add(ResUtil.getString(res));
        targets.add(target);
    }

    private void toggle() {
        LiveSetting.putPool(!LiveSetting.isPool());
        refreshLive();
        showPool();
    }

    /** 状态变更后重载当前直播配置（缓存先行）：finishLive 重建并集，事件经既有链路广播。 */
    private void refreshLive() {
        LiveConfig.load(Config.live(), new Callback());
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.depot_pool_title)
                .setMessage(R.string.depot_pool_clear_confirm)
                .setPositiveButton(R.string.depot_pool_clear, (dialog, which) -> {
                    DepotPool.clear();
                    Notify.show(R.string.depot_pool_clear_done);
                    refreshLive();
                    showPool();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }
}
