package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.HistoryProgressFormatter;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class HistoryAdapter extends BaseDiffAdapter<History, HistoryAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final int width;
    private final int height;
    private boolean animate;
    private boolean delete;
    private Map<Integer, String> configNames = Collections.emptyMap();

    public HistoryAdapter(OnClickListener listener) {
        this.listener = listener;
        this.animate = true;
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (Product.getColumn() - 1));
        int base = ResUtil.getScreenWidth() - space;
        width = base / Product.getColumn();
        height = (int) (width / 0.75f);
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemDelete(History item);

        boolean onLongClick();
    }

    public void setConfigNames(Map<Integer, String> configNames) {
        this.configNames = configNames == null ? Collections.emptyMap() : configNames;
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.animate = false;
        this.delete = delete;
        notifyItemRangeChanged(0, getItemCount());
    }

    @Override
    public void clear() {
        // 全局模式下列表含多配置历史，按去重 cid 逐个清空（本地+同步）；本地模式等价于清当前配置
        List<Integer> cids = new ArrayList<>();
        for (History item : getItems()) if (!cids.contains(item.getCid())) cids.add(item.getCid());
        super.clear();
        setDelete(false);
        for (int cid : cids) History.deleteAndSync(cid);
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
        boolean same = item.getVodName().equals(item.getVodRemarks());
        holder.binding.name.setText(item.getVodName());
        // 跨配置的历史在站点名后标注来源配置，便于区分
        String configName = item.getCid() != VodConfig.getCid() ? configNames.get(item.getCid()) : null;
        holder.binding.site.setText(configName == null || configName.isEmpty() ? item.getSiteName() : item.getSiteName() + " · " + configName);
        holder.binding.remark.setText(item.getVodRemarks());
        holder.binding.site.setVisibility(item.getSiteVisible());
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
        holder.binding.remark.setVisibility(delete || same ? View.GONE : View.VISIBLE);
        String watchedTime = HistoryProgressFormatter.format(item.getPosition(), item.getDuration());
        holder.binding.historyProgress.setText(watchedTime.isEmpty() ? "" : holder.itemView.getContext().getString(R.string.history_watched_time, watchedTime));
        holder.binding.historyProgress.setVisibility(delete || watchedTime.isEmpty() ? View.GONE : View.VISIBLE);
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
        setClickListener(holder.binding.getRoot(), item);
    }

    private void setClickListener(View root, History item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemDelete(item);
            else listener.onItemClick(item);
        });
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterVodBinding binding;

        public ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
