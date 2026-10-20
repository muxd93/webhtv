package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogSiteHealthDetailBinding;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.ui.adapter.SiteHealthAdapter;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** 站点健康明细：成功率/耗时/最近错误，点击条目重置该站点数据 */
public class SiteHealthDetailDialog extends BaseAlertDialog {

    private DialogSiteHealthDetailBinding binding;
    private SiteHealthAdapter adapter;
    private boolean abnormalOnly;

    public static void show(Fragment fragment) {
        new SiteHealthDetailDialog().show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity) {
        new SiteHealthDetailDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSiteHealthDetailBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null) return;
        Window window = getDialog().getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (ResUtil.getScreenWidth(requireContext()) * (ResUtil.isLand(requireContext()) ? 0.5f : 0.92f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        binding.filter.requestFocus();
    }

    @Override
    protected void initView() {
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setAdapter(adapter = new SiteHealthAdapter(this::onItemClick));
        refresh();
    }

    @Override
    protected void initEvent() {
        binding.filter.setOnClickListener(v -> {
            abnormalOnly = !abnormalOnly;
            binding.filter.setAlpha(abnormalOnly ? 1.0f : 0.65f);
            refresh();
        });
        binding.negative.setOnClickListener(v -> dismiss());
    }

    private void refresh() {
        List<Site> items = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) {
            if (site == null || site.isHide()) continue;
            SiteHealthStore.Summary summary = SiteHealthStore.summary(site);
            if (summary == null) continue;
            if (abnormalOnly && !summary.abnormal) continue;
            items.add(site);
        }
        adapter.setItems(items);
        binding.count.setText(getString(R.string.site_health_detail_count, items.size()));
        binding.count.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (items.isEmpty()) Notify.show(R.string.site_health_detail_empty);
    }

    private void onItemClick(Site site) {
        SiteHealthStore.reset(site);
        Notify.show(R.string.site_health_reset);
        refresh();
    }
}
