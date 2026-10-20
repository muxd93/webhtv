package com.fongmi.android.tv.ui.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterSiteHealthBinding;
import com.fongmi.android.tv.setting.SiteHealthStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SiteHealthAdapter extends RecyclerView.Adapter<SiteHealthAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Site> mItems;

    public SiteHealthAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        setHasStableIds(true);
    }

    public interface OnClickListener {

        void onItemClick(Site item);
    }

    public void setItems(List<Site> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public long getItemId(int position) {
        return mItems.get(position).getKey().hashCode();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSiteHealthBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(mItems.get(position)));
        holder.binding.name.setText(mItems.get(position).getName());
        SiteHealthStore.Summary summary = SiteHealthStore.summary(mItems.get(position));
        if (summary == null) return;
        holder.binding.health.setBackgroundTintList(ColorStateList.valueOf(summary.color));
        holder.binding.stats.setText(String.format(Locale.getDefault(), "%d/%d · %d/%d · %d/%d · %.1fs", summary.searchSuccess, summary.searchFail, summary.detailSuccess, summary.detailFail, summary.playSuccess, summary.playFail, Math.max(summary.lastSearchCost, summary.lastDetailCost) / 1000f));
        holder.binding.error.setText(summary.lastError);
        holder.binding.error.setVisibility(summary.lastError.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteHealthBinding binding;

        ViewHolder(@NonNull AdapterSiteHealthBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
