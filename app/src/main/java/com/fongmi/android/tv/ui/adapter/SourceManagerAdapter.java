package com.fongmi.android.tv.ui.adapter;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
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
import com.fongmi.android.tv.databinding.AdapterSourceSectionBinding;
import com.fongmi.android.tv.source.SourceState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订阅仓分组树（SRCUI1）：配置身份是 (type,url)，全部状态键一律复合键，
 * 同 URL 的点播仓/直播仓各自独立分组、独立折叠。独立源（无 parentUrl）归入
 * "独立源"分区，不再与折叠后的仓头粘连。rebuild 保留展开状态与探测结果，
 * 仅对新增组默认展开。
 */
public class SourceManagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SECTION = 0;
    private static final int TYPE_HEADER = 1;
    private static final int TYPE_ITEM = 2;

    private static final int COLOR_UNKNOWN = 0xFF9E9E9E;
    private static final int COLOR_OK = 0xFF4CAF50;
    private static final int COLOR_BAD = 0xFFE53935;
    private static final int FOCUS_FILL = 0x66383C73;
    private static final int FOCUS_STROKE = 0xE6C7C2FF;
    private static final float FOCUS_RADIUS_DP = 6f;

    public interface OnActionListener {
        void onSelect(Config item);

        void onToggleEnabled(Config item, boolean enabled);

        void onDelete(Config item);

        void onRefresh(Config depot);
    }

    /** 展示行：分区标题、仓组头、源条目三态共用。 */
    public static class Row {
        final int type;
        final int sectionTitle;
        final Group group;
        final Config config;

        Row(int sectionTitle) {
            this.type = TYPE_SECTION;
            this.sectionTitle = sectionTitle;
            this.group = null;
            this.config = null;
        }

        Row(Group group) {
            this.type = TYPE_HEADER;
            this.sectionTitle = 0;
            this.group = group;
            this.config = null;
        }

        Row(Config config) {
            this.type = TYPE_ITEM;
            this.sectionTitle = 0;
            this.group = null;
            this.config = config;
        }
    }

    /** 仓组：root 为仓根配置，children 仅收 type 一致的直接子源。 */
    private static class Group {
        final Config root;
        final int type;
        final String key;
        final List<Config> children = new ArrayList<>();

        Group(Config root) {
            this.root = root;
            this.type = root.getType();
            this.key = key(type, root.getUrl());
        }
    }

    /** 单仓探测瞬时状态：驱动组头摘要文案与进度。 */
    private static class ProbeState {
        boolean probing;
        int done;
        int total;
        List<DepotProbe.Result> results;
    }

    private final OnActionListener listener;
    private final List<Row> rows = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final List<Config> orphans = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private final Set<String> knownGroups = new HashSet<>();
    private final Map<String, Boolean> lastProbe = new HashMap<>();
    private final Map<String, ProbeState> probeStates = new HashMap<>();
    /** 类型过滤（SRCUI4 起必选）：仅收该类型的仓组与独立源，独立"数据源管理"入口已删除。 */
    private int typeFilter;

    public SourceManagerAdapter(OnActionListener listener) {
        this.listener = listener;
    }

    public SourceManagerAdapter type(int filter) {
        typeFilter = filter;
        return this;
    }

    public SourceManagerAdapter build() {
        gather();
        rebuildRows();
        return this;
    }

    /** 重新从数据库拉取（新增/删除订阅后调用）；保留展开状态与探测结果。 */
    public void rebuild() {
        gather();
        rebuildRows();
        notifyDataSetChanged();
    }

    private static String key(int type, String url) {
        return type + "|" + url;
    }

    private void gather() {
        rows.clear();
        groups.clear();
        orphans.clear();
        List<Config> all = new ArrayList<>();
        for (int t = 0; t <= 2; t++) {
            if (t != typeFilter) continue;
            all.addAll(Config.getAll(t));
        }
        Map<String, List<Config>> byParent = new HashMap<>();
        for (Config c : all) {
            if (c.isDepot()) {
                Group g = new Group(c);
                groups.add(g);
                // 新出现的组默认展开；已见过的组沿用用户折叠状态（B5）
                if (knownGroups.add(g.key)) expanded.add(g.key);
            } else if (!TextUtils.isEmpty(c.getParentUrl())) {
                String k = key(c.getType(), c.getParentUrl());
                List<Config> kids = byParent.get(k);
                if (kids == null) byParent.put(k, kids = new ArrayList<>());
                kids.add(c);
            } else {
                orphans.add(c);
            }
        }
        for (Group g : groups) {
            List<Config> kids = byParent.remove(g.key);
            if (kids != null) g.children.addAll(kids);
        }
        // 幽灵子源：parentUrl 指向的仓根不存在（崩溃残留），按独立源兜底展示，可手动删除
        for (List<Config> kids : byParent.values()) orphans.addAll(kids);
        Set<String> liveKeys = new HashSet<>();
        for (Group g : groups) liveKeys.add(g.key);
        expanded.retainAll(liveKeys);
        knownGroups.retainAll(liveKeys);
        probeStates.keySet().retainAll(liveKeys);
    }

    private void rebuildRows() {
        rows.clear();
        // 单一形态时省略节头（Wall 无仓、纯仓列表皆无需"独立源/订阅仓"标签）
        boolean showSections = !groups.isEmpty() && !orphans.isEmpty();
        if (showSections) rows.add(new Row(R.string.source_section_depots));
        for (Group g : groups) {
            rows.add(new Row(g));
            if (expanded.contains(g.key)) for (Config k : g.children) rows.add(new Row(k));
        }
        if (showSections) rows.add(new Row(R.string.source_section_standalone));
        for (Config o : orphans) rows.add(new Row(o));
    }

    /** 定位删除（保 TV 焦点）：仓根连同级联子源区间移除；子源/独立源单行移除。失败返回 false（调用方兜底 rebuild）。 */
    public boolean remove(Config target) {
        String k = key(target.getType(), target.getUrl());
        int pos = indexOfHeader(k);
        if (pos >= 0) {
            Group g = group(k);
            int n = 1 + (expanded.contains(k) ? g.children.size() : 0);
            rows.subList(pos, pos + n).clear();
            groups.remove(g);
            knownGroups.remove(k);
            expanded.remove(k);
            probeStates.remove(k);
            if (n > 0) notifyItemRangeRemoved(pos, n);
            return true;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.type != TYPE_ITEM || r.config.getType() != target.getType() || !TextUtils.equals(r.config.getUrl(), target.getUrl())) continue;
            rows.remove(i);
            Group owner = TextUtils.isEmpty(r.config.getParentUrl()) ? null : group(key(r.config.getType(), r.config.getParentUrl()));
            if (owner != null) owner.children.remove(r.config);
            else orphans.remove(r.config);
            notifyItemRemoved(i);
            return true;
        }
        return false;
    }

    /** 定位单行刷新（保 TV 焦点）。 */
    public void notifyItemChanged(Config target) {
        String k = key(target.getType(), target.getUrl());
        int pos = indexOfHeader(k);
        if (pos >= 0) {
            notifyItemChanged(pos);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.type == TYPE_ITEM && r.config.getType() == target.getType() && TextUtils.equals(r.config.getUrl(), target.getUrl())) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    private Group group(String key) {
        for (Group g : groups) if (g.key.equals(key)) return g;
        return null;
    }

    private int indexOfHeader(String key) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.type == TYPE_HEADER && r.group.key.equals(key)) return i;
        }
        return -1;
    }

    private void toggleExpanded(Group g) {
        int pos = indexOfHeader(g.key);
        if (pos < 0) return;
        if (expanded.contains(g.key)) {
            expanded.remove(g.key);
            int n = g.children.size();
            if (n > 0) {
                rows.subList(pos + 1, pos + 1 + n).clear();
                notifyItemRangeRemoved(pos + 1, n);
            }
            notifyItemChanged(pos);
        } else {
            expanded.add(g.key);
            int start = pos + 1;
            int n = 0;
            for (Config k : g.children) rows.add(start + n++, new Row(k));
            if (n > 0) notifyItemRangeInserted(start, n);
            notifyItemChanged(pos);
        }
    }

    // ---- 探测状态入口（由 Dialog 的异步回调驱动） ----

    public void setProbeState(Config depot, int total) {
        ProbeState s = state(depot);
        s.probing = true;
        s.done = 0;
        s.total = total;
        s.results = null;
    }

    public void setProbeProgress(Config depot, int done, int total) {
        ProbeState s = state(depot);
        s.probing = true;
        s.done = done;
        s.total = total;
    }

    public void setProbeResult(Config depot, List<DepotProbe.Result> results) {
        ProbeState s = state(depot);
        s.probing = false;
        s.done = results == null ? 0 : results.size();
        s.total = s.done;
        s.results = results;
        if (results != null) for (DepotProbe.Result r : results) lastProbe.put(key(depot.getType(), r.url), r.ok);
    }

    private ProbeState state(Config depot) {
        String k = key(depot.getType(), depot.getUrl());
        ProbeState s = probeStates.get(k);
        if (s == null) probeStates.put(k, s = new ProbeState());
        return s;
    }

    public void notifyHeaderChanged(Config depot) {
        int p = indexOfHeader(key(depot.getType(), depot.getUrl()));
        if (p >= 0) notifyItemChanged(p);
    }

    public void notifyChildrenChanged(Config depot) {
        String k = key(depot.getType(), depot.getUrl());
        int p = indexOfHeader(k);
        if (p < 0) return;
        Group g = group(k);
        int n = g == null ? 0 : g.children.size();
        if (n > 0 && expanded.contains(k)) notifyItemRangeChanged(p + 1, n);
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
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SECTION) return new SectionHolder(AdapterSourceSectionBinding.inflate(inflater, parent, false));
        if (viewType == TYPE_HEADER) return new HeaderHolder(AdapterSourceHeaderBinding.inflate(inflater, parent, false));
        return new ItemHolder(AdapterSourceItemBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof SectionHolder) ((SectionHolder) holder).bind(row.sectionTitle);
        else if (holder instanceof HeaderHolder) ((HeaderHolder) holder).bind(row.group);
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
        Boolean probe = lastProbe.get(key(item.getType(), item.getUrl()));
        if (probe != null) return probe ? COLOR_OK : COLOR_BAD;
        Boolean ok = InterfaceOrderStoreHelper.getHealth(item.getType()).get(item.getUrl());
        if (ok == null) return COLOR_UNKNOWN;
        return ok ? COLOR_OK : COLOR_BAD;
    }

    /** 行/组头聚焦玻璃描边（与 selector_dialog_glass_chip 同族配色），失焦还原。 */
    private void bindFocusVisual(View view) {
        view.setOnFocusChangeListener((v, focused) -> {
            if (!focused) {
                v.setBackground(null);
                return;
            }
            float density = v.getResources().getDisplayMetrics().density;
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(FOCUS_FILL);
            drawable.setStroke(Math.round(2 * density), FOCUS_STROKE);
            drawable.setCornerRadius(FOCUS_RADIUS_DP * density);
            v.setBackground(drawable);
        });
    }

    /** 仅暴露健康档读取，避免适配器直接耦合 InterfaceOrderStore 细节。 */
    private static class InterfaceOrderStoreHelper {
        static Map<String, Boolean> getHealth(int type) {
            return com.fongmi.android.tv.setting.InterfaceOrderStore.getHealth(type);
        }
    }

    public class SectionHolder extends RecyclerView.ViewHolder {
        private final AdapterSourceSectionBinding b;

        public SectionHolder(AdapterSourceSectionBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        public void bind(int titleRes) {
            b.text.setText(titleRes);
        }
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
            b.getRoot().setAlpha(enabled ? 1f : 0.45f);
            b.check.setOnCheckedChangeListener(null);
            b.check.setChecked(enabled);
            b.check.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onToggleEnabled(item, isChecked));
            b.text.setText(typeName(item.getType()) + " · " + item.getDesc());
            b.used.setVisibility(used ? View.VISIBLE : View.GONE);
            // 使用中的源不允许就地删除（与历史列表规则一致），先切换再删
            b.delete.setVisibility(used ? View.GONE : View.VISIBLE);
            b.text.setOnClickListener(v -> listener.onSelect(item));
            bindFocusVisual(b.text);
            b.delete.setOnClickListener(v -> listener.onDelete(item));
        }
    }

    public class HeaderHolder extends RecyclerView.ViewHolder {
        private final AdapterSourceHeaderBinding b;

        public HeaderHolder(AdapterSourceHeaderBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        public void bind(Group g) {
            Config item = g.root;
            boolean open = expanded.contains(g.key);
            boolean empty = g.children.isEmpty();
            b.text.setText(item.getDesc());
            if (TextUtils.equals(item.getDesc(), item.getUrl())) {
                b.url.setVisibility(View.GONE);
            } else {
                b.url.setVisibility(View.VISIBLE);
                b.url.setText(item.getUrl());
            }
            // 空仓（未激活）：无子源可展开/探测，箭头与刷新一并隐藏
            b.arrow.setVisibility(empty ? View.GONE : View.VISIBLE);
            b.arrow.setRotation(open ? 0f : -90f);
            b.reprobe.setVisibility(empty ? View.GONE : View.VISIBLE);
            b.summary.setText(summaryText(g));
            b.header.setOnClickListener(v -> toggleExpanded(g));
            bindFocusVisual(b.header);
            b.reprobe.setOnClickListener(v -> listener.onRefresh(item));
            b.delete.setOnClickListener(v -> listener.onDelete(item));
        }

        private CharSequence summaryText(Group g) {
            ProbeState s = probeStates.get(g.key);
            if (s != null && s.probing) {
                return itemView.getContext().getString(R.string.source_probe_progress, s.done, s.total);
            }
            if (s != null && s.results != null) {
                int ok = 0;
                for (DepotProbe.Result r : s.results) if (r.ok) ok++;
                return itemView.getContext().getString(R.string.source_available, ok, s.results.size());
            }
            // 已打仓标但尚未激活（无子源）：明示"未激活"，避免 0 个源的困惑
            if (g.children.isEmpty()) return itemView.getContext().getString(R.string.source_depot_inactive);
            return itemView.getContext().getString(R.string.source_sources_count, g.children.size());
        }
    }
}
