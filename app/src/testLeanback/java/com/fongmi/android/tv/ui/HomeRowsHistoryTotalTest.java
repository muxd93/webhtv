package com.fongmi.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ClassPresenterSelector;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.HomeMore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

/**
 * 删除历史后「更多」卡计数必须立刻跟上：旧代码只在 submitFeed 里写总数，
 * 删除走的是 refresh() 路径，卡片会一直显示删除前的数字。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HomeRowsHistoryTotalTest {

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
        selector.addClassPresenter(HomeMore.class, stub());
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(selector);
        adapter.addAll(0, Arrays.asList(items));
        return adapter;
    }

    private static HomeMore more(ArrayObjectAdapter adapter) {
        if (adapter.size() == 0) return null;
        Object last = adapter.get(adapter.size() - 1);
        return last instanceof HomeMore ? (HomeMore) last : null;
    }

    @Test
    public void oneDeleteUpdatesMoreCountImmediately() {
        // limit=3、库里共 5 条：删除一条后「更多」卡应立即变成 4
        ArrayObjectAdapter adapter = adapter(history("1"), history("2"), history("3"), new HomeMore(HomeMore.Kind.HISTORY, 5));
        adapter.removeItems(2, 1);
        int total = HomeRows.afterHistoryDeleted(adapter, 5);

        assertEquals(4, total);
        assertEquals(4, more(adapter).getCount());
        assertEquals("删除的卡片不能再留在适配器里", 3, adapter.size());
    }

    @Test
    public void twoConsecutiveDeletesStayInSync() {
        ArrayObjectAdapter adapter = adapter(history("1"), history("2"), history("3"), new HomeMore(HomeMore.Kind.HISTORY, 5));
        int total = 5;

        adapter.removeItems(2, 1);
        total = HomeRows.afterHistoryDeleted(adapter, total);
        assertEquals(4, total);
        assertEquals(4, more(adapter).getCount());

        adapter.removeItems(1, 1);
        total = HomeRows.afterHistoryDeleted(adapter, total);
        assertEquals("连续删除第二次不能还停留在 4", 3, total);
        assertEquals(3, more(adapter).getCount());
        assertEquals(2, adapter.size());
    }

    @Test
    public void moreCardRemovedWhenRemainingFitsInTheRow() {
        // 剩余总数已经不超过可见条目数时，「更多」卡不应继续存在
        ArrayObjectAdapter adapter = adapter(history("1"), history("2"), history("3"), new HomeMore(HomeMore.Kind.HISTORY, 4));
        int total = HomeRows.afterHistoryDeleted(adapter, 4);

        assertEquals(3, total);
        assertNull(more(adapter));
        assertEquals(3, adapter.size());
    }

    @Test
    public void totalNeverGoesNegative() {
        ArrayObjectAdapter adapter = adapter(new HomeMore(HomeMore.Kind.HISTORY, 1));
        assertEquals(0, HomeRows.afterHistoryDeleted(adapter, 0));
    }
}
