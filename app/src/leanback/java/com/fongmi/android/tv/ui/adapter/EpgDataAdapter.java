package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.databinding.AdapterEpgDataBinding;
import com.fongmi.android.tv.live.LiveSession;

import java.util.ArrayList;
import java.util.List;

public class EpgDataAdapter extends RecyclerView.Adapter<EpgDataAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<EpgData> mItems;
    private Channel mChannel;

    public EpgDataAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    /** 频道决定"可回看"标签与降透明度判定(catchup 声明随当前线路变化,换线后重设)。 */
    public void setChannel(Channel channel) {
        mChannel = channel;
    }

    public void addAll(List<EpgData> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public EpgData get(int position) {
        return mItems.get(position);
    }

    public void setSelected(EpgData selected) {
        for (EpgData item : mItems) item.setSelected(selected);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpgDataBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EpgData item = mItems.get(position);
        holder.binding.time.setText(item.getTime());
        holder.binding.title.setText(item.getTitle());
        // 三态:正在播 > 未开始 > 可回看;无能力的已播节目降透明度
        int text = item.isInRange() ? R.string.live_program_current : item.isFuture() ? R.string.live_program_future : LiveSession.isCatchupable(mChannel, item) ? R.string.live_program_catchup : 0;
        holder.binding.status.setVisibility(text == 0 ? View.GONE : View.VISIBLE);
        if (text != 0) holder.binding.status.setText(text);
        holder.binding.getRoot().setSelected(item.isSelected());
        holder.binding.getRoot().setAlpha(item.isPast() && !LiveSession.isCatchupable(mChannel, item) ? 0.5f : 1f);
        holder.binding.getRoot().setLeftListener(mListener::hideEpg);
        holder.binding.getRoot().setOnClickListener(v -> {
            if (!item.isFuture()) mListener.onItemClick(item);
        });
    }

    public interface OnClickListener {

        void hideEpg();

        void onItemClick(EpgData item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpgDataBinding binding;

        ViewHolder(@NonNull AdapterEpgDataBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
