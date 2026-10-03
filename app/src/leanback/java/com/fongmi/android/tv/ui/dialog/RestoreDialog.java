package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogRestoreBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.RestoreAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

public class RestoreDialog extends BaseAlertDialog implements RestoreAdapter.OnClickListener {

    private DialogRestoreBinding binding;
    private RestoreAdapter adapter;
    private Callback callback;

    public static RestoreDialog create() {
        return new RestoreDialog();
    }

    public RestoreDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = LightDialog.create(requireContext(), getString(R.string.restore_select), getBinding().getRoot());
        initView();
        initEvent();
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogRestoreBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new RestoreAdapter(this);
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
    }

    @Override
    public void onItemClick(File item) {
        // 恢复会清空当前全部数据并覆盖，先二次确认
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.restore_select).setMessage(R.string.dialog_restore_confirm).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> restore(item)).show();
    }

    private void restore(File item) {
        BackupProgressDialog progress = BackupProgressDialog.open(getParentFragmentManager(), getString(R.string.restore_progress));
        AppDatabase.restore(item, new Callback() {
            @Override
            public void success() {
                progress.finish();
                if (callback != null) callback.success();
            }

            @Override
            public void error() {
                progress.finish();
                if (callback != null) callback.error();
            }
        }, progress::update);
        dismiss();
    }

    @Override
    public void onDeleteClick(File item) {
        new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.dialog_delete_backup).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> removeBackup(item)).show();
    }

    private void removeBackup(File item) {
        int count = adapter.remove(item);
        if (count == 0) {
            dismiss();
            return;
        }
        if (count > 0) binding.recycler.post(() -> binding.recycler.scrollToPosition(0));
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(0.4f);
    }
}
