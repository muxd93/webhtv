package com.fongmi.android.tv.ui.presenter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.HistoryProgressFormatter;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

public class KeepPresenter extends Presenter {

    private final OnClickListener listener;
    private int width, height;

    public KeepPresenter(OnClickListener listener) {
        this.listener = listener;
        setLayoutSize();
    }

    public interface OnClickListener {

        void onItemClick(Keep item);

        void onKeepLongClick();
    }

    private void setLayoutSize() {
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (Product.getColumn() - 1));
        int base = ResUtil.getScreenWidth() - space;
        width = base / Product.getColumn();
        height = (int) (width / 0.75f);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        ViewHolder holder = new ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.image.getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        Keep item = (Keep) object;
        ViewHolder holder = (ViewHolder) viewHolder;
        holder.view.setOnClickListener(view -> listener.onItemClick(item));
        holder.view.setOnLongClickListener(view -> {
            listener.onKeepLongClick();
            return true;
        });
        holder.binding.name.setText(item.getVodName());
        holder.binding.site.setText(item.getSiteName());
        holder.binding.site.setVisibility(TextUtils.isEmpty(item.getSiteName()) ? View.GONE : View.VISIBLE);
        holder.binding.delete.setVisibility(View.GONE);
        History history = item.getCid() == VodConfig.getCid() ? History.find(item.getKey()) : null;
        bindProgress(holder.binding, history);
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
    }

    public static void bindProgress(AdapterVodBinding binding, History history) {
        long position = history == null ? 0 : history.getPosition();
        long duration = history == null ? 0 : history.getDuration();
        String watchedTime = HistoryProgressFormatter.format(position, duration);
        binding.historyProgress.setText(binding.getRoot().getContext().getString(R.string.history_watched_time, watchedTime));
        binding.historyProgress.setVisibility(watchedTime.isEmpty() ? View.GONE : View.VISIBLE);
        boolean showBar = duration > 0 && position > 0;
        binding.watchProgress.setVisibility(showBar ? View.VISIBLE : View.GONE);
        if (showBar) binding.watchProgress.setProgress((int) Math.min(100, position * 100 / duration));
        String remark = history == null ? "" : history.getVodRemarks();
        binding.remark.setText(remark);
        binding.remark.setVisibility(remark.isEmpty() || remark.equals(binding.name.getText().toString()) ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        Glide.with(holder.binding.image).clear(holder.binding.image);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterVodBinding binding;

        public ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
