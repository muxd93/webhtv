package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogConfigBinding;
import com.fongmi.android.tv.impl.ConfigListener;
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

    public ConfigDialog show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
        return this;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogConfigBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected MaterialAlertDialogBuilder listDialog() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog);
    }

    @Override
    protected EditText nameView() {
        return binding.name;
    }

    @Override
    protected EditText urlView() {
        return binding.url;
    }

    @Override
    protected void onConfigSaved(Config config) {
        ((ConfigListener) requireParentFragment()).setConfig(config);
    }

    @Override
    protected void initView() {
        binding.title.setText(getDialogTitle());
        binding.positive.setText(getPositiveText());
        initConfigViews();
        binding.preset.setVisibility(type == 1 && !edit ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onPositive());
        binding.preset.setOnClickListener(this::onPreset);
        binding.choose.setEndIconOnClickListener(this::onChoose);
        initConfigEvents();
        binding.name.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onPositive();
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow();
        binding.url.requestFocus();
    }

    private void configureWindow() {
        if (getDialog() == null || getDialog().getWindow() == null) return;
        Window window = getDialog().getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        boolean land = ResUtil.isLand(requireContext());
        int width = Math.min(Math.round(ResUtil.getScreenWidth(requireContext()) * (land ? 0.58f : 0.92f)), ResUtil.dp2px(560));
        params.width = Math.max(width, ResUtil.dp2px(320));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
    }
}
