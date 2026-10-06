package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogConfigBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ConfigDialog extends BaseConfigDialog {

    private DialogConfigBinding binding;

    public static ConfigDialog create() {
        return new ConfigDialog();
    }

    @Override
    public ConfigDialog vod() {
        return (ConfigDialog) super.vod();
    }

    @Override
    public ConfigDialog live() {
        return (ConfigDialog) super.live();
    }

    @Override
    public ConfigDialog wall() {
        return (ConfigDialog) super.wall();
    }

    @Override
    public ConfigDialog edit() {
        return (ConfigDialog) super.edit();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    /** 供其他弹窗片段（如 SourceManagerDialog）宿主打开；与 mobile 版 show(Fragment) 对齐。 */
    public void show(Fragment host) {
        show(host.getChildFragmentManager(), null);
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = LightDialog.create(requireContext(), getDialogTitle(), getBinding().getRoot());
        initView();
        initEvent();
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogConfigBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected TextView nameView() {
        return binding.name;
    }

    @Override
    protected EditText urlView() {
        return binding.text;
    }

    @Override
    protected void onConfigSaved(Config config) {
        ((ConfigListener) requireActivity()).setConfig(config);
    }

    @Override
    protected void initView() {
        initConfigViews();
        binding.positive.setText(getPositiveText());
        binding.preset.setVisibility(type == 1 && !edit ? View.VISIBLE : View.GONE);
        binding.code.setImageBitmap(QRCode.getLightBitmap(Server.get().getAddress(4), 200, 0));
        binding.info.setText(ResUtil.getString(R.string.push_info, Server.get().getAddress()).replace("\uff0c", "\n"));
    }

    @Override
    protected void initEvent() {
        binding.choose.setOnClickListener(this::onChoose);
        binding.preset.setOnClickListener(this::onPreset);
        binding.positive.setOnClickListener(v -> onPositive());
        binding.negative.setOnClickListener(v -> dismiss());
        initConfigEvents();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.6f);
    }
}
