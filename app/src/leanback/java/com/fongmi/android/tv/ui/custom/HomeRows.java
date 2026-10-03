package com.fongmi.android.tv.ui.custom;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ListRow;

/**
 * 首页分区（标题+行）原子增删助手：行有数据时保证"标题在上、行在下"成对出现，
 * 行清空时把标题一并移除，避免出现没有内容行的孤儿标题。
 * anchors 为各分区标题资源 id 的固定先后顺序，插入新分区时对齐到下一个已存在分区之前。
 */
public class HomeRows {

    private final ArrayObjectAdapter adapter;
    private final int[] anchors;

    public HomeRows(ArrayObjectAdapter adapter, int... anchors) {
        this.adapter = adapter;
        this.anchors = anchors;
    }

    public void ensure(int header, ListRow row) {
        int index = adapter.indexOf(header);
        if (index == -1) {
            adapter.add(nextAnchorIndex(header), header);
            index = adapter.indexOf(header);
        }
        if (index + 1 >= adapter.size() || !(adapter.get(index + 1) instanceof ListRow)) adapter.add(index + 1, row);
    }

    public void remove(int header) {
        int index = adapter.indexOf(header);
        if (index == -1) return;
        if (index + 1 < adapter.size() && adapter.get(index + 1) instanceof ListRow) adapter.removeItems(index + 1, 1);
        adapter.removeItems(index, 1);
    }

    private int nextAnchorIndex(int header) {
        int order = -1;
        for (int i = 0; i < anchors.length; i++) if (anchors[i] == header) order = i;
        for (int i = order + 1; i < anchors.length; i++) {
            int index = adapter.indexOf(anchors[i]);
            if (index != -1) return index;
        }
        return adapter.size();
    }
}
