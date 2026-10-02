package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogPoolBinding;
import com.fongmi.android.tv.live.LiveAggregator;
import com.fongmi.android.tv.live.LiveProbe;
import com.fongmi.android.tv.ui.adapter.PoolAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Json;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LivePoolDialog extends BaseAlertDialog {

    private DialogPoolBinding binding;
    private PoolAdapter adapter;

    public static LivePoolDialog create() {
        return new LivePoolDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = LightDialog.create(requireContext(), getString(R.string.pool_manage), getBinding().getRoot());
        initView();
        initEvent();
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogPoolBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    private final PoolAdapter.OnClickListener listener = new PoolAdapter.OnClickListener() {

        @Override
        public void onSourceClick(LiveAggregator.SourceInfo item) {
            apply(() -> LiveAggregator.setEnabled(item.url, !item.enabled));
        }

        @Override
        public void onMove(LiveAggregator.SourceInfo item, int delta) {
            apply(() -> LiveAggregator.move(item.url, delta));
        }

        @Override
        public void onRemove(LiveAggregator.SourceInfo item) {
            apply(() -> LiveAggregator.removeSource(item.url));
        }

        @Override
        public void onRevive(LiveAggregator.QuarantineInfo item) {
            apply(() -> LiveAggregator.revive(item.group, item.key));
        }
    };

    protected void initView() {
        adapter = new PoolAdapter(listener);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        ViewGroup.LayoutParams params = binding.recycler.getLayoutParams();
        params.height = ResUtil.dp2px(320);
        binding.recycler.setLayoutParams(params);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 10));
        binding.recycler.setAdapter(adapter);
        loadData();
    }

    protected void initEvent() {
        binding.add.setOnClickListener(this::onAdd);
        binding.update.setOnClickListener(v -> reaggregate());
        binding.detect.setOnClickListener(v -> LiveProbe.toggle());
    }

    private void loadData() {
        JsonObject agg = LiveAggregator.aggInfo();
        if (agg == null) binding.status.setText(R.string.pool_none);
        else binding.status.setText(getString(R.string.pool_last_agg, time(agg), safeInt(agg, "channels"), safeInt(agg, "lines")));
        adapter.setItems(LiveAggregator.poolStatus(), LiveAggregator.quarantineList());
        binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private int safeInt(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : 0;
    }

    private String time(JsonObject agg) {
        long ts = agg.has("ts") ? agg.get("ts").getAsLong() : 0;
        return ts <= 0 ? "-" : new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    /** 池操作统一入口：改 meta → 立即刷新列表 → 后台重聚合（变化且聚合激活才重载）。 */
    private void apply(Runnable action) {
        action.run();
        loadData();
        reaggregate();
    }

    private void reaggregate() {
        Task.submit(() -> {
            boolean changed;
            try {
                changed = LiveAggregator.aggregate(true);
            } catch (Throwable e) {
                return;
            }
            if (changed && LiveAggregator.isAggregate(LiveConfig.get().getConfig())) App.post(() -> LiveConfig.get().load());
            App.post(this::loadData);
        });
    }

    private void onAdd(View view) {
        Context context = requireContext();
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = ResUtil.dp2px(20);
        container.setPadding(pad, ResUtil.dp2px(8), pad, 0);
        EditText url = new EditText(context);
        url.setSingleLine(true);
        url.setHint(R.string.pool_input_hint);
        EditText name = new EditText(context);
        name.setSingleLine(true);
        name.setHint(R.string.pool_input_name);
        container.addView(url);
        container.addView(name);
        new MaterialAlertDialogBuilder(context).setTitle(R.string.pool_add).setView(container).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (d, w) -> {
            String target = url.getText().toString().trim();
            if (TextUtils.isEmpty(target)) return;
            Config config = Config.find(target, 1);
            String text = name.getText().toString().trim();
            if (!TextUtils.isEmpty(text)) config.name(text);
            else if (TextUtils.isEmpty(config.getName())) config.name(UrlUtil.getName(target));
            LiveAggregator.addSource(config.update());
            apply(() -> {
            });
        }).show();
    }

    private final LiveProbe.ProgressListener probeListener = new LiveProbe.ProgressListener() {

        @Override
        public void onProgress(int done, int total) {
            if (binding == null) return;
            binding.progress.setVisibility(View.VISIBLE);
            binding.progress.setText(getString(R.string.live_probe_progress, done, total));
        }

        @Override
        public void onFinished(int results, int ok) {
            if (binding == null) return;
            binding.progress.setVisibility(View.GONE);
        }
    };

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.55f);
        LiveProbe.setProgressListener(probeListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        LiveProbe.setProgressListener(null);
    }
}
