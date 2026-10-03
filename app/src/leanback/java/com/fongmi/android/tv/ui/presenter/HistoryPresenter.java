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
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.HistoryProgressFormatter;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Map;

public class HistoryPresenter extends Presenter {

    private final OnClickListener listener;
    private int width, height;
    private boolean delete;
    private Map<Integer, String> configNames = Map.of();

    public HistoryPresenter(OnClickListener listener) {
        this.listener = listener;
        setLayoutSize();
    }

    /** 聚合模式下用于把跨配置条目的站点名替换为来源配置名。 */
    public void setConfigNames(Map<Integer, String> names) {
        this.configNames = names == null ? Map.of() : names;
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemDelete(History item);

        void onItemFocus(History item);

        boolean onLongClick();
    }

    private void setLayoutSize() {
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (Product.getColumn() - 1));
        int base = ResUtil.getScreenWidth() - space;
        width = base / Product.getColumn();
        height = (int) (width / 0.75f);
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
    }

    private String siteText(History item) {
        String configName = configNames.get(item.getCid());
        return item.getCid() != VodConfig.getCid() && configName != null ? configName : item.getSiteName();
    }

    private void setClickListener(View root, History item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemDelete(item);
            else listener.onItemClick(item);
        });
        root.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) listener.onItemFocus(item);
        });
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
        History item = (History) object;
        boolean same = item.getVodName().equals(item.getVodRemarks());
        ViewHolder holder = (ViewHolder) viewHolder;
        setClickListener(holder.view, item);
        holder.binding.name.setText(item.getVodName());
        holder.binding.site.setText(siteText(item));
        holder.binding.remark.setText(item.getVodRemarks());
        holder.binding.site.setVisibility(item.getSiteVisible());
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
        holder.binding.remark.setVisibility(delete || same ? View.GONE : View.VISIBLE);
        String watchedTime = HistoryProgressFormatter.format(item.getPosition(), item.getDuration());
        holder.binding.historyProgress.setText(watchedTime.isEmpty() ? "" : holder.view.getContext().getString(R.string.history_watched_time, watchedTime));
        holder.binding.historyProgress.setVisibility(delete || watchedTime.isEmpty() ? View.GONE : View.VISIBLE);
        boolean showBar = !delete && item.getDuration() > 0 && item.getPosition() > 0;
        holder.binding.watchProgress.setVisibility(showBar ? View.VISIBLE : View.GONE);
        if (showBar) holder.binding.watchProgress.setProgress((int) Math.min(100, item.getPosition() * 100 / item.getDuration()));
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
