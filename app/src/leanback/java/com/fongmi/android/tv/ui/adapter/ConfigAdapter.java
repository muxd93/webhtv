package com.fongmi.android.tv.ui.adapter;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.api.config.ConfigLister;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterConfigBinding;
import com.fongmi.android.tv.databinding.AdapterConfigSectionBinding;

import java.util.List;

public class ConfigAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final OnClickListener listener;
    private List<ConfigLister.Row> mItems;
    private boolean readOnly;
    private int type;

    public interface OnClickListener {

        void onTextClick(Config item);

        void onDeleteClick(Config item);

        void onDepotToggle(Config item);

        void onDepotRefresh(Config item);
    }

    public ConfigAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public ConfigAdapter readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public ConfigAdapter addAll(int type) {
        this.type = type;
        mItems = ConfigLister.build(type);
        return this;
    }

    /** 折叠/展开多仓行，返回该行位置供焦点保持 */
    public int toggle(Config item) {
        ConfigLister.toggle(item.getId());
        int position = -1;
        List<ConfigLister.Row> rows = ConfigLister.build(type);
        for (int i = 0; i < rows.size(); i++) if (item.equals(rows.get(i).config)) position = i;
        mItems = rows;
        notifyDataSetChanged();
        return position;
    }

    /** 删除后返回建议聚焦的位置（被删项的相邻项）；列表为空返回 -1 */
    public int remove(Config item) {
        int position = -1;
        for (int i = 0; i < mItems.size(); i++) if (item.equals(mItems.get(i).config)) position = i;
        item.delete();
        mItems = ConfigLister.build(type);
        notifyDataSetChanged();
        if (position >= mItems.size()) position = mItems.size() - 1;
        return position;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return mItems.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == ConfigLister.SECTION) return new SectionViewHolder(AdapterConfigSectionBinding.inflate(inflater, parent, false));
        return new ItemViewHolder(AdapterConfigBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ConfigLister.Row row = mItems.get(position);
        if (holder instanceof SectionViewHolder section) {
            section.binding.text.setText(row.text);
            return;
        }
        ItemViewHolder item = (ItemViewHolder) holder;
        item.binding.text.setText(row.text);
        item.binding.text.setTypeface(null, row.type == ConfigLister.DEPOT ? Typeface.BOLD : Typeface.NORMAL);
        item.binding.text.setPadding(row.indent ? (int) (24 * item.binding.text.getResources().getDisplayMetrics().density) : 0, 0, 0, 0);
        item.binding.expand.setVisibility(row.type == ConfigLister.DEPOT ? View.VISIBLE : View.GONE);
        item.binding.expand.setRotation(row.type == ConfigLister.DEPOT && row.expanded ? 90f : 0f);
        item.binding.text.setOnClickListener(v -> {
            if (row.type == ConfigLister.DEPOT) listener.onDepotToggle(row.config);
            else listener.onTextClick(row.config);
        });
        item.binding.text.setOnLongClickListener(v -> {
            if (row.type != ConfigLister.DEPOT) return false;
            listener.onDepotRefresh(row.config);
            return true;
        });
        item.binding.delete.setVisibility(readOnly ? View.GONE : View.VISIBLE);
        item.binding.delete.setOnClickListener(v -> listener.onDeleteClick(row.config));
    }

    public class SectionViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigSectionBinding binding;

        SectionViewHolder(@NonNull AdapterConfigSectionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public class ItemViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigBinding binding;

        ItemViewHolder(@NonNull AdapterConfigBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
