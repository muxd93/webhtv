package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 下载页全表刷新闸门：200 条进度事件只能触发 1 次在途查询 + 1 次合并后的补查 */
public class DownloadQueryGateTest {

    @Test
    public void eventStormCollapsesToOneFollowUpQuery() {
        DownloadActivity.QueryGate gate = new DownloadActivity.QueryGate();
        int started = 0;

        for (int i = 0; i < 200; i++) if (gate.tryStart()) started++;
        assertEquals("首次事件应启动查询，其余合并", 1, started);

        assertTrue("合并标记必须在查询结束后触发一次补查", gate.finish());
        assertFalse("补查期间没有新事件时不应再排队", gate.finish());
    }

    @Test
    public void noFollowUpWhenNoEventArrived() {
        DownloadActivity.QueryGate gate = new DownloadActivity.QueryGate();
        assertTrue(gate.tryStart());
        assertFalse(gate.finish());
        assertTrue("没有新事件时下一次事件仍可直接发起查询", gate.tryStart());
    }
}
