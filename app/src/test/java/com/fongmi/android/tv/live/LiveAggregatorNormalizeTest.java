package com.fongmi.android.tv.live;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 归一化纯函数的合并语义回归：错合会吞掉真实频道，漏合会拆散同台多线路。 */
public class LiveAggregatorNormalizeTest {

    @Test
    public void separatorVariantsMergeToOneKey() {
        assertEquals("CCTV1", LiveAggregator.normalizeChannel("CCTV-1"));
        assertEquals("CCTV1", LiveAggregator.normalizeChannel("cctv 1"));
        assertEquals("CCTV1", LiveAggregator.normalizeChannel("CCTV。1"));
    }

    @Test
    public void caseAndFullwidthMerge() {
        assertEquals("CCTV1", LiveAggregator.normalizeChannel("ＣＣＴＶ１"));
        assertEquals("CCTV1", LiveAggregator.normalizeChannel("CCTV１"));
        assertEquals("CCTV1综合", LiveAggregator.normalizeChannel("CCTV1 综合"));
    }

    @Test
    public void plusSignIsPreserved() {
        assertEquals("CCTV5+", LiveAggregator.normalizeChannel("CCTV5+"));
        assertEquals("CCTV5+", LiveAggregator.normalizeChannel("CCTV5＋"));
    }

    @Test
    public void traditionalMergesWithSimplified() {
        assertEquals(LiveAggregator.normalizeChannel("央视一套"), LiveAggregator.normalizeChannel("央視一套"));
        assertEquals("央视一套", LiveAggregator.normalizeChannel("央視一套"));
    }

    @Test
    public void nullAndEmptySafe() {
        assertEquals("", LiveAggregator.normalizeChannel(null));
        assertEquals("", LiveAggregator.normalizeChannel(""));
    }

    @Test
    public void groupFamiliesCollapse() {
        assertEquals("央视", LiveAggregator.normalizeGroup("CCTV频道"));
        assertEquals("央视", LiveAggregator.normalizeGroup("央視頻道"));
        assertEquals("央视", LiveAggregator.normalizeGroup("CGTN专区"));
        assertEquals("卫视", LiveAggregator.normalizeGroup("卫视台"));
        assertEquals("卫视", LiveAggregator.normalizeGroup("省 卫视"));
        assertEquals("体育", LiveAggregator.normalizeGroup("体育"));
        assertEquals("凤凰中文", LiveAggregator.normalizeGroup("凤凰中文"));
    }

    @Test
    public void fixedOrderBuckets() {
        assertEquals(0, LiveAggregator.order("央视"));
        assertEquals(1, LiveAggregator.order("卫视"));
        assertEquals(2, LiveAggregator.order("地方台"));
        assertEquals(3, LiveAggregator.order("体育"));
        assertEquals(3, LiveAggregator.order("新电影频道"));
        assertEquals(4, LiveAggregator.order("凤凰中文"));
    }
}
