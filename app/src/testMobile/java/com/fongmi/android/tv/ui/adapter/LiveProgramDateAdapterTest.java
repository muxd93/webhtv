package com.fongmi.android.tv.ui.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Epg;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 日期条点击必须按点击当时的适配器位置取数据；列表被替换或 ViewHolder 已回收时不能点出旧日期。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class LiveProgramDateAdapterTest {

    private final List<Epg> clicked = new ArrayList<>();

    private LiveProgramDateAdapter create() {
        LiveProgramDateAdapter adapter = new LiveProgramDateAdapter(clicked::add);
        adapter.setItems(Arrays.asList(Epg.create("a", "2026-11-05"), Epg.create("b", "2026-11-06"), Epg.create("c", "2026-11-07")), "2026-11-06");
        return adapter;
    }

    @Test
    public void clickUsesItemAtCurrentPosition() {
        LiveProgramDateAdapter adapter = create();
        adapter.onDateSelected(2);
        assertEquals("2026-11-07", clicked.get(0).getDate());
    }

    @Test
    public void noPositionIsIgnored() {
        create().onDateSelected(RecyclerView.NO_POSITION);
        assertTrue(clicked.isEmpty());
    }

    @Test
    public void replacedListDropsStalePosition() {
        LiveProgramDateAdapter adapter = create();
        adapter.setItems(Arrays.asList(Epg.create("x", "2026-11-08"), Epg.create("y", "2026-11-09")), "2026-11-08");
        // 旧列表第 3 项的位置在新列表里已越界：必须丢弃，不能回落到旧捕获的日期
        adapter.onDateSelected(2);
        assertTrue(clicked.isEmpty());
        adapter.onDateSelected(0);
        assertEquals("2026-11-08", clicked.get(0).getDate());
    }

    @Test
    public void selectedIndexFollowsClicks() {
        LiveProgramDateAdapter adapter = create();
        adapter.onDateSelected(0);
        assertEquals("2026-11-05", adapter.getSelected().getDate());
        adapter.onDateSelected(2);
        assertEquals("2026-11-07", adapter.getSelected().getDate());
    }
}
