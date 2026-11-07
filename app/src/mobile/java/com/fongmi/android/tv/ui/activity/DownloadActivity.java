package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.DownloadTask;
import com.fongmi.android.tv.databinding.ActivityDownloadBinding;
import com.fongmi.android.tv.download.DownloadEvent;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.adapter.DownloadAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class DownloadActivity extends BaseActivity implements DownloadAdapter.OnClickListener {

    private ActivityDownloadBinding mBinding;
    private DownloadAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DownloadActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDownloadBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        getTask();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recycler.setAdapter(mAdapter = new DownloadAdapter(this));
    }

    private void getTask() {
        mAdapter.setItems(DownloadManager.tasks(), () -> mBinding.progressLayout.showContent(true, mAdapter.getItemCount()));
    }

    @Override
    public void onItemClick(DownloadTask task) {
        // 已完成 → 进详情页（本地命中会直拨）；排队中/等待网络 → 立即重试调度；运行中 → 暂停；已暂停/失败 → 恢复/重试
        if (task.isCompleted()) openVod(task);
        else if (task.canAutoResume() || task.getState() == DownloadTask.STATE_QUEUED) DownloadManager.retrySchedule();
        else if (task.isRunning()) DownloadManager.pause(task);
        else if (task.canResume()) DownloadManager.resume(task);
    }

    private void openVod(DownloadTask task) {
        Config config = Config.find(task.getCid());
        if (config == null) {
            // 源已删除但本地缓存还在：用本地文件直接起播，不再因源缺失拒绝播放
            String url = DownloadManager.localUrl(task);
            if (url.isEmpty()) Notify.show(R.string.download_source_missing);
            else VideoActivity.start(this, url);
            return;
        }
        if (task.getCid() != VodConfig.getCid()) {
            VodConfig.load(config, new Callback() {

                @Override
                public void success() {
                    VideoActivity.start(DownloadActivity.this, task.getSiteKey(), task.getVodId(), task.getVodName(), task.getVodPic());
                }

                @Override
                public void error(String msg) {
                    Notify.show(msg);
                }
            });
        } else {
            VideoActivity.start(this, task.getSiteKey(), task.getVodId(), task.getVodName(), task.getVodPic());
        }
    }

    @Override
    public void onItemDelete(DownloadTask task) {
        DownloadManager.delete(task);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        for (int i = 0; i < mAdapter.getItemCount(); i++) {
            if (mAdapter.getItem(i).getId().equals(event.getId())) {
                mAdapter.notifyItemChanged(i);
                return;
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.DOWNLOAD) getTask();
    }
}
