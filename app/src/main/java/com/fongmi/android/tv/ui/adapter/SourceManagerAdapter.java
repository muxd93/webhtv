package com.fongmi.android.tv.ui.adapter;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.DepotProbe;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterSourceHeaderBinding;
import com.fongmi.android.tv.databinding.AdapterSourceItemBinding;
import com.fongmi.android.tv.source.SourceState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SourceManagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    public interface OnActionListener {
        void onSelect(Config item);

        void onToggleEnabled(Config item, boolean enabled);

        void onDelete(Config item);

        void onRefresh(Config depot);
    }

    public static class Row {
        final int type;
        final Config config;

        Row(int type, Config config) {
            this.type = type;
            this.config = config;
        }
    }

    /** 单仓探测瞬时状态：驱动卡头摘要文案与进度。 */
    private static class ProbeState {
        boolean probing;
        int done;
        int total;
        List<DepotProbe.Result> results;
    }

    private final OnActionListener listener;
    private final List<Row> rows = new ArrayList<>();
    private final List<Config> roots = new ArrayList<>();
    private final List<Config> orphans = new ArrayList<>();
    private final Map<String, List<Config>> childrenByParent = new HashMap<>();
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, Boolean> lastProbe = new HashMap<>();
    private final Map<String, ProbeState> probeStates = new HashMap<>();

    public SourceManagerAdapter(OnActionListener listener) {
        this.listener = listener;
    }

    public SourceManagerAdapter build() {
        gather();
        rebuildRows();
        return this;
    }

    /** 重新从数据库拉取（新增/删除订阅后调用）。 */
    public void rebuild() {
        gather();
        rebuildRows();
        notifyDataSetChanged();
    }

    private void gather() {
        rows.clear();
        roots.clear();
        orphans.clear();
        childrenByParent.clear();
        expanded.clear();
        List<Config> all = new ArrayList<>();
        all.addAll(Config.getAll(0));
        all.addAll(Config.getAll(1));
        all.addAll(Config.getAll(2));
        Map<String, List<Config>> byParent = new HashMap<>();
        for (Config c : all) {
            if (c.isDepot()) roots.add(c);
            else if (!TextUtils.isEmpty(c.getParentUrl())) {
                List<Config> kids = byParent.get(c.getParentUrl());
                if (kids == null) byParent.put(c.getParentUrl(), kids = new ArrayList<>());
                kids.add(c);
            } else {
                orphans.add(c);
            }
        }
        for (Config root : roots) {
            childrenByParent.put(root.getUrl(), byParent.get(root.getUrl()));
            // 默认展开全部仓，折叠为可选操作（TV 列表通常仓数不多）
            expanded.add(root.getUrl());
        }
        lastProbe.keySet().removeIf(url -> true);
    }

    private void rebuildRows() {
        rows.clear();
        for (Config root : roots) {
            rows.add(new Row(TYPE_HEADER, root));
            if (expanded.contains(root.getUrl())) addChildren(root.getUrl());
        }
        for (Config o : orphans) rows.add(new Row(TYPE_ITEM, o));
    }

    private void addChildren(String parentUrl) {
        List<Config> kids = childrenByParent.get(parentUrl);
        if (kids == null) return;
        for (Config k : kids) rows.add(new Row(TYPE_ITEM, k));
    }

    private int indexOfHeader(String parentUrl) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.type == TYPE_HEADER && TextUtils.equals(r.config.getUrl(), parentUrl)) return i;
        }
        return -1;
    }

    private void toggleExpanded(Config root) {
        String key = root.getUrl();
        int pos = indexOfHeader(key);
        if (pos < 0) return;
        if (expanded.contains(key)) {
            expanded.remove(key);
            List<Config> kids = childrenByParent.get(key);
            int n = kids == null ? 0 : kids.size();
            int start = pos + 1;
            rows.subList(start, start + n).clear();
            notifyItemRangeRemoved(start, n);
            notifyItemChanged(pos);
        } else {
            expanded.add(key);
            int start = pos + 1;
            int n = 0;
            List<Config> kids = childrenByParent.get(key);
            if (kids != null) for (Config k : kids) {
                rows.add(start + n, new Row(TYPE_ITEM, k));
                n++;
            }
            notifyItemRangeInserted(start, n);
            notifyItemChanged(pos);
        }
    }

    // ---- 探测状态入口（由 Dialog 的异步回调驱动） ----

    public void setProbeState(String parentUrl, int total) {
        ProbeState s = state(parentUrl);
        s.probing = true;
        s.done = 0;
        s.total = total;
        s.results = null;
    }

    public void setProbeProgress(String parentUrl, int done, int total) {
        ProbeState s = state(parentUrl);
        s.probing = true;
        s.done = done;
        s.total = total;
    }

    public void setProbeResult(String parentUrl, List<DepotProbe.Result> results) {
        ProbeState s = state(parentUrl);
        s.probing = false;
        s.done = results == null ? 0 : results.size();
        s.total = s.done;
        s.results = results;
        if (results != null) for (DepotProbe.Result r : results) lastProbe.put(r.url, r.ok);
    }

    private ProbeState state(String parentUrl) {
        ProbeState s = probeStates.get(parentUrl);
        if (s == null) probeStates.put(parentUrl, s = new ProbeState());
        return s;
    }

    public void notifyHeaderChanged(String parentUrl) {
        int p = indexOfHeader(parentUrl);
        if (p >= 0) notifyItemChanged(p);
    }

    public void notifyChildrenChanged(String parentUrl) {
        int p = indexOfHeader(parentUrl);
        if (p < 0) return;
        List<Config> kids = childrenByParent.get(parentUrl);
        int n = kids == null ? 0 : kids.size();
        if (expanded.contains(parentUrl) && n > 0) notifyItemRangeChanged(p + 1, n);
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

    private String currentUrl(int type) {
        try {
            switch (type) {
                case 0:
                    return VodConfig.get().getConfig().getUrl();
                case 1:
                    return LiveConfig.get().getConfig().getUrl();
                case 2:
                    return WallConfig.get().getConfig().getUrl();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private int healthColor(Config item) {
        Boolean probe = lastProbe.get(item.getUrl());
        if (probe != null) return probe ? 0xFF4CAF50 : 0xFFE53935;
        if (item.getType() == 0) {
            Boolean ok = InterfaceOrderStoreHelper.getVodHealth().get(item.getUrl());
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
            boolean enabled = SourceState.isEnabled(item);
            boolean used = TextUtils.equals(item.getUrl(), currentUrl(item.getType()));
            b.health.setBackgroundTintList(ColorStateList.valueOf(healthColor(item)));
            b.check.setText(enabled ? "☑" : "☐");
            b.check.setTextColor(ColorStateList.valueOf(enabled ? 0xFF4CAF50 : 0xFF9E9E9E));
            b.text.setText(typeName(item.getType()) + " · " + item.getDesc());
            b.used.setVisibility(used ? View.VISIBLE : View.GONE);
            b.text.setOnClickListener(v -> listener.onSelect(item));
            b.check.setOnClickListener(v -> listener.onToggleEnabled(item, !enabled));
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
            boolean open = expanded.contains(item.getUrl());
            b.text.setText(item.getDesc());
            b.url.setText(item.getUrl());
            b.arrow.setText(open ? "▼" : "▶");
            b.summary.setText(summaryText(item));
            b.header.setOnClickListener(v -> toggleExpanded(item));
            b.arrow.setOnClickListener(v -> toggleExpanded(item));
            b.reprobe.setOnClickListener(v -> listener.onRefresh(item));
            b.delete.setOnClickListener(v -> listener.onDelete(item));
        }

        private CharSequence summaryText(Config item) {
            ProbeState s = probeStates.get(item.getUrl());
            if (s != null && s.probing) {
                return itemView.getContext().getString(R.string.source_probe_progress, s.done, s.total);
            }
            if (s != null && s.results != null) {
                int ok = 0;
                for (DepotProbe.Result r : s.results) if (r.ok) ok++;
                return itemView.getContext().getString(R.string.source_available, ok, s.results.size());
            }
            List<Config> kids = childrenByParent.get(item.getUrl());
            int n = kids == null ? 0 : kids.size();
            return itemView.getContext().getString(R.string.source_sources_count, n);
        }
    }

    /** 仅暴露 VOD 健康档读取，避免适配器直接耦合 InterfaceOrderStore 细节。 */
    private static class InterfaceOrderStoreHelper {
        static Map<String, Boolean> getVodHealth() {
            return com.fongmi.android.tv.setting.InterfaceOrderStore.getVodHealth();
        }
    }
}
