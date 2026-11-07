package com.fongmi.android.tv.ui.dialog;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.ConfigLister;
import com.fongmi.android.tv.api.config.SourceRetry;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogHistoryBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.ui.adapter.ConfigAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public class HistoryDialog extends BaseAlertDialog implements ConfigAdapter.OnClickListener {

    private DialogHistoryBinding binding;
    private ConfigAdapter adapter;
    private ConfigListener listener;
    private boolean readOnly;
    private int type;

    public static HistoryDialog create() {
        return new HistoryDialog();
    }

    public HistoryDialog vod() {
        type = 0;
        return this;
    }

    public HistoryDialog live() {
        type = 1;
        return this;
    }

    public HistoryDialog wall() {
        type = 2;
        return this;
    }

    public HistoryDialog readOnly() {
        readOnly = true;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        if (activity instanceof ConfigListener) listener = (ConfigListener) activity;
    }

    public void show(FragmentActivity activity, ConfigListener listener) {
        show(activity.getSupportFragmentManager(), null);
        this.listener = listener;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new ConfigAdapter(this);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.setAdapter(adapter.readOnly(readOnly).addAll(type).onLoaded(this::onRowsLoaded));
    }

    /** 构建在后台完成；空列表沿用旧语义直接关闭，交互重建后把焦点还给对应行 */
    private void onRowsLoaded(List<ConfigLister.Row> rows, int position) {
        if (binding == null) return;
        if (rows.isEmpty()) {
            if (isAdded()) dismiss();
            return;
        }
        if (position >= 0) focusPosition(position);
    }

    @Override
    public void onTextClick(Config item) {
        if (listener != null) listener.setConfig(ConfigLister.hydrate(item));
        dismiss();
    }

    @Override
    public void onDepotToggle(Config item) {
        adapter.toggle(item);
    }

    @Override
    public void onDepotRefresh(Config item) {
        SourceRetry.reset(item);
        listener.setConfig(ConfigLister.hydrate(item));
        dismiss();
    }

    @Override
    public void onDeleteClick(Config item) {
        AlertDialog dialog = builder().setTitle(R.string.config_delete_title).setMessage(getString(item.isDepot() ? R.string.config_delete_depot : R.string.config_delete_single, ConfigLister.childCount(item))).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (d, w) -> adapter.remove(item)).create();
        dialog.show();
        // 破坏性操作默认焦点落「取消」，避免遥控器误按确定
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private void focusPosition(int position) {
        if (position < 0 || binding == null) return;
        binding.recycler.scrollToPosition(position);
        binding.recycler.post(() -> requestFocusAt(position));
    }

    private void requestFocusAt(int position) {
        if (binding == null) return;
        RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            holder.itemView.requestFocus();
            return;
        }
        binding.recycler.post(() -> {
            if (binding == null) return;
            RecyclerView.ViewHolder retry = binding.recycler.findViewHolderForAdapterPosition(position);
            if (retry != null) retry.itemView.requestFocus();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(0.5f);
    }
}
