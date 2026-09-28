package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.databinding.ActivityFileBinding;
import com.fongmi.android.tv.ui.adapter.FileAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Task;
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
        } else {
            setResult(RESULT_OK, new Intent().setData(Uri.fromFile(file)));
            finish();
        }
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
