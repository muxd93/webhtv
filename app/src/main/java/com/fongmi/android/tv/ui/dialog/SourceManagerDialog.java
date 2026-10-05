package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import android.text.TextUtils;
import android.widget.EditText;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.DepotProbe;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogSourceManagerBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.setting.InterfaceOrderStore;
import com.fongmi.android.tv.source.SourceState;
import com.fongmi.android.tv.ui.adapter.SourceManagerAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

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
        binding.add.setOnClickListener(v -> onAddSubscription());
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
                        adapter.rebuild();
                    })
                    .show();
        } else {
            item.delete();
            adapter.rebuild();
        }
    }

    /** 定向重探：仅探测该仓子源并更新圆点/摘要，不重建整个应用配置、不关闭弹窗。 */
    @Override
    public void onRefresh(Config depot) {
        List<Config> children = Config.getChildren(depot.getUrl(), depot.getType());
        adapter.setProbeState(depot.getUrl(), children.size());
        adapter.notifyHeaderChanged(depot.getUrl());
        DepotProbe.probeAsync(children, new DepotProbe.ProbeCallback() {
            @Override
            public void onProgress(int done, int total) {
                if (!isAdded()) return;
                adapter.setProbeProgress(depot.getUrl(), done, total);
                adapter.notifyHeaderChanged(depot.getUrl());
            }

            @Override
            public void onComplete(List<DepotProbe.Result> results) {
                if (!isAdded()) return;
                if (depot.getType() == 0) {
                    long now = System.currentTimeMillis();
                    List<InterfaceOrderStore.HealthSample> samples = new ArrayList<>();
                    for (DepotProbe.Result r : results) {
                        samples.add(new InterfaceOrderStore.HealthSample(r.url, r.ok, now, r.latency));
                    }
                    InterfaceOrderStore.recordVodHealth(samples);
                }
                adapter.setProbeResult(depot.getUrl(), results);
                adapter.notifyHeaderChanged(depot.getUrl());
                adapter.notifyChildrenChanged(depot.getUrl());
            }
        });
    }

    /** 弹窗内添加订阅：输入 URL（仓/单源皆可），落库后重建列表。 */
    private void onAddSubscription() {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setHint(R.string.source_add_hint);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.source_add_subscription)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (TextUtils.isEmpty(url)) return;
                    Config item = new Config().type(0).url(url);
                    if (TextUtils.isEmpty(item.getName())) item.name(url);
                    item.insert();
                    adapter.rebuild();
                })
                .show();
    }
}
