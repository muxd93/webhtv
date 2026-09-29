package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.databinding.ActivityFileBinding;
import com.fongmi.android.tv.ui.adapter.FileAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.PushId;
import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.VideoFolderUtil;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;

public class FileActivity extends BaseActivity implements FileAdapter.OnClickListener {

    private ActivityFileBinding mBinding;
    private FileAdapter mAdapter;
    private File dir;
    private boolean selectDir;
    private boolean playMode;
    private Future<?> pending;

    private boolean isRoot() {
        return Path.root().equals(dir);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityFileBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        selectDir = getIntent().getBooleanExtra("select_dir", false);
        playMode = getIntent().getBooleanExtra("play_mode", false);
        setRecyclerView();
        checkPermission();
    }

    public boolean canSelectDir() {
        return getIntent().getBooleanExtra("can_select_dir", false);
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        mBinding.recycler.setAdapter(mAdapter = new FileAdapter(this));
    }

    private void checkPermission() {
        File startDir = Path.root();
        String dirPath = getIntent().getStringExtra("dir");
        if (dirPath != null) {
            File customDir = new File(dirPath);
            if (customDir.isDirectory()) startDir = customDir;
        }
        File finalStartDir = startDir;
        PermissionUtil.requestFile(this, allGranted -> update(finalStartDir));
    }

    private void update(File dir) {
        this.dir = dir;
        if (pending != null) pending.cancel(true);
        mBinding.progressLayout.showProgress();
        final File target = dir;
        final Future<?>[] self = new Future<?>[1];
        self[0] = pending = Task.submit(() -> {
            List<File> items = list(target);
            App.post(() -> {
                if (isFinishing() || self[0].isCancelled()) return;
                mAdapter.addAll(this.dir, items, selectDir || canSelectDir());
                mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            });
        });
    }

    private List<File> list(File dir) {
        if (selectDir) {
            File[] files = dir.listFiles(File::isDirectory);
            if (files == null) return new ArrayList<>();
            Path.sort(files);
            return Arrays.asList(files);
        }
        return Path.list(dir);
    }

    @Override
    public void onItemClick(File file) {
        if (file.isDirectory()) {
            update(file);
            return;
        }
        if (playMode) {
            if (VideoFolderUtil.isVideoFile(file.getName())) VideoActivity.push(this, Uri.fromFile(file).toString());
            return;
        }
        setResult(RESULT_OK, new Intent().setData(Uri.fromFile(file)));
        finish();
    }

    /** 播放模式下长按文件夹：递归收集该目录视频，一键连播 */
    @Override
    public boolean onItemLongClick(File file) {
        if (!playMode || !file.isDirectory()) return false;
        playFolder(file);
        return true;
    }

    private void playFolder(File folder) {
        Notify.show(R.string.smb_loading);
        Task.execute(() -> {
            String urls = VideoFolderUtil.buildFolderPlayUrl(folder, isRecursive());
            SpiderDebug.log("push-folder", "build folder=%s recursive=%s urls=%s", folder.getName(), isRecursive(), urls);
            App.post(() -> {
                if (isFinishing()) return;
                if (urls.isEmpty()) {
                    Notify.show(R.string.folder_no_video);
                    return;
                }
                VideoActivity.start(this, SiteApi.PUSH, PushId.folder(folder.getName(), urls), folder.getName(), null);
            });
        });
    }

    private boolean isRecursive() {
        return com.fongmi.android.tv.setting.Setting.isFolderRecursive();
    }

    @Override
    public void onCurrentDirClick(File dir) {
        if (dir == null) return;
        setResult(RESULT_OK, new Intent().setData(Uri.fromFile(dir)));
        finish();
    }

    @Override
    protected void onBackInvoked() {
        if (isRoot()) {
            super.onBackInvoked();
        } else {
            update(dir.getParentFile());
        }
    }
}
