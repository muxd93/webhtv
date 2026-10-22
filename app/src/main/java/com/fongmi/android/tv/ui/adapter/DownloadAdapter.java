package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadTask;
import com.fongmi.android.tv.databinding.AdapterDownloadBinding;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

public class DownloadAdapter extends BaseDiffAdapter<DownloadTask, DownloadAdapter.ViewHolder> {

    private final OnClickListener listener;

    public DownloadAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(DownloadTask task);

        void onItemDelete(DownloadTask task);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDownloadBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadTask task = getItem(position);
        holder.binding.name.setText(task.getVodName());
        holder.binding.episode.setText(task.getVodFlag() + " " + task.getEpisodeName());
        holder.binding.state.setText(stateText(task));
        holder.binding.progress.setProgress(DownloadManager.progress(task));
        holder.binding.action.setText(ResUtil.getString(R.string.download_delete));
        holder.binding.action.setOnClickListener(view -> listener.onItemDelete(task));
        holder.binding.getRoot().setOnClickListener(view -> listener.onItemClick(task));
        ImgUtil.load(task.getVodName(), task.getVodPic(), holder.binding.image);
    }

    private String stateText(DownloadTask task) {
        String state = DownloadManager.stateText(task);
        if (task.isFailed() && !task.getErrorMsg().isEmpty()) return state + " · " + task.getErrorMsg();
        if (task.isCompleted()) {
            return state + " · " + FileUtil.byteCountToDisplaySize(task.getDoneBytes());
        }
        if (task.getState() == DownloadTask.STATE_DOWNLOADING || task.getState() == DownloadTask.STATE_PAUSED) {
            String progress = DownloadManager.progress(task) + "%";
            if (task.getTotalSegments() > 0) progress = task.getDoneSegments() + "/" + task.getTotalSegments() + " " + progress;
            else if (task.getTotalBytes() > 0) progress = FileUtil.byteCountToDisplaySize(task.getDoneBytes()) + "/" + FileUtil.byteCountToDisplaySize(task.getTotalBytes()) + " " + progress;
            return state + " " + progress;
        }
        return state;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterDownloadBinding binding;

        public ViewHolder(@NonNull AdapterDownloadBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            setFocusListener();
        }

        private void setFocusListener() {
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(150).start();
                    v.setTranslationZ(10f);
                    v.setSelected(true);
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    v.setTranslationZ(0f);
                    v.setSelected(false);
                }
            });
        }
    }
}
