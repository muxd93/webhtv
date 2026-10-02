package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterPoolHeaderBinding;
import com.fongmi.android.tv.databinding.AdapterPoolQuarantineBinding;
import com.fongmi.android.tv.databinding.AdapterPoolSourceBinding;
import com.fongmi.android.tv.live.LiveAggregator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聚合源池列表：订阅源行（点击启停、▲▼ 排序、✕ 移出）+ 隔离区（标题 + 频道行 + 复活）。
 * 数据由 LiveAggregator.poolStatus()/quarantineList() 提供，UI 不持有池状态。
 */
public class PoolAdapter extends RecyclerView.Adapter<PoolAdapter.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SOURCE = 1;
    private static final int TYPE_QUARANTINE = 2;
    private static final Object HEADER = new Object();

    private final OnClickListener listener;
    private final List<Object> items;
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

        void onRevive(LiveAggregator.QuarantineInfo item);
    }

    public void setItems(List<LiveAggregator.SourceInfo> sources, List<LiveAggregator.QuarantineInfo> quarantines) {
        items.clear();
        items.addAll(sources);
        if (!quarantines.isEmpty()) {
            items.add(HEADER);
            items.addAll(quarantines);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item == HEADER) return TYPE_HEADER;
        return item instanceof LiveAggregator.SourceInfo ? TYPE_SOURCE : TYPE_QUARANTINE;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return switch (viewType) {
            case TYPE_SOURCE -> new SourceHolder(AdapterPoolSourceBinding.inflate(inflater, parent, false));
            case TYPE_QUARANTINE -> new QuarantineHolder(AdapterPoolQuarantineBinding.inflate(inflater, parent, false));
            default -> new HeaderHolder(AdapterPoolHeaderBinding.inflate(inflater, parent, false));
        };
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof SourceHolder) ((SourceHolder) holder).bind((LiveAggregator.SourceInfo) item, listener, format);
        else if (holder instanceof QuarantineHolder) ((QuarantineHolder) holder).bind((LiveAggregator.QuarantineInfo) item, listener);
        else ((HeaderHolder) holder).bind();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    abstract static class ViewHolder extends RecyclerView.ViewHolder {

        ViewHolder(@NonNull View view) {
            super(view);
        }
    }

    private static class HeaderHolder extends ViewHolder {

        private final AdapterPoolHeaderBinding binding;

        HeaderHolder(AdapterPoolHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind() {
            binding.text.setText(R.string.pool_quarantine);
        }
    }

    private static class SourceHolder extends ViewHolder {

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

    private static class QuarantineHolder extends ViewHolder {

        private final AdapterPoolQuarantineBinding binding;

        QuarantineHolder(AdapterPoolQuarantineBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(LiveAggregator.QuarantineInfo item, OnClickListener listener) {
            binding.text.setText(item.group.isEmpty() ? item.name : item.group + " · " + item.name);
            binding.revive.setOnClickListener(v -> listener.onRevive(item));
        }
    }
}
