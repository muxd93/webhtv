package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogDownloadSettingsBinding;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.download.DownloadPath;
import com.fongmi.android.tv.download.DownloadStore;
import com.fongmi.android.tv.ui.activity.DownloadActivity;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class DownloadSettingsDialog extends BaseAlertDialog {

    private static final long[] CAPACITIES = {5L << 30, 10L << 30, 20L << 30, 50L << 30, 0};

    private DialogDownloadSettingsBinding binding;

    public static DownloadSettingsDialog create() {
        return new DownloadSettingsDialog();
    }

    public static void show(androidx.fragment.app.FragmentActivity activity) {
        for (androidx.fragment.app.Fragment f : activity.getSupportFragmentManager().getFragments()) {
            if (f instanceof DownloadSettingsDialog) return;
        }
        new DownloadSettingsDialog().show(activity.getSupportFragmentManager(), null);
    }

    public static void show(androidx.fragment.app.Fragment fragment) {
        show(fragment.requireActivity());
    }

    @Override
    protected DialogDownloadSettingsBinding getBinding() {
        return binding = DialogDownloadSettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        // 复用全局浅色对话框主题（与 CustomCspDialog 等一致）：白底圆角 + 深色文字 + 主题化按钮
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setTitle(R.string.download_title).setView(getBinding().getRoot())
                .setPositiveButton(R.string.dialog_positive, null);
    }

    @Override
    protected void initView() {
        binding.wifiOnly.setChecked(DownloadManager.isWifiOnly());
        refreshTexts();
    }

    @Override
    protected void initEvent() {
        binding.wifiOnly.setOnCheckedChangeListener((button, checked) -> {
            DownloadManager.setWifiOnly(checked);
            com.fongmi.android.tv.download.DownloadService.start(requireContext());
        });
        binding.concurrent.setOnClickListener(view -> {
            int next = DownloadManager.maxConcurrent() % 3 + 1;
            DownloadManager.setMaxConcurrent(next);
            refreshTexts();
            com.fongmi.android.tv.download.DownloadService.start(requireContext());
        });
        binding.capacity.setOnClickListener(view -> {
            setCapacity(nextCapacity(DownloadManager.capacityBytes()));
        });
        binding.manage.setOnClickListener(view -> {
            dismiss();
            DownloadActivity.start(requireActivity());
        });
        binding.clear.setOnClickListener(view -> confirmClear());
        binding.permission.setOnClickListener(view -> DownloadPath.openPermissionSettings(requireContext()));
        binding.permission.setVisibility(DownloadPath.writable() ? View.GONE : View.VISIBLE);
    }

    private void setCapacity(long value) {
        DownloadManager.setCapacityBytes(value);
        refreshTexts();
    }

    private void refreshTexts() {
        binding.concurrent.setText(String.valueOf(DownloadManager.maxConcurrent()));
        binding.capacity.setText(capacityText(DownloadManager.capacityBytes()));
        long used = DownloadStore.totalBytes();
        long cap = DownloadManager.capacityBytes();
        binding.usage.setText(getString(R.string.download_setting_usage, FileUtil.byteCountToDisplaySize(used), cap > 0 ? FileUtil.byteCountToDisplaySize(cap) : getString(R.string.download_capacity_unlimited)));
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.download_setting_clear)
                .setMessage(R.string.download_delete_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                    DownloadManager.clearAll();
                    dismiss();
                }).show();
    }

    private String capacityText(long bytes) {
        if (bytes <= 0) return getString(R.string.download_capacity_unlimited);
        return FileUtil.byteCountToDisplaySize(bytes);
    }

    private long nextCapacity(long current) {
        for (long value : CAPACITIES) if (value > current) return value;
        return CAPACITIES[0];
    }
}
