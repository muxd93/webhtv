package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import android.view.View;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.DepotProbe;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogSourceManagerBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.setting.InterfaceOrderStore;
import com.fongmi.android.tv.source.SourceState;
import com.fongmi.android.tv.ui.adapter.SourceManagerAdapter;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class SourceManagerDialog extends BaseAlertDialog implements SourceManagerAdapter.OnActionListener, ConfigListener {

    private DialogSourceManagerBinding binding;
    private SourceManagerAdapter adapter;
    private ConfigListener configListener;
    /** 类型过滤（SRCUI4 起必选）：独立"数据源管理"总览入口已删除。 */
    private int typeFilter;

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
        adapter = new SourceManagerAdapter(this).type(typeFilter);
        binding.title.setText(typeFilter == 0 ? R.string.source_manager_vod : typeFilter == 1 ? R.string.source_manager_live : R.string.source_manager_wall);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setAdapter(adapter.build());
        binding.scan.setVisibility(View.VISIBLE);
        binding.scan.setOnClickListener(v -> openPushDialog());
    }

    /** 补回电视友好添加方式（扫码推送/文件导入/预设）：过滤态经 ConfigDialog 打开，能力不回退。 */
    private void openPushDialog() {
        ConfigDialog dialog = ConfigDialog.create();
        if (typeFilter == 0) dialog.vod();
        else if (typeFilter == 1) dialog.live();
        else if (typeFilter == 2) dialog.wall();
        dialog.show(this);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(adaptiveWidth());
    }

    /** 竖屏手机放宽到 0.92（≤560dp），固定 0.6 会挤压行内容到不可读；TV/横屏维持 0.6。 */
    private float adaptiveWidth() {
        if (ResUtil.isLand(requireContext())) return 0.6f;
        return Math.min(0.92f, (float) ResUtil.dp2px(560) / ResUtil.getScreenWidth());
    }

    @Override
    public void onSelect(Config item) {
        if (configListener != null) configListener.setConfig(item);
        dismiss();
    }

    /** 作为 ConfigDialog 的宿主片段（mobile onConfigSaved 强转 requireParentFragment）：委托给真实宿主。 */
    @Override
    public void setConfig(Config item) {
        if (configListener != null) configListener.setConfig(item);
    }

    @Override
    public void onToggleEnabled(Config item, boolean enabled) {
        SourceState.setEnabled(item, enabled);
        adapter.notifyItemChanged(item);
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
                        if (!adapter.remove(item)) adapter.rebuild();
                    })
                    .show();
        } else {
            item.delete();
            if (!adapter.remove(item)) adapter.rebuild();
        }
    }

    /** 定向重探：仅探测该仓子源并更新圆点/摘要，不重建整个应用配置、不关闭弹窗。 */
    @Override
    public void onRefresh(Config depot) {
        List<Config> children = Config.getChildren(depot.getUrl(), depot.getType());
        if (children.isEmpty()) return;
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
}
