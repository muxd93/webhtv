package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.ActivityHistoryBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.HistoryAdapter;
import com.fongmi.android.tv.ui.dialog.SyncDialog;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.HistoryOpener;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Map;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HistoryActivity extends BaseActivity implements HistoryAdapter.OnClickListener {

    private ActivityHistoryBinding mBinding;
    private HistoryAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        setEvent();
        getHistory();
    }

    private void setEvent() {
        mBinding.clear.setOnClickListener(v -> clearHistory());
        mBinding.sync.setOnClickListener(v -> SyncDialog.create().history().show(this));
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter = new HistoryAdapter(this));
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(Product.getColumn(), 16));
    }

    private int historyEpoch;

    private void getHistory() {
        // X16 聚合读取（跨配置全表扫描）移入后台，避免 HISTORY 事件风暴下主线程 DB 卡顿
        final int epoch = ++historyEpoch;
        Task.submit(() -> {
            boolean aggregated = Setting.isHistoryAggregation();
            List<History> items = aggregated ? History.getAll() : History.get();
            Map<Integer, String> configNames = aggregated ? History.configNameMap() : null;
            App.post(() -> {
                if (epoch != historyEpoch || isFinishing() || isDestroyed()) return;
                if (configNames != null) mAdapter.setConfigNames(configNames);
                mAdapter.setItems(items, () -> {
                    mBinding.count.setText(getString(R.string.keep_count, mAdapter.getItemCount()));
                    mBinding.empty.setVisibility(mAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
                    mBinding.progressLayout.showContent();
                });
            });
        });
    }

    private void clearHistory() {
        if (mAdapter.getItemCount() == 0) return;
        int message = Setting.isHistoryAggregation() ? R.string.dialog_delete_global_history : R.string.dialog_clear_history;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.home_history).setMessage(message).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> doClear()).show();
    }

    private void doClear() {
        Task.submit(() -> {
            if (Setting.isHistoryAggregation()) History.deleteAllAndSync();
            else History.deleteAndSync(VodConfig.getCid());
            App.post(() -> {
                mAdapter.setDelete(false);
                getHistory();
                RefreshEvent.history();
            });
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.HISTORY) getHistory();
    }

    @Override
    public void onItemClick(History item) {
        HistoryOpener.open(this, item);
    }

    @Override
    public void onItemDelete(History item) {
        mAdapter.remove(item.deleteAndSync(), () -> {
            if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
        });
        // 首页历史行需要同步收起/更新
        RefreshEvent.history();
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(!mAdapter.isDelete());
        // 删除模式的退出/清空手势不可见，进入时提示一次
        if (mAdapter.isDelete()) Notify.show(R.string.history_delete_hint);
        return true;
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }
}
