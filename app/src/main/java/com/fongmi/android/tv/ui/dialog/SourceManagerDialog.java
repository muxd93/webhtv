package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogSourceManagerBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.source.SourceState;
import com.fongmi.android.tv.ui.adapter.SourceManagerAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SourceManagerDialog extends BaseAlertDialog implements SourceManagerAdapter.OnActionListener {

    private DialogSourceManagerBinding binding;
    private SourceManagerAdapter adapter;
    private ConfigListener configListener;

    public static SourceManagerDialog create(ConfigListener listener) {
        SourceManagerDialog dialog = new SourceManagerDialog();
        dialog.configListener = listener;
        return dialog;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSourceManagerBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new SourceManagerAdapter(this);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setAdapter(adapter.build());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(0.6f);
    }

    @Override
    public void onSelect(Config item) {
        if (configListener != null) configListener.setConfig(item);
        dismiss();
    }

    @Override
    public void onToggleEnabled(Config item, boolean enabled) {
        SourceState.setEnabled(item, enabled);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onDelete(Config item) {
        if (item.isDepot()) {
            long count = Config.getChildren(item.getUrl(), item.getType()).size();
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(getString(R.string.source_delete_depot_confirm, item.getDesc(), count))
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                        item.delete();
                        adapter.removeGroup(item.getUrl());
                    })
                    .show();
        } else {
            item.delete();
            adapter.remove(item);
        }
    }

    @Override
    public void onReprobe(Config item) {
        if (configListener != null) configListener.setConfig(item);
        dismiss();
    }
}
