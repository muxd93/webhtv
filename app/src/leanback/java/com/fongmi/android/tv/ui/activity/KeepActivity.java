package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.ActivityKeepBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.KeepAdapter;
import com.fongmi.android.tv.ui.dialog.SyncDialog;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class KeepActivity extends BaseActivity implements KeepAdapter.OnClickListener {

    private ActivityKeepBinding mBinding;
    private KeepAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        setEvent();
        getKeep();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter = new KeepAdapter(this));
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(Product.getColumn(), 16));
    }

    private void setEvent() {
        mBinding.clear.setOnClickListener(v -> clearKeep());
        mBinding.search.setOnClickListener(v -> SearchActivity.start(this));
        mBinding.sync.setOnClickListener(v -> SyncDialog.create().keep().show(this));
    }

    private void getKeep() {
        mAdapter.setItems(Keep.getVod(), this::showResult);
    }

    private void showResult() {
        int count = mAdapter.getItemCount();
        mBinding.count.setText(getString(R.string.keep_count, count));
        mBinding.empty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        mBinding.progressLayout.showContent();
    }

    private void clearKeep() {
        if (mAdapter.getItemCount() == 0) return;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.home_keep_row).setMessage(R.string.dialog_clear_keep).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> doClearKeep()).show();
    }

    private void doClearKeep() {
        // 只清视频收藏，不影响直播收藏
        Keep.getVod().forEach(Keep::delete);
        getKeep();
        RefreshEvent.keep();
    }

    private void loadConfig(Config config, Keep item) {
        Notify.show(getString(R.string.keep_switching, config.getName()));
        mBinding.progressLayout.showProgress();
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                mBinding.progressLayout.showContent();
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                mBinding.progressLayout.showContent();
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.KEEP) getKeep();
    }

    @Override
    public void onItemClick(Keep item) {
        Config config = Config.find(item.getCid());
        if (config == null) {
            Notify.show(R.string.keep_site_missing);
            CollectActivity.start(this, item.getVodName());
        } else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(Keep item) {
        mAdapter.remove(item.delete(), () -> {
            if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
        });
        // 首页收藏行需要同步收起/更新
        RefreshEvent.keep();
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(true);
        return true;
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }
}
