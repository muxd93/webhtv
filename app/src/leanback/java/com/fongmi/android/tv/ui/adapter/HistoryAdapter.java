package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.HistoryProgressFormatter;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Map;

public class HistoryAdapter extends BaseDiffAdapter<History, HistoryAdapter.ViewHolder> {

    private final OnClickListener listener;
    private int width, height;
    private boolean delete;
    private Map<Integer, String> configNames = Map.of();

    public HistoryAdapter(OnClickListener listener) {
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
        notifyItemRangeChanged(0, getItemCount());
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
        History item = getItem(position);
        setClickListener(holder.itemView, item);
        holder.binding.name.setText(item.getVodName());
        holder.binding.site.setText(siteText(item));
        holder.binding.site.setVisibility(item.getSiteVisible());
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
        boolean same = item.getVodName().equals(item.getVodRemarks());
        holder.binding.remark.setText(item.getVodRemarks());
        holder.binding.remark.setVisibility(delete || same ? View.GONE : View.VISIBLE);
        String watchedTime = HistoryProgressFormatter.format(item.getPosition(), item.getDuration());
        holder.binding.historyProgress.setText(watchedTime.isEmpty() ? "" : holder.itemView.getContext().getString(R.string.history_watched_time, watchedTime));
        holder.binding.historyProgress.setVisibility(delete || watchedTime.isEmpty() ? View.GONE : View.VISIBLE);
        boolean showBar = !delete && item.getDuration() > 0 && item.getPosition() > 0;
        holder.binding.watchProgress.setVisibility(showBar ? View.VISIBLE : View.GONE);
        if (showBar) holder.binding.watchProgress.setProgress((int) Math.min(100, item.getPosition() * 100 / item.getDuration()));
        holder.itemView.setContentDescription(item.getVodName());
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterVodBinding binding;

        public ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            setFocusListener();
        }

        private void setFocusListener() {
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start();
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
