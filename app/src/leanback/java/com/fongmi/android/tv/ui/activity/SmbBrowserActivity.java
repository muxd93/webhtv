package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.SmbServer;
import com.fongmi.android.tv.databinding.ActivitySmbBrowserBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.SmbFileAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PushId;
import com.fongmi.android.tv.utils.SmbHelper;
import com.fongmi.android.tv.utils.Task;

import java.util.List;

public class SmbBrowserActivity extends BaseActivity implements SmbFileAdapter.OnClickListener {

    public static final String EXTRA_SERVER_ID = "server_id";
    public static final String EXTRA_SMB_URL = "smb_url";
    public static final String EXTRA_SMB_NAME = "smb_name";
    public static final String EXTRA_SMB_SERVER_ID = "smb_server_id";
    public static final String EXTRA_SMB_IS_DIR = "smb_is_dir";
    public static final String EXTRA_PLAY_MODE = "play_mode";

    private ActivitySmbBrowserBinding binding;
    private SmbFileAdapter adapter;
    private SmbServer server;
    private String currentPath = "";
    private int loadVersion;
    private boolean playMode;

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivitySmbBrowserBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        String serverId = getIntent().getStringExtra(EXTRA_SERVER_ID);
        if (serverId == null) {
            finish();
            return;
        }
        server = AppDatabase.get().getSmbServerDao().find(serverId);
        if (server == null) {
            finish();
            return;
        }
        playMode = getIntent().getBooleanExtra(EXTRA_PLAY_MODE, false);
        setRecyclerView();
        String displayName = server.getName() != null && !server.getName().isEmpty() ? server.getName() : server.getHost();
        binding.title.setText(displayName + " /" + server.getShareName());
        loadDirectory("");
    }

    private void setRecyclerView() {
        adapter = new SmbFileAdapter(this);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        binding.recycler.setAdapter(adapter);
    }

    private void loadDirectory(String path) {
        currentPath = path != null ? path : "";
        binding.selectDir.setVisibility(currentPath.isEmpty() || playMode ? View.GONE : View.VISIBLE);
        binding.progressLayout.showProgress();
        int version = ++loadVersion;
        String loadPath = currentPath;
        Task.execute(() -> {
            List<SmbHelper.SmbFileItem> files = SmbHelper.listFiles(server, loadPath);
            App.post(() -> {
                if (version != loadVersion) return;
                adapter.setItems(files);
                binding.progressLayout.showContent(true, adapter.getItemCount());
                updateTitle();
            });
        });
    }

    private void updateTitle() {
        StringBuilder sb = new StringBuilder();
        sb.append(server.getName() != null && !server.getName().isEmpty() ? server.getName() : server.getHost());
        sb.append(" /").append(server.getShareName());
        if (!currentPath.isEmpty()) {
            String display = currentPath.replace('\\', '/');
            sb.append(display);
        }
        binding.title.setText(sb.toString());
    }

    @Override
    public void onItemClick(SmbHelper.SmbFileItem item) {
        if (item.isDirectory()) {
            String newPath = currentPath.isEmpty() ? item.getName() : currentPath + "\\" + item.getName();
            loadDirectory(newPath);
            return;
        }
        String smbUrl = SmbHelper.getSmbUrl(server, currentPath.isEmpty() ? item.getPath() : currentPath + "\\" + item.getName());
        if (playMode) VideoActivity.push(this, smbUrl);
        else returnResult(smbUrl, item.getName(), false);
    }

    /** 播放模式下长按文件夹：递归收集该目录视频，一键连播 */
    @Override
    public boolean onItemLongClick(SmbHelper.SmbFileItem item) {
        if (!playMode || !item.isDirectory()) return false;
        String dirPath = currentPath.isEmpty() ? item.getName() : currentPath + "\\" + item.getName();
        playSmbFolder(dirPath, item.getName());
        return true;
    }

    private void playSmbFolder(String dirPath, String dirName) {
        Notify.show(R.string.smb_loading);
        Task.execute(() -> {
            String urls = SmbHelper.buildFolderPlayUrls(server, dirPath, Setting.isFolderRecursive());
            App.post(() -> {
                if (isFinishing()) return;
                if (urls.isEmpty()) {
                    Toast.makeText(this, R.string.folder_no_video, Toast.LENGTH_SHORT).show();
                    return;
                }
                VideoActivity.start(this, SiteApi.PUSH, PushId.folder(dirName, urls), dirName, null);
            });
        });
    }

    @Override
    protected void initEvent() {
        binding.selectDir.setOnClickListener(v -> {
            String smbUrl = SmbHelper.getSmbUrl(server, currentPath);
            int sep = currentPath.lastIndexOf('\\');
            String dirName = currentPath.isEmpty() ? server.getShareName() : (sep >= 0 ? currentPath.substring(sep + 1) : currentPath);
            returnResult(smbUrl, dirName, true);
        });
    }

    private void returnResult(String url, String name, boolean isDir) {
        Intent data = new Intent();
        data.putExtra(EXTRA_SMB_URL, url);
        data.putExtra(EXTRA_SMB_NAME, name);
        data.putExtra(EXTRA_SMB_SERVER_ID, server.getId());
        data.putExtra(EXTRA_SMB_IS_DIR, isDir);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onBackInvoked() {
        if (currentPath.isEmpty()) {
            setResult(RESULT_CANCELED);
            super.onBackInvoked();
        } else {
            String parentPath = "";
            int lastSep = currentPath.lastIndexOf('\\');
            if (lastSep > 0) {
                parentPath = currentPath.substring(0, lastSep);
            }
            loadDirectory(parentPath);
        }
    }
}
