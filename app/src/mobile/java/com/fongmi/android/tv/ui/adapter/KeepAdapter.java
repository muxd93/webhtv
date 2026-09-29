package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.HistoryProgressFormatter;
import com.fongmi.android.tv.utils.ImgUtil;

public class KeepAdapter extends BaseDiffAdapter<Keep, KeepAdapter.ViewHolder> {

    private final OnClickListener listener;
    private int width, height;
    private boolean delete;

    public KeepAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Keep item);

        void onItemDelete(Keep item);

        boolean onLongClick();
    }

    public void setSize(int[] size) {
        this.width = size[0];
        this.height = size[1];
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
        notifyItemRangeChanged(0, getItemCount());
    }

    @Override
    public void clear() {
        super.clear();
        setDelete(false);
        // 数据删除由 KeepActivity 负责（只清视频收藏），adapter 只管界面
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.image.getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Keep item = getItem(position);
        holder.binding.name.setText(item.getVodName());
        holder.binding.site.setVisibility(View.VISIBLE);
        holder.binding.site.setText(item.getSiteName());
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
        History history = item.getCid() == VodConfig.getCid() ? History.find(item.getKey()) : null;
        bindProgress(holder.binding, history);
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
        setClickListener(holder.binding.getRoot(), item);
    }

    private void bindProgress(AdapterVodBinding binding, History history) {
        long progress = history == null ? 0 : history.getPosition();
        long duration = history == null ? 0 : history.getDuration();
        int max = (int) Math.min(Integer.MAX_VALUE, Math.max(0, duration));
        int current = (int) Math.min(Integer.MAX_VALUE, Math.max(0, progress));
        boolean show = !delete && max > 0 && current > 0;
        binding.progress.setMax(max > 0 ? max : 1);
        binding.progress.setProgress(max > 0 ? Math.min(current, max) : 0, false);
        binding.progress.setVisibility(show ? View.VISIBLE : View.GONE);
        String watchedTime = HistoryProgressFormatter.format(progress, duration);
        binding.historyProgress.setText(watchedTime.isEmpty() ? "" : binding.getRoot().getContext().getString(R.string.history_watched_time, watchedTime));
        binding.historyProgress.setVisibility(show && !watchedTime.isEmpty() ? View.VISIBLE : View.GONE);
        String remark = history == null ? "" : history.getVodRemarks();
        binding.remark.setText(remark);
        binding.remark.setVisibility(remark.isEmpty() || remark.equals(binding.name.getText().toString()) ? View.GONE : View.VISIBLE);
    }

    private void setClickListener(View root, Keep item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemDelete(item);
            else listener.onItemClick(item);
        });
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterVodBinding binding;

        ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
