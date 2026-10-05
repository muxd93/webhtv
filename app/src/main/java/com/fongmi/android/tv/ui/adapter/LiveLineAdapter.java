package com.fongmi.android.tv.ui.adapter;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.databinding.AdapterLiveLineBinding;
import com.fongmi.android.tv.live.LineBlockStore;
import com.fongmi.android.tv.utils.ResUtil;

public class LiveLineAdapter extends RecyclerView.Adapter<LiveLineAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final Channel channel;

    public LiveLineAdapter(OnClickListener listener, Channel channel) {
        this.listener = listener;
        this.channel = channel;
    }

    public interface OnClickListener {

        void onLineClick(int position);

        void onLineLongClick(int position);
    }

    @Override
    public int getItemCount() {
        return channel.getUrls().size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterLiveLineBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.text.setText(getName(position));
        holder.binding.text.setSelected(channel.getIndex() == position);
        holder.binding.text.setOnClickListener(view -> listener.onLineClick(position));
        holder.binding.text.setOnLongClickListener(view -> {
            listener.onLineLongClick(position);
            return true;
        });
        applyBlocked(holder.binding.text, position);
    }

    private String getName(int position) {
        String name = channel.lineName(position);
        if (!name.isEmpty()) return name;
        return ResUtil.getString(com.fongmi.android.tv.R.string.live_line, position + 1);
    }

    /** 屏蔽态可视化：删除线 + 降透明度（沉底保留可选语义，仍可点击播放、长按取消屏蔽）。 */
    private void applyBlocked(TextView text, int position) {
        boolean blocked = LineBlockStore.isBlocked(channel.getUrls().get(position));
        if (blocked) text.setPaintFlags(text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        else text.setPaintFlags(text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        text.setAlpha(blocked ? 0.4f : 1f);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterLiveLineBinding binding;

        ViewHolder(@NonNull AdapterLiveLineBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
