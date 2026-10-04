package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterPoolSourceBinding;
import com.fongmi.android.tv.live.LiveAggregator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聚合源池列表：订阅源行（点击启停、▲▼ 排序、✕ 移出）。
 * 数据由 LiveAggregator.poolStatus() 提供，UI 不持有池状态。
 */
public class PoolAdapter extends RecyclerView.Adapter<PoolAdapter.SourceHolder> {

    private final OnClickListener listener;
    private final List<LiveAggregator.SourceInfo> items;
    private final SimpleDateFormat format;

    public PoolAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
        this.format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    }

    public interface OnClickListener {

        void onSourceClick(LiveAggregator.SourceInfo item);

        void onMove(LiveAggregator.SourceInfo item, int delta);

        void onRemove(LiveAggregator.SourceInfo item);
    }

    public void setItems(List<LiveAggregator.SourceInfo> sources) {
        items.clear();
        items.addAll(sources);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SourceHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new SourceHolder(AdapterPoolSourceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull SourceHolder holder, int position) {
        holder.bind(items.get(position), listener, format);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class SourceHolder extends RecyclerView.ViewHolder {

        private final AdapterPoolSourceBinding binding;

        SourceHolder(AdapterPoolSourceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(LiveAggregator.SourceInfo item, OnClickListener listener, SimpleDateFormat format) {
            binding.text.setText(item.name.isEmpty() ? item.url : item.name);
            binding.sub.setText(sub(item, binding.text.getContext(), format));
            binding.text.setOnClickListener(v -> listener.onSourceClick(item));
            binding.sub.setOnClickListener(v -> listener.onSourceClick(item));
            binding.up.setOnClickListener(v -> listener.onMove(item, -1));
            binding.down.setOnClickListener(v -> listener.onMove(item, 1));
            binding.remove.setOnClickListener(v -> listener.onRemove(item));
        }

        private String sub(LiveAggregator.SourceInfo item, android.content.Context context, SimpleDateFormat format) {
            StringBuilder out = new StringBuilder();
            out.append(!item.enabled ? context.getString(R.string.pool_src_off) : item.ok ? context.getString(R.string.pool_src_ok) : context.getString(R.string.pool_src_fail));
            out.append(" · ").append(item.ts <= 0 ? context.getString(R.string.pool_never) : format.format(new Date(item.ts)));
            if (item.channels >= 0) out.append(" · ").append(context.getString(R.string.pool_channels, item.channels));
            return out.toString();
        }
    }
}
