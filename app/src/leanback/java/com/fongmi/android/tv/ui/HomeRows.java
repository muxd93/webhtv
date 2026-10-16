package com.fongmi.android.tv.ui;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ClassPresenterSelector;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.PresenterSelector;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
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

    private List<Func> mFuncItems = Collections.emptyList();
    private List<History> mHistory = Collections.emptyList();
    private List<Keep> mKeeps = Collections.emptyList();
    private int mKeepTotal;
    private Map<String, History> mProgress = Collections.emptyMap();
    private List<Vod> mVodItems = Collections.emptyList();
    private Style mVodStyle = Style.list();
    private boolean mVodLoading;
    private Map<String, History> mAppliedProgress = Collections.emptyMap();

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
        mHistoryAdapter = new ArrayObjectAdapter(mHistoryPresenter);
        mKeepPresenter = new KeepPresenter(mKeepListener);
        KeepSelector keepSelector = new KeepSelector(mKeepPresenter);
        keepSelector.add(Keep.class, mKeepPresenter);
        keepSelector.add(HomeMore.class, new HomeMorePresenter(mMoreListener));
        mKeepAdapter = new ArrayObjectAdapter(keepSelector);
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
        submitKeepItems(mProgress);
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

    public void submitFunc(List<Func> items) {
        mFuncItems = items == null ? Collections.emptyList() : new ArrayList<>(items);
        mFuncAdapter.setItems(mFuncItems, new BaseDiffCallback<>());
    }

    public void submitFeed(HomeFeed feed) {
        mHistory = feed.getHistory();
        mKeeps = feed.getKeeps();
        mKeepTotal = feed.getKeepTotal();
        mProgress = feed.getProgress();
        mHistoryAdapter.setItems(mHistory, new BaseDiffCallback<>());
        submitKeepItems(mProgress);
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

    private void submitKeepItems(Map<String, History> progress) {
        mKeepPresenter.setProgress(progress);
        List<Object> items = new ArrayList<>(mKeeps.subList(0, Math.min(mKeeps.size(), KEEP_LIMIT)));
        if (mKeepTotal > KEEP_LIMIT) items.add(new HomeMore(HomeMore.Kind.KEEP, mKeepTotal));
        if (sameItems(mKeepAdapter, items)) {
            if (mKeepAdapter.size() > 0 && !progress.equals(mAppliedProgress)) mKeepAdapter.notifyArrayItemRangeChanged(0, mKeepAdapter.size());
            mAppliedProgress = progress;
            return;
        }
        mAppliedProgress = progress;
        mKeepAdapter.clear();
        mKeepAdapter.addAll(0, items);
    }

    private List<Object> build() {
        List<Object> items = new ArrayList<>();
        items.add(new ListRow(mFuncAdapter));
        items.add(R.string.home_history);
        if (!mHistory.isEmpty()) items.add(new ListRow(mHistoryAdapter));
        if (!mKeeps.isEmpty() || mKeepTotal > 0) {
            items.add(R.string.home_keep);
            items.add(new ListRow(mKeepAdapter));
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
     * 收藏行子适配器：Keep 与 HomeMore 双 Presenter。
     * CustomSelector 的嵌套路径会用 ListRow 本身探测行适配器的子 Presenter
     * （row.getAdapter().getPresenter(row)），ClassPresenterSelector 对未注册的
     * ListRow 返回 null，会让顶层 ItemBridgeAdapter 拿到 null Presenter 而崩溃；
     * 回退到 KeepPresenter 保证探测永远非空（行内子项仍按各自类型分发）。
     */
    static class KeepSelector extends PresenterSelector {

        private final ClassPresenterSelector mDelegate;
        private final Presenter mFallback;

        KeepSelector(Presenter fallback) {
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
