package com.fongmi.android.tv.ui;

import static org.junit.Assert.assertSame;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.bean.HomeMore;
import com.fongmi.android.tv.bean.Keep;

import org.junit.Test;

public class HomeSelectorTest {

    private static Presenter dummy() {
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

    @Test
    public void dispatchesRegisteredChildTypes() {
        Presenter keep = dummy();
        Presenter more = dummy();
        HomeRows.HomeSelector selector = new HomeRows.HomeSelector(keep);
        selector.add(Keep.class, keep);
        selector.add(HomeMore.class, more);
        assertSame(keep, selector.getPresenter(new Keep()));
        assertSame(more, selector.getPresenter(new HomeMore(HomeMore.Kind.KEEP, 3)));
    }

    @Test
    public void fallsBackForUnregisteredProbeItems() {
        Presenter keep = dummy();
        HomeRows.HomeSelector selector = new HomeRows.HomeSelector(keep);
        selector.add(Keep.class, keep);
        // CustomSelector 用 ListRow 本身探测行适配器；ClassPresenterSelector 原样会返回 null，
        // 导致顶层 ItemBridgeAdapter 拿到 null Presenter 崩溃（回归锁）
        assertSame(keep, selector.getPresenter(new ListRow(new ArrayObjectAdapter())));
        assertSame(keep, selector.getPresenter("progress"));
        assertSame(keep, selector.getPresenter(null));
    }
}
