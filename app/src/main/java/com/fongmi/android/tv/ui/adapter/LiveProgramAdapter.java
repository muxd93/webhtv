package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.databinding.AdapterLiveProgramItemBinding;
import com.fongmi.android.tv.live.LiveSession;

import java.util.ArrayList;
import java.util.List;

public class LiveProgramAdapter extends RecyclerView.Adapter<LiveProgramAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<EpgData> items;
    private Channel channel;

    public LiveProgramAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    /** 频道决定"可回看"标签与降透明度判定(catchup 声明随当前线路变化,换线后重设)。 */
    public LiveProgramAdapter channel(Channel item) {
        this.channel = item;
        return this;
    }

    public interface OnClickListener {

        void onProgramClick(EpgData item);
    }

    public void setEpg(Epg epg) {
        items.clear();
        items.addAll(epg.getList());
        notifyDataSetChanged();
    }

    public int getSelected() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).isSelected()) return i;
        return -1;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterLiveProgramItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EpgData item = items.get(position);
        holder.binding.time.setText(item.getTime());
        holder.binding.title.setText(item.getTitle());
        // 三态:正在播 > 未开始 > 可回看;无能力的已播节目无标签且降透明度
        int text = item.isInRange() ? R.string.live_program_current : item.isFuture() ? R.string.live_program_future : LiveSession.isCatchupable(channel, item) ? R.string.live_program_catchup : 0;
        holder.binding.status.setVisibility(text == 0 ? View.GONE : View.VISIBLE);
        if (text != 0) holder.binding.status.setText(text);
        holder.binding.getRoot().setSelected(item.isSelected());
        holder.binding.getRoot().setAlpha(item.isPast() && !LiveSession.isCatchupable(channel, item) ? 0.5f : 1f);
        holder.binding.getRoot().setOnClickListener(view -> {
            if (!item.isFuture()) listener.onProgramClick(item);
        });
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterLiveProgramItemBinding binding;

        ViewHolder(@NonNull AdapterLiveProgramItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
