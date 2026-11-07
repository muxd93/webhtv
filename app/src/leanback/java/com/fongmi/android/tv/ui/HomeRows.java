package com.fongmi.android.tv.ui;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ClassPresenterSelector;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.PresenterSelector;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.HomeMore;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.HomeMorePresenter;
import com.fongmi.android.tv.ui.presenter.KeepPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class HomeRows {

    public static final int KEEP_LIMIT = 10;
    public static final int HISTORY_LIMIT = 10;

    /** 铺平铺网格子行的稳定 id 基址，用于 RowDiff 按 id 而非适配器实例做行级 diff */
    static final long HISTORY_ID_BASE = 1_000_000L;
    static final long KEEP_ID_BASE = 2_000_000L;

    public interface OnMoreClickListener {
        void onMoreClick(HomeMore.Kind kind);
    }

    private final VodPresenter.OnClickListener mVodListener;
    private final HistoryPresenter.OnClickListener mHistoryListener;
    private final FuncPresenter.OnClickListener mFuncListener;
    private final KeepPresenter.OnClickListener mKeepListener;
    private final OnMoreClickListener mMoreListener;

    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mFuncAdapter;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mKeepAdapter;
    private HistoryPresenter mHistoryPresenter;
    private KeepPresenter mKeepPresenter;
    private HomeSelector mHistorySelector;
    private HomeSelector mKeepSelector;
    private final List<ArrayObjectAdapter> mHistoryGrid = new ArrayList<>();
    private final List<ArrayObjectAdapter> mKeepGrid = new ArrayList<>();

    private List<Func> mFuncItems = Collections.emptyList();
    private List<History> mHistory = Collections.emptyList();
    private List<Keep> mKeeps = Collections.emptyList();
    private int mKeepTotal;
    private int mHistoryTotal;
    private Map<String, History> mProgress = Collections.emptyMap();
    private Map<Integer, String> mConfigNames = Collections.emptyMap();
    private List<Vod> mVodItems = Collections.emptyList();
    private Style mVodStyle = Style.list();
    private boolean mVodLoading;

    public HomeRows(VodPresenter.OnClickListener vod, HistoryPresenter.OnClickListener history, FuncPresenter.OnClickListener func, KeepPresenter.OnClickListener keep, OnMoreClickListener more) {
        mVodListener = vod;
        mHistoryListener = history;
        mFuncListener = func;
        mKeepListener = keep;
        mMoreListener = more;
        init();
    }

    private void init() {
        mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(mFuncListener));
        mHistoryPresenter = new HistoryPresenter(mHistoryListener);
        mHistorySelector = new HomeSelector(mHistoryPresenter);
        mHistorySelector.add(History.class, mHistoryPresenter);
        mHistorySelector.add(HomeMore.class, new HomeMorePresenter(mMoreListener));
        mHistoryAdapter = new ArrayObjectAdapter(mHistorySelector);
        mKeepPresenter = new KeepPresenter(mKeepListener);
        mKeepSelector = new HomeSelector(mKeepPresenter);
        mKeepSelector.add(Keep.class, mKeepPresenter);
        mKeepSelector.add(HomeMore.class, new HomeMorePresenter(mMoreListener));
        mKeepAdapter = new ArrayObjectAdapter(mKeepSelector);
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(Vod.class, new VodPresenter(mVodListener, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), FuncPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_SMALL, HorizontalGridView.FOCUS_SCROLL_ALIGNED), HistoryPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_SMALL, HorizontalGridView.FOCUS_SCROLL_ALIGNED), KeepPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_SMALL, HorizontalGridView.FOCUS_SCROLL_ALIGNED), HomeMorePresenter.class);
        mAdapter = new ArrayObjectAdapter(selector);
        mFuncAdapter.setItems(mFuncItems, new BaseDiffCallback<>());
        mHistoryAdapter.setItems(mHistory, new BaseDiffCallback<>());
        submitKeepItems(mProgress, mConfigNames);
        apply();
    }

    public ItemBridgeAdapter bridgeAdapter() {
        return new ItemBridgeAdapter(mAdapter);
    }

    public ArrayObjectAdapter historyAdapter() {
        return mHistoryAdapter;
    }

    public HistoryPresenter historyPresenter() {
        return mHistoryPresenter;
    }

    public ItemBridgeAdapter rebuild() {
        init();
        return bridgeAdapter();
    }

    /** 数据变化后整体重铺（删除单条等场景），按当前设置值重新铺平铺网格 */
    public void refresh() {
        apply();
    }

    /** 删除模式切换时通知所有历史网格子适配器重绑（保留焦点，不整体重建） */
    public void notifyHistoryChanged() {
        notifyRows(mHistoryGrid);
    }

    /** 删除模式切换时通知所有收藏网格子适配器重绑 */
    public void notifyKeepChanged() {
        notifyRows(mKeepGrid);
    }

    /**
     * 只重绑已上屏的行副本：站点名、跨配置标注、观看进度都在绑定时按当前状态实时解析，
     * 通知源适配器（mHistoryAdapter/mKeepAdapter 没有观察者）不会让卡片更新。
     */
    private void notifyRows(List<ArrayObjectAdapter> grid) {
        for (ArrayObjectAdapter adapter : grid) adapter.notifyArrayItemRangeChanged(0, adapter.size());
    }

    /** 删除一条历史后立即同步总数与「更多」卡，避免计数滞后到下一次刷新 */
    public void onHistoryDeleted() {
        mHistoryTotal = afterHistoryDeleted(mHistoryAdapter, mHistoryTotal);
    }

    /** 剩余总数已经装得下全部条目时移除「更多」卡，否则只更新卡上的计数 */
    static int afterHistoryDeleted(ArrayObjectAdapter adapter, int total) {
        int next = Math.max(0, total - 1);
        int moreIndex = adapter.size() > 0 && adapter.get(adapter.size() - 1) instanceof HomeMore ? adapter.size() - 1 : -1;
        if (moreIndex < 0) return next;
        if (next <= moreIndex) adapter.removeItems(moreIndex, 1);
        else adapter.replace(moreIndex, new HomeMore(HomeMore.Kind.HISTORY, next));
        return next;
    }

    public void submitFunc(List<Func> items) {
        mFuncItems = items == null ? Collections.emptyList() : new ArrayList<>(items);
        mFuncAdapter.setItems(mFuncItems, new BaseDiffCallback<>());
    }

    public void submitFeed(HomeFeed feed) {
        mHistory = feed.getHistory();
        mHistoryTotal = feed.getHistoryTotal();
        mKeeps = feed.getKeeps();
        mKeepTotal = feed.getKeepTotal();
        mProgress = feed.getProgress();
        mConfigNames = feed.getConfigNames();
        submitHistoryItems();
        submitKeepItems(mProgress, mConfigNames);
        apply();
    }

    public void submitVod(List<Vod> items, Style style, boolean loading) {
        mVodItems = items == null ? Collections.emptyList() : new ArrayList<>(items);
        mVodStyle = style == null ? Style.list() : style;
        mVodLoading = loading;
        apply();
    }

    public boolean isTopRow(int position) {
        for (int i = 0; i < mAdapter.size(); i++) {
            Object item = mAdapter.get(i);
            if (item instanceof Integer && (Integer) item == R.string.home_history) return position < i;
        }
        return true;
    }

    private void submitHistoryItems() {
        int limit = Setting.getHistoryCount();
        List<Object> items = new ArrayList<>(mHistory.subList(0, Math.min(mHistory.size(), limit)));
        if (mHistoryTotal > limit) items.add(new HomeMore(HomeMore.Kind.HISTORY, mHistoryTotal));
        if (sameItems(mHistoryAdapter, items)) {
            // 内容未变但每次进首页/切配置都会重新取 feed：重绑已上屏的历史行，
            // 否则站点名等按当前配置实时解析的字段会停留在旧状态
            notifyRows(mHistoryGrid);
            return;
        }
        mHistoryAdapter.clear();
        mHistoryAdapter.addAll(0, items);
    }

    private void submitKeepItems(Map<String, History> progress, Map<Integer, String> configNames) {
        mKeepPresenter.setProgress(progress);
        mKeepPresenter.setConfigNames(configNames);
        int limit = Setting.getKeepCount();
        List<Object> items = new ArrayList<>(mKeeps.subList(0, Math.min(mKeeps.size(), limit)));
        if (mKeepTotal > limit) items.add(new HomeMore(HomeMore.Kind.KEEP, mKeepTotal));
        if (sameItems(mKeepAdapter, items)) {
            // 观看进度与跨配置标注存在 presenter 上，重绑已上屏的收藏行才会生效
            notifyRows(mKeepGrid);
            return;
        }
        mKeepAdapter.clear();
        mKeepAdapter.addAll(0, items);
    }

    /** 网格每行列数，与推荐区保持一致，保证横向对齐 */
    private int gridColumn() {
        return Math.max(1, Product.getColumn(mVodStyle));
    }

    private List<Object> build() {
        List<Object> items = new ArrayList<>();
        items.add(new ListRow(mFuncAdapter));
        items.add(R.string.home_history);
        mHistoryGrid.clear();
        if (mHistoryAdapter.size() > 0) {
            int column = gridColumn();
            List<Object> data = new ArrayList<>(mHistoryAdapter.size());
            for (int i = 0; i < mHistoryAdapter.size(); i++) data.add(mHistoryAdapter.get(i));
            int row = 0;
            for (List<Object> part : Lists.partition(data, column)) {
                ArrayObjectAdapter adapter = new ArrayObjectAdapter(mHistorySelector);
                adapter.addAll(0, part);
                mHistoryGrid.add(adapter);
                items.add(new ListRow(HISTORY_ID_BASE + row, (HeaderItem) null, adapter));
                row++;
            }
        }
        if (!mKeeps.isEmpty() || mKeepTotal > 0) {
            items.add(R.string.home_keep);
            mKeepGrid.clear();
            if (mKeepAdapter.size() > 0) {
                int column = gridColumn();
                List<Object> data = new ArrayList<>(mKeepAdapter.size());
                for (int i = 0; i < mKeepAdapter.size(); i++) data.add(mKeepAdapter.get(i));
                int row = 0;
                for (List<Object> part : Lists.partition(data, column)) {
                    ArrayObjectAdapter adapter = new ArrayObjectAdapter(mKeepSelector);
                    adapter.addAll(0, part);
                    mKeepGrid.add(adapter);
                    items.add(new ListRow(KEEP_ID_BASE + row, (HeaderItem) null, adapter));
                    row++;
                }
            }
        }
        items.add(R.string.home_recommend);
        if (mVodStyle.isList()) {
            items.addAll(mVodItems);
        } else if (!mVodItems.isEmpty()) {
            VodPresenter presenter = new VodPresenter(mVodListener, mVodStyle);
            int column = Math.max(1, Product.getColumn(mVodStyle));
            for (List<Vod> part : Lists.partition(mVodItems, column)) {
                ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
                adapter.addAll(0, part);
                items.add(new ListRow(adapter));
            }
        }
        if (mVodLoading) items.add("progress");
        return items;
    }

    private void apply() {
        RowDiff.apply(new RowDiff.Target() {
            @Override
            public int size() {
                return mAdapter.size();
            }

            @Override
            public Object get(int index) {
                return mAdapter.get(index);
            }

            @Override
            public void removeAt(int index) {
                mAdapter.removeItems(index, 1);
            }

            @Override
            public void insertAt(int index, Object item) {
                mAdapter.add(index, item);
            }
        }, build(), HomeRows.this::key);
    }

    private String key(Object item) {
        if (item instanceof Integer) return "h:" + item;
        if (item instanceof String) return "s:" + item;
        if (item instanceof HomeMore) return "m:" + ((HomeMore) item).getKind();
        if (item instanceof Vod) return "vod:" + vodKey((Vod) item);
        if (item instanceof ListRow) {
            long id = ((ListRow) item).getId();
            if (id >= HISTORY_ID_BASE && id < HISTORY_ID_BASE + 1_000_000) return gridKey(HISTORY_ID_BASE, (int) (id - HISTORY_ID_BASE), ((ListRow) item).getAdapter());
            if (id >= KEEP_ID_BASE && id < KEEP_ID_BASE + 1_000_000) return gridKey(KEEP_ID_BASE, (int) (id - KEEP_ID_BASE), ((ListRow) item).getAdapter());
            ObjectAdapter child = ((ListRow) item).getAdapter();
            if (child == null) return "grid:null";
            if (child == mFuncAdapter) return "row:func";
            if (child == mHistoryAdapter) return "row:history";
            if (child == mKeepAdapter) return "row:keep";
            return "grid:" + rowKey(child);
        }
        return "obj:" + Objects.toString(item);
    }

    private String vodKey(Vod vod) {
        return Objects.toString(vod.getId()) + "|" + Objects.toString(vod.getName());
    }

    /**
     * 网格子行 key = 行号 + 内容指纹。已上屏的行适配器是 build() 时另建的副本，
     * 只按行号命中会把删除/内容的改动留在旧副本里；指纹变化时让 RowDiff 重建该行。
     */
    static String gridKey(long idBase, int row, ObjectAdapter adapter) {
        int size = adapter == null ? 0 : adapter.size();
        StringBuilder sb = new StringBuilder("row:").append(idBase).append(':').append(row).append(':').append(size);
        if (size > 0) sb.append(':').append(itemKey(adapter.get(0))).append(':').append(itemKey(adapter.get(size - 1)));
        return sb.toString();
    }

    private static String itemKey(Object item) {
        if (item instanceof History) return ((History) item).getKey();
        if (item instanceof Keep) return ((Keep) item).getKey();
        return Objects.toString(item);
    }

    private String rowKey(ObjectAdapter adapter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < adapter.size(); i++) {
            Object item = adapter.get(i);
            if (item instanceof Vod) sb.append(vodKey((Vod) item)).append(';');
        }
        return sb.toString();
    }

    private boolean sameItems(ArrayObjectAdapter adapter, List<Object> items) {
        if (adapter.size() != items.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            if (!sameItem(adapter.get(i), items.get(i))) return false;
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean sameItem(Object oldItem, Object newItem) {
        if (oldItem == null || newItem == null) return oldItem == newItem;
        if (oldItem instanceof Diffable && newItem instanceof Diffable && oldItem.getClass() == newItem.getClass()) {
            return ((Diffable) oldItem).isSameItem((Diffable) newItem);
        }
        return oldItem.equals(newItem);
    }

    /**
     * 行子适配器：主 Presenter 与 HomeMore 双 Presenter。
     * CustomSelector 的嵌套路径会用 ListRow 本身探测行适配器的子 Presenter
     * （row.getAdapter().getPresenter(row)），ClassPresenterSelector 对未注册的
     * ListRow 返回 null，会让顶层 ItemBridgeAdapter 拿到 null Presenter 而崩溃；
     * 回退到主 Presenter 保证探测永远非空（行内子项仍按各自类型分发）。
     */
    static class HomeSelector extends PresenterSelector {

        private final ClassPresenterSelector mDelegate;
        private final Presenter mFallback;

        HomeSelector(Presenter fallback) {
            mFallback = fallback;
            mDelegate = new ClassPresenterSelector();
        }

        void add(Class<?> cls, Presenter presenter) {
            mDelegate.addClassPresenter(cls, presenter);
        }

        @Override
        public Presenter getPresenter(Object item) {
            Presenter presenter = mDelegate.getPresenter(item);
            return presenter != null ? presenter : mFallback;
        }

        @Override
        public Presenter[] getPresenters() {
            return mDelegate.getPresenters();
        }
    }
}
