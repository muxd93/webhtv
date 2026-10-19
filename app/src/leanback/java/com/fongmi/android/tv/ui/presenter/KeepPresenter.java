package com.fongmi.android.tv.ui.presenter;

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

import java.util.Collections;
import java.util.Map;

public class KeepPresenter extends Presenter {

    private final OnClickListener mListener;
    private final int width;
    private final int height;
    private Map<String, History> mProgress = Collections.emptyMap();
    private Map<Integer, String> mConfigNames = Collections.emptyMap();

    public KeepPresenter(OnClickListener listener) {
        mListener = listener;
        int column = Math.max(1, Product.getColumn());
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (column - 1));
        int base = ResUtil.getScreenWidth() - space;
        width = base / column;
        height = (int) (width / 0.75f);
    }

    public interface OnClickListener {
        void onItemClick(Keep item);
    }

    public void setProgress(Map<String, History> progress) {
        mProgress = progress == null ? Collections.emptyMap() : progress;
    }

    public void setConfigNames(Map<Integer, String> configNames) {
        mConfigNames = configNames == null ? Collections.emptyMap() : configNames;
    }

    private void setClickListener(View root, Keep item) {
        root.setOnClickListener(view -> mListener.onItemClick(item));
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
        setClickListener(holder.view, item);
        holder.binding.name.setText(item.getVodName());
        // 跨配置的收藏在站点名后标注来源配置，便于区分
        String configName = item.getCid() != VodConfig.getCid() ? mConfigNames.get(item.getCid()) : null;
        holder.binding.site.setText(configName == null || configName.isEmpty() ? item.getSiteName() : item.getSiteName() + " · " + configName);
        holder.binding.site.setVisibility(View.VISIBLE);
        holder.binding.remark.setVisibility(View.GONE);
        holder.binding.delete.setVisibility(View.GONE);
        History history = mProgress.get(item.getKey());
        String watchedTime = history == null ? "" : HistoryProgressFormatter.format(history.getPosition(), history.getDuration());
        holder.binding.historyProgress.setText(watchedTime.isEmpty() ? "" : holder.view.getContext().getString(R.string.history_watched_time, watchedTime));
        holder.binding.historyProgress.setVisibility(watchedTime.isEmpty() ? View.GONE : View.VISIBLE);
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
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
