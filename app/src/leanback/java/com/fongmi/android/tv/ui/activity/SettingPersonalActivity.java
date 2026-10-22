package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingPersonalBinding;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.DownloadSettingsDialog;
import com.fongmi.android.tv.utils.Task;

public class SettingPersonalActivity extends BaseActivity {

    private ActivitySettingPersonalBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingPersonalActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingPersonalBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        refreshTexts();
    }

    @Override
    protected void initEvent() {
        mBinding.autoNext.setOnClickListener(this::setAutoNext);
        mBinding.doubleBack.setOnClickListener(this::setDoubleBack);
        mBinding.powerSave.setOnClickListener(this::setPowerSave);
        mBinding.globalHistory.setOnClickListener(this::setGlobalHistory);
        mBinding.history.setOnClickListener(this::onHistory);
        mBinding.searchThreads.setOnClickListener(this::setSearchThreads);
        mBinding.startupPage.setOnClickListener(this::setStartupPage);
        mBinding.download.setOnClickListener(view -> DownloadSettingsDialog.show(this));
    }

    private void refreshTexts() {
        mBinding.autoNextText.setText(getSwitch(Setting.isAutoNextEps()));
        mBinding.doubleBackText.setText(getSwitch(Setting.isDoubleBackExit()));
        mBinding.powerSaveText.setText(getSwitch(Setting.isPowerSaveStartup()));
        mBinding.globalHistoryText.setText(getGlobalHistoryModeText());
        mBinding.searchThreadsText.setText(String.valueOf(Setting.getSearchThreads()));
        mBinding.startupPageText.setText(getStartupPageLabel());
        mBinding.downloadText.setText(DownloadManager.summary(this));
    }

    private int getStartupPageLabel() {
        int page = Setting.getStartupPage();
        if (page == Setting.STARTUP_LIVE) return R.string.startup_page_live;
        if (page == Setting.STARTUP_HISTORY) return R.string.startup_page_history;
        if (page == Setting.STARTUP_KEEP) return R.string.startup_page_keep;
        return R.string.startup_page_home;
    }

    private String getGlobalHistoryModeText() {
        int mode = Setting.getGlobalHistoryMode();
        if (mode == Setting.GLOBAL_HISTORY_AUTO) return getString(R.string.global_history_auto);
        if (mode == Setting.GLOBAL_HISTORY_SEARCH) return getString(R.string.global_history_search);
        return getString(R.string.setting_off);
    }

    private void setAutoNext(View view) {
        Setting.putAutoNextEps(!Setting.isAutoNextEps());
        mBinding.autoNextText.setText(getSwitch(Setting.isAutoNextEps()));
    }

    private void setDoubleBack(View view) {
        Setting.putDoubleBackExit(!Setting.isDoubleBackExit());
        mBinding.doubleBackText.setText(getSwitch(Setting.isDoubleBackExit()));
    }

    private void setPowerSave(View view) {
        Setting.putPowerSaveStartup(!Setting.isPowerSaveStartup());
        mBinding.powerSaveText.setText(getSwitch(Setting.isPowerSaveStartup()));
    }

    private void setGlobalHistory(View view) {
        Setting.putGlobalHistoryMode(Setting.nextGlobalHistoryMode());
        mBinding.globalHistoryText.setText(getGlobalHistoryModeText());
        // 模式变化影响主页历史/收藏行的聚合范围，通知主页重载
        RefreshEvent.history();
    }

    private void onHistory(View view) {
        HistoryActivity.start(this);
    }

    private void setSearchThreads(View view) {
        int threads = Setting.nextSearchThreads();
        Setting.putSearchThreads(threads);
        // 立即调整共享搜索池，下一次搜索即按新线程数执行
        Task.newSearchExecutor(threads);
        mBinding.searchThreadsText.setText(String.valueOf(threads));
    }

    private void setStartupPage(View view) {
        Setting.putStartupPage(Setting.nextStartupPage());
        mBinding.startupPageText.setText(getStartupPageLabel());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从缓存设置弹窗/下载管理返回后刷新摘要
        mBinding.downloadText.setText(DownloadManager.summary(this));
    }
}
