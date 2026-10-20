package com.fongmi.android.tv.ui.adapter;

import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterSiteBinding;
import com.fongmi.android.tv.databinding.AdapterSiteSwitchBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteBlockSetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.ViewHolder> {

    private static final int TYPE_SWITCH = 0;
    private static final int TYPE_ACTION = 1;

    private final OnClickListener listener;
    private final List<Site> allItems;
    private final List<Site> mItems;
    private final long adapterStart;
    private boolean firstBindLogged;
    private boolean block;
    private int displayLimit = Integer.MAX_VALUE;
    private int type;

    public SiteAdapter(OnClickListener listener) {
        this.adapterStart = System.currentTimeMillis();
        this.listener = listener;
        this.allItems = new ArrayList<>();
        this.mItems = new ArrayList<>();
        setHasStableIds(true);
        this.addAll();
        log("created total=%sms items=%s", cost(), mItems.size());
    }

    public interface OnClickListener {

        void onItemClick(Site item);

        void onBlockChanged();
    }

    public void setType(int type) {
        int oldViewType = getViewType();
        this.type = type;
        if (oldViewType != getViewType()) notifyDataSetChanged();
        else notifyItemRangeChanged(0, getItemCount());
    }

    public void filter(String keyword) {
        String text = keyword == null ? "" : keyword.trim().toLowerCase(Locale.getDefault());
        mItems.clear();
        for (Site site : SiteBlockSetting.filter(allItems, block)) if (text.isEmpty() || site.getName().toLowerCase(Locale.getDefault()).contains(text) || site.getKey().toLowerCase(Locale.getDefault()).contains(text)) mItems.add(site);
        displayLimit = Integer.MAX_VALUE;
        notifyDataSetChanged();
    }

    /** 屏蔽视图开关：开启后显示被屏蔽站点（置灰），点击可取消屏蔽 */
    public void setBlock(boolean block) {
        this.block = block;
        applyBlock();
    }

    private void applyBlock() {
        mItems.clear();
        mItems.addAll(SiteBlockSetting.filter(allItems, block));
        if (Setting.isSiteHealthDialogSort()) SiteHealthStore.sortSites(mItems);
        displayLimit = Integer.MAX_VALUE;
        notifyDataSetChanged();
    }

    public void selectAll() {
        setEnable(type != 3);
    }

    public void cancelAll() {
        setEnable(type == 3);
    }

    private void addAll() {
        long collectStart = System.currentTimeMillis();
        for (Site site : VodConfig.get().getSites()) if (!site.isHide()) allItems.add(site);
        log("collect sites cost=%sms visible=%s", cost(collectStart), allItems.size());
        mItems.addAll(SiteBlockSetting.filter(allItems, block));
        if (Setting.isSiteHealthDialogSort()) {
            long sortStart = System.currentTimeMillis();
            SiteHealthStore.sortSites(mItems);
            log("health sort cost=%sms visible=%s", cost(sortStart), mItems.size());
        }
    }

    public List<Site> getItems() {
        return mItems;
    }

    public int getTotalCount() {
        return mItems.size();
    }

    public void setDisplayLimit(int displayLimit) {
        this.displayLimit = Math.max(1, displayLimit);
    }

    public void showAll() {
        int before = getItemCount();
        displayLimit = Integer.MAX_VALUE;
        int after = getItemCount();
        if (after > before) notifyItemRangeInserted(before, after - before);
    }

    @Override
    public int getItemCount() {
        return Math.min(mItems.size(), displayLimit);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= mItems.size()) return RecyclerView.NO_ID;
        String key = mItems.get(position).getKey();
        return key == null ? position : key.hashCode();
    }

    @Override
    public int getItemViewType(int position) {
        return getViewType();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return viewType == TYPE_ACTION ? new ViewHolder(AdapterSiteBinding.inflate(inflater, parent, false)) : new ViewHolder(AdapterSiteSwitchBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = mItems.get(position);
        if (!firstBindLogged) {
            firstBindLogged = true;
            log("first bind position=%s name=%s total=%sms", position, item.getName(), cost());
        }
        holder.bind(item);
    }

    private boolean getChecked(Site item) {
        if (type == 1) return item.isSearchable();
        if (type == 2) return item.isChangeable();
        return false;
    }

    private void setListener(Site item, int position) {
        if (type == 0 && block) {
            SiteBlockSetting.toggle(item);
            applyBlock();
            listener.onBlockChanged();
            return;
        }
        if (type == 0) listener.onItemClick(item);
        if (type == 1) item.setSearchable(!item.isSearchable()).save();
        if (type == 2) item.setChangeable(!item.isChangeable()).save();
        if (type != 0) notifyItemChanged(position);
    }

    private boolean setLongListener(Site item) {
        if (type == 1) setEnable(!item.isSearchable());
        if (type == 2) setEnable(!item.isChangeable());
        return true;
    }

    private void setEnable(boolean enable) {
        if (type == 1) for (Site site : mItems) site.setSearchable(enable).save();
        if (type == 2) for (Site site : mItems) site.setChangeable(enable).save();
        notifyItemRangeChanged(0, getItemCount());
    }

    private int getViewType() {
        return type == 0 ? TYPE_SWITCH : TYPE_ACTION;
    }

    private long cost() {
        return cost(adapterStart);
    }

    private long cost(long start) {
        return System.currentTimeMillis() - start;
    }

    private void log(String msg, Object... args) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log("site-dialog", msg, args);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteBinding actionBinding;
        private final AdapterSiteSwitchBinding switchBinding;
        private Site item;

        ViewHolder(@NonNull AdapterSiteBinding binding) {
            super(binding.getRoot());
            this.actionBinding = binding;
            this.switchBinding = null;
            binding.text.setGravity(Gravity.CENTER);
            binding.getRoot().setOnClickListener(v -> click());
            binding.getRoot().setOnLongClickListener(v -> longClick());
            binding.getRoot().setOnFocusChangeListener((v, hasFocus) -> binding.text.setSelected(hasFocus || isSelected()));
        }

        ViewHolder(@NonNull AdapterSiteSwitchBinding binding) {
            super(binding.getRoot());
            this.actionBinding = null;
            this.switchBinding = binding;
            binding.text.setGravity(Gravity.CENTER);
            binding.getRoot().setOnClickListener(v -> click());
            binding.getRoot().setOnLongClickListener(v -> longClick());
            binding.getRoot().setOnFocusChangeListener((v, hasFocus) -> binding.text.setSelected(hasFocus || isSelected()));
        }

        void bind(Site item) {
            this.item = item;
            float alpha = SiteBlockSetting.isBlocked(item) ? 0.4f : 1f;
            if (actionBinding != null) {
                actionBinding.text.setText(item.getName());
                actionBinding.health.setBackgroundTintList(ColorStateList.valueOf(SiteHealthStore.getColor(item)));
                actionBinding.check.setChecked(getChecked(item));
                actionBinding.text.setSelected(item.isSelected());
                actionBinding.getRoot().setSelected(item.isSelected());
                actionBinding.text.setAlpha(alpha);
                actionBinding.health.setAlpha(alpha);
            } else {
                switchBinding.text.setText(item.getName());
                switchBinding.health.setBackgroundTintList(ColorStateList.valueOf(SiteHealthStore.getColor(item)));
                switchBinding.text.setSelected(item.isSelected());
                switchBinding.getRoot().setSelected(item.isSelected());
                switchBinding.text.setAlpha(alpha);
                switchBinding.health.setAlpha(alpha);
            }
        }

        private boolean isSelected() {
            return item != null && item.isSelected();
        }

        private void click() {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION || item == null) return;
            setListener(item, position);
        }

        private boolean longClick() {
            return item != null && setLongListener(item);
        }
    }
}
