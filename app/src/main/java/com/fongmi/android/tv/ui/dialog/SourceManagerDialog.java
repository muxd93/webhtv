package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import android.text.TextUtils;
import android.widget.EditText;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.DepotProbe;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.databinding.DialogSourceManagerBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.setting.InterfaceOrderStore;
import com.fongmi.android.tv.source.SourceState;
import com.fongmi.android.tv.ui.adapter.SourceManagerAdapter;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class SourceManagerDialog extends BaseAlertDialog implements SourceManagerAdapter.OnActionListener {

    private DialogSourceManagerBinding binding;
    private SourceManagerAdapter adapter;
    private ConfigListener configListener;
    /** 类型过滤（SRCUI2/P5）：-1=全部总览；>=0 时只显示该类型并沿用"添加即切换"语义。 */
    private int typeFilter = -1;

    public static SourceManagerDialog create(ConfigListener listener) {
        SourceManagerDialog dialog = new SourceManagerDialog();
        dialog.configListener = listener;
        return dialog;
    }

    public SourceManagerDialog type(int filter) {
        typeFilter = filter;
        return this;
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
        if (typeFilter >= 0) {
            adapter.type(typeFilter);
            binding.title.setText(typeFilter == 0 ? R.string.source_manager_vod : typeFilter == 1 ? R.string.source_manager_live : R.string.source_manager_wall);
        }
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
        adapter.setProbeState(depot, children.size());
        adapter.notifyHeaderChanged(depot);
        DepotProbe.probeAsync(children, new DepotProbe.ProbeCallback() {
            @Override
            public void onProgress(int done, int total) {
                if (!isAdded()) return;
                adapter.setProbeProgress(depot, done, total);
                adapter.notifyHeaderChanged(depot);
            }

            @Override
            public void onComplete(List<DepotProbe.Result> results) {
                if (!isAdded()) return;
                if (depot.getType() == 0) {
                    InterfaceOrderStore.recordVodHealth(samples(results));
                } else if (depot.getType() == 1) {
                    InterfaceOrderStore.recordLiveHealth(samples(results));
                }
                adapter.setProbeResult(depot, results);
                adapter.notifyHeaderChanged(depot);
                adapter.notifyChildrenChanged(depot);
            }
        });
    }

    private List<InterfaceOrderStore.HealthSample> samples(List<DepotProbe.Result> results) {
        long now = System.currentTimeMillis();
        List<InterfaceOrderStore.HealthSample> samples = new ArrayList<>();
        for (DepotProbe.Result r : results) samples.add(new InterfaceOrderStore.HealthSample(r.url, r.ok, now, r.latency));
        return samples;
    }

    /** 弹窗内添加订阅：输入 URL（仓/单源皆可），落库后重建列表，并后台识别仓格式。 */
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
                    Config item = new Config().type(typeFilter >= 0 ? typeFilter : 0).url(url);
                    if (TextUtils.isEmpty(item.getName())) item.name(url);
                    item.insert();
                    adapter.rebuild();
                    probeDepotFormat(item);
                    // 类型过滤态沿用 ConfigDialog"添加即切换"语义；总览态保持只收藏
                    if (typeFilter >= 0) onSelect(item);
                })
                .show();
    }

    /**
     * 添加后后台识别仓格式：返回 JSON 含 urls 即打 depot 标记，列表即时以"仓（未激活）"组呈现；
     * 子源仍留到激活时由 expandDepot 展开，语义与 VodConfig/LiveConfig.checkJson 一致。
     */
    private void probeDepotFormat(Config item) {
        Task.submit(() -> {
            boolean depot = false;
            try {
                JsonObject object = Json.parse(OkHttp.string(UrlUtil.convert(item.getUrl()))).getAsJsonObject();
                depot = object.has("urls");
            } catch (Throwable ignored) {
            }
            if (!depot) return;
            Config saved = AppDatabase.get().getConfigDao().find(item.getUrl(), item.getType());
            if (saved == null || saved.isDepot()) return;
            saved.depot(true).save();
            App.post(() -> {
                if (isAdded()) adapter.rebuild();
            });
        });
    }
}
