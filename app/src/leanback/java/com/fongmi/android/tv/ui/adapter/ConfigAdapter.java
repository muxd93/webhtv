package com.fongmi.android.tv.ui.adapter;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.ConfigLister;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterConfigBinding;
import com.fongmi.android.tv.databinding.AdapterConfigSectionBinding;
import com.fongmi.android.tv.utils.Task;

import java.util.Collections;
import java.util.List;

public class ConfigAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final OnClickListener listener;
    private List<ConfigLister.Row> mItems = Collections.emptyList();
    private boolean readOnly;
    private int type;
    private int generation;
    private OnLoadListener onLoad;

    public interface OnClickListener {

        void onTextClick(Config item);

        void onDeleteClick(Config item);

        void onDepotToggle(Config item);

        void onDepotRefresh(Config item);
    }

    /** 列表构建完成（主线程）：position 是重建后建议聚焦的行，-1 表示无需聚焦 */
    public interface OnLoadListener {

        void onConfigRows(List<ConfigLister.Row> rows, int position);
    }

    public ConfigAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public ConfigAdapter readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /** 首次构建要查库，放后台跑；先用一行占位把对话框立起来，避免首帧是空列表 */
    public ConfigAdapter addAll(int type) {
        this.type = type;
        mItems = placeholder();
        reload(null);
        return this;
    }

    public ConfigAdapter onLoaded(OnLoadListener onLoad) {
        this.onLoad = onLoad;
        return this;
    }

    /** 折叠/展开多仓行：被操作行自身的位置不变，重建同样在后台 */
    public void toggle(Config item) {
        ConfigLister.toggle(item.getId());
        reload(rows -> indexOf(item, rows));
    }

    /** 删除后建议聚焦被删项的相邻行；列表清空给 -1，由对话框沿用旧语义直接关闭 */
    public void remove(Config item) {
        int index = indexOf(item, mItems);
        item.delete();
        reload(rows -> rows.isEmpty() ? -1 : Math.min(index, rows.size() - 1));
    }

    private void reload(@Nullable Focus focus) {
        int token = ++generation;
        Task.execute(() -> {
            List<ConfigLister.Row> rows = ConfigLister.build(type);
            App.post(() -> apply(token, rows, focus));
        });
    }

    private void apply(int token, List<ConfigLister.Row> rows, @Nullable Focus focus) {
        if (token != generation) return;   // 期间又发起过构建：旧结果作废，位置以最新结果为准
        mItems = rows;
        notifyDataSetChanged();
        if (onLoad != null) onLoad.onConfigRows(rows, focus == null ? -1 : focus.resolve(rows));
    }

    private static int indexOf(Config item, List<ConfigLister.Row> rows) {
        for (int i = 0; i < rows.size(); i++) if (item.equals(rows.get(i).config)) return i;
        return -1;
    }

    /** 占位行：只借用分区样式，不可点击；后台回填后整表替换 */
    private static List<ConfigLister.Row> placeholder() {
        ConfigLister.Row row = new ConfigLister.Row();
        row.type = ConfigLister.SECTION;
        row.text = App.get().getString(R.string.config_list_loading);
        return Collections.singletonList(row);
    }

    /** 重建完成后要聚焦的行：按最新 rows 解析，-1 表示不聚焦 */
    private interface Focus {

        int resolve(List<ConfigLister.Row> rows);
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
