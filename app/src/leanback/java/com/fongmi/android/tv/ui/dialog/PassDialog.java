package com.fongmi.android.tv.ui.dialog;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogPassBinding;
import com.fongmi.android.tv.impl.PassListener;
import com.fongmi.android.tv.utils.ResUtil;

public class PassDialog extends BaseBottomSheetDialog {

    private DialogPassBinding binding;

    public static PassDialog create() {
        return new PassDialog();
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof PassDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogPassBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onPass);
        binding.pass.setOnEditorActionListener(this::onDone);
    }

    private void onPass(View view) {
        String pass = binding.pass.getText().toString().trim();
        if (!pass.isEmpty()) ((PassListener) requireActivity()).setPass(pass);
        dismiss();
    }

    private boolean onDone(TextView view, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_DONE) binding.positive.performClick();
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 底栏宽度随屏幕比例缩放，避免在高分辨率电视上写死 250dp 显得过窄
        int width = Math.max(ResUtil.dp2px(250), Math.round(ResUtil.getScreenWidth(requireContext()) * 0.32f));
        getDialog().getWindow().setLayout(width, -1);
    }
}
