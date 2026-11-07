package com.fongmi.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ClassPresenterSelector;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.bean.History;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 首页历史行按「行号 + 内容指纹」做 RowDiff：已上屏的行适配器是 build() 时另建的副本，
 * 只按行号命中会让删除/内容的改动留在旧副本里，用户看到的就是「删掉的历史卡片还在」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HomeRowsGridKeyTest {

    private static History history(String key) {
        History item = new History();
        item.setKey(key);
        item.setVodName(key);
        return item;
    }

    private static Presenter stub() {
        return new Presenter() {
            @Override
            public Presenter.ViewHolder onCreateViewHolder(android.view.ViewGroup parent) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ArrayObjectAdapter adapter(Object... items) {
        ClassPresenterSelector selector = new ClassPresenterSelector();
        selector.addClassPresenter(History.class, stub());
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(selector);
        adapter.addAll(0, Arrays.asList(items));
        return adapter;
    }

    private static ListRow historyRow(int index, Object... items) {
        return new ListRow(HomeRows.HISTORY_ID_BASE + index, (HeaderItem) null, adapter(items));
    }

    private static List<Object> rowItems(ListRow row) {
        ArrayObjectAdapter items = (ArrayObjectAdapter) row.getAdapter();
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) result.add(items.get(i));
        return result;
    }

    private static String keyOf(Object item) {
        if (item instanceof ListRow) {
            long id = ((ListRow) item).getId();
            return HomeRows.gridKey(HomeRows.HISTORY_ID_BASE, (int) (id - HomeRows.HISTORY_ID_BASE), ((ListRow) item).getAdapter());
        }
        return "h:" + item;
    }

    private static class Rows implements RowDiff.Target {

        private final List<Object> rows;

        private Rows(Object... rows) {
            this.rows = new ArrayList<>(Arrays.asList(rows));
        }

        @Override
        public int size() {
            return rows.size();
        }

        @Override
        public Object get(int index) {
            return rows.get(index);
        }

        @Override
        public void removeAt(int index) {
            rows.remove(index);
        }

        @Override
        public void insertAt(int index, Object item) {
            rows.add(index, item);
        }
    }

    @Test
    public void deletedHistoryItemDisappearsFromMountedRow() {
        // 回归：删除一条历史后 refresh() 重新 build()，行号仍是 0，旧行不能被原地复用
        Rows mounted = new Rows(historyRow(0, history("1"), history("2"), history("3")));
        RowDiff.apply(mounted, Arrays.asList(historyRow(0, history("1"), history("2"))), HomeRowsGridKeyTest::keyOf);
        assertEquals(Arrays.asList("1", "2"), Arrays.asList(keysOf(rowItems((ListRow) mounted.get(0)))));
    }

    @Test
    public void sameRowIndexWithChangedContentRebuildsRow() {
        // 行数设置档位变化后，同一行号下装的是不同卡片
        Rows mounted = new Rows(historyRow(0, history("1"), history("2"), history("3")));
        RowDiff.apply(mounted, Arrays.asList(historyRow(0, history("1"), history("3"), history("4"))), HomeRowsGridKeyTest::keyOf);
        assertEquals(Arrays.asList("1", "3", "4"), Arrays.asList(keysOf(rowItems((ListRow) mounted.get(0)))));
    }

    @Test
    public void identicalContentKeepsRowInstance() {
        ListRow row = historyRow(0, history("1"), history("2"));
        Rows mounted = new Rows(row);
        RowDiff.apply(mounted, Arrays.asList(historyRow(0, history("1"), history("2"))), HomeRowsGridKeyTest::keyOf);
        assertSame("内容未变时不应重建行，避免焦点与图片重复加载", row, mounted.get(0));
    }

    private static String[] keysOf(List<Object> items) {
        String[] keys = new String[items.size()];
        for (int i = 0; i < items.size(); i++) keys[i] = ((History) items.get(i)).getKey();
        return keys;
    }
}
