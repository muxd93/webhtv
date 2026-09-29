package com.fongmi.android.tv.live;

import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Memoizes the measured column widths used by the live list UI. Replaces the
 * width fields that used to live on Live/Group/Epg so the beans stay pure
 * data. Entries are weakly held and recomputed after a config switch drops
 * the old objects.
 */
public final class LiveWidthCache {

    private static final Map<Live, Integer> LIVE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Group, Integer> GROUP = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Epg, Integer> EPG = Collections.synchronizedMap(new WeakHashMap<>());

    private LiveWidthCache() {
    }

    public static int get(Live live) {
        Integer width = LIVE.get(live);
        return width == null ? 0 : width;
    }

    public static void put(Live live, int width) {
        LIVE.put(live, width);
    }

    public static int get(Group group) {
        Integer width = GROUP.get(group);
        return width == null ? 0 : width;
    }

    public static void put(Group group, int width) {
        GROUP.put(group, width);
    }

    public static int get(Epg epg) {
        Integer width = EPG.get(epg);
        return width == null ? 0 : width;
    }

    public static void put(Epg epg, int width) {
        EPG.put(epg, width);
    }
}
