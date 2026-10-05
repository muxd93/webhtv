package com.fongmi.android.tv.ui.adapter;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterSourceHeaderBinding;
import com.fongmi.android.tv.databinding.AdapterSourceItemBinding;
import com.fongmi.android.tv.setting.InterfaceOrderStore;
import com.fongmi.android.tv.source.SourceState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SourceManagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    public interface OnActionListener {
        void onSelect(Config item);

        void onToggleEnabled(Config item, boolean enabled);

        void onDelete(Config item);

        void onReprobe(Config item);
    }

    public static class Row {
        final int type;
        final Config config;

        Row(int type, Config config) {
            this.type = type;
            this.config = config;
        }
    }

    private final OnActionListener listener;
    private final List<Row> rows = new ArrayList<>();

    public SourceManagerAdapter(OnActionListener listener) {
        this.listener = listener;
    }

    public SourceManagerAdapter build() {
        List<Config> all = new ArrayList<>();
        all.addAll(Config.getAll(0));
        all.addAll(Config.getAll(1));
        all.addAll(Config.getAll(2));
        Map<String, List<Config>> childrenByParent = new HashMap<>();
        List<Config> roots = new ArrayList<>();
        List<Config> orphans = new ArrayList<>();
        for (Config c : all) {
            if (c.isDepot()) roots.add(c);
            else if (!TextUtils.isEmpty(c.getParentUrl())) {
                List<Config> kids = childrenByParent.get(c.getParentUrl());
                if (kids == null) childrenByParent.put(c.getParentUrl(), kids = new ArrayList<>());
                kids.add(c);
            } else {
                orphans.add(c);
            }
        }
        for (Config root : roots) {
            rows.add(new Row(TYPE_HEADER, root));
            List<Config> kids = childrenByParent.get(root.getUrl());
            if (kids != null) for (Config k : kids) rows.add(new Row(TYPE_ITEM, k));
        }
        for (Config o : orphans) rows.add(new Row(TYPE_ITEM, o));
        return this;
    }

    public void remove(Config item) {
        for (int i = 0; i < rows.size(); i++) {
            if (TextUtils.equals(rows.get(i).config.getUrl(), item.getUrl())) {
                rows.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public void removeGroup(String parentUrl) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row r = rows.get(i);
            if (r.type == TYPE_HEADER && TextUtils.equals(r.config.getUrl(), parentUrl)) {
                rows.remove(i);
                notifyItemRemoved(i);
            } else if (r.type == TYPE_ITEM && TextUtils.equals(r.config.getParentUrl(), parentUrl)) {
                rows.remove(i);
                notifyItemRemoved(i);
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(AdapterSourceHeaderBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
        return new ItemHolder(AdapterSourceItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderHolder) ((HeaderHolder) holder).bind(row.config);
        else ((ItemHolder) holder).bind(row.config);
    }

    private String typeName(int type) {
        switch (type) {
            case 0:
                return "VOD";
            case 1:
                return "Live";
            case 2:
                return "Wall";
            default:
                return "";
        }
    }

    private int healthColor(Config item) {
        if (item.getType() == 0) {
            Boolean ok = InterfaceOrderStore.getVodHealth().get(item.getUrl());
            if (ok == null) return 0xFF9E9E9E;
            return ok ? 0xFF4CAF50 : 0xFFE53935;
        }
        return 0xFF9E9E9E;
    }

    public class ItemHolder extends RecyclerView.ViewHolder {
        private final AdapterSourceItemBinding b;

        public ItemHolder(AdapterSourceItemBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        public void bind(Config item) {
            b.text.setText(typeName(item.getType()) + " · " + item.getDesc());
            b.health.setBackgroundTintList(ColorStateList.valueOf(healthColor(item)));
            boolean enabled = SourceState.isEnabled(item);
            b.enable.setText(enabled ? R.string.source_enabled : R.string.source_disabled);
            b.text.setOnClickListener(v -> listener.onSelect(item));
            b.enable.setOnClickListener(v -> listener.onToggleEnabled(item, !enabled));
            b.delete.setOnClickListener(v -> listener.onDelete(item));
        }
    }

    public class HeaderHolder extends RecyclerView.ViewHolder {
        private final AdapterSourceHeaderBinding b;

        public HeaderHolder(AdapterSourceHeaderBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        public void bind(Config item) {
            b.text.setText("Depot · " + item.getDesc());
            b.reprobe.setOnClickListener(v -> listener.onReprobe(item));
            b.delete.setOnClickListener(v -> listener.onDelete(item));
        }
    }
}
