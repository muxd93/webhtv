package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterConfigBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ViewHolder> {

    private final OnClickListener listener;
    private List<Config> mItems;
    private boolean readOnly;
    private String currentUrl;

    public ConfigAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onTextClick(Config item);

        void onDeleteClick(Config item);
    }

    public ConfigAdapter readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public ConfigAdapter addAll(int type) {
        return addAll(type, null);
    }

    public ConfigAdapter addAll(int type, Config current) {
        currentUrl = current == null ? null : current.getUrl();
        // 仓子源由仓根代表（SRCUI4）：历史只显示"仓根+独立源"，多子源归并为一个根条目
        Map<String, Config> merged = new LinkedHashMap<>();
        for (Config item : Config.getAll(type)) {
            Config root = item.rootOrSelf();
            merged.putIfAbsent(root.getType() + "|" + root.getUrl(), root);
        }
        mItems = new ArrayList<>(merged.values());
        // 当前使用的配置置顶展示并标注（ViewHolder 内），不可从历史中删除
        if (!readOnly && !TextUtils.isEmpty(currentUrl)) {
            Config cur = AppDatabase.get().getConfigDao().find(currentUrl, type); // 非创建式查找，避免幽灵行
            if (cur != null) {
                Config active = cur.rootOrSelf();
                mItems.removeIf(item -> item.getType() == active.getType() && TextUtils.equals(item.getUrl(), active.getUrl()));
                mItems.add(0, active);
            }
        }
        return this;
    }

    public int remove(Config item) {
        int position = mItems.indexOf(item);
        if (position == -1) return -1;
        item.delete();
        mItems.remove(position);
        notifyItemRemoved(position);
        return getItemCount();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterConfigBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config item = mItems.get(position);
        boolean isCurrent = TextUtils.equals(item.getUrl(), currentUrl);
        String desc = item.isDepot() ? holder.binding.text.getContext().getString(com.fongmi.android.tv.R.string.config_depot_tag, item.getDesc()) : item.getDesc();
        holder.binding.text.setText(isCurrent ? desc + holder.binding.text.getContext().getString(com.fongmi.android.tv.R.string.config_in_use) : desc);
        holder.binding.text.setOnClickListener(v -> listener.onTextClick(item));
        holder.binding.delete.setVisibility(readOnly || isCurrent ? View.GONE : View.VISIBLE);
        holder.binding.delete.setOnClickListener(v -> listener.onDeleteClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigBinding binding;

        public ViewHolder(@NonNull AdapterConfigBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
