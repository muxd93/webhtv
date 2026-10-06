package com.fongmi.android.tv.live;

import static org.junit.Assert.assertEquals;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;

/** LIVE10 整树三桶重排回归：render/softReload 共用的 reorderAll，新对象按 URL 命中健康/屏蔽状态恢复桶序。 */
public class LiveSessionTest {

    @After
    public void reset() {
        LineHealth.resetForTest();
        LineBlockStore.resetForTest();
    }

    @Test
    public void reorderAllRestoresBucketsAcrossGroups() {
        Group group = new Group("组");
        Channel first = new Channel();
        first.addLine("http://b", "B");
        first.addLine("http://a", "A");
        Channel second = new Channel();
        second.addLine("http://d", "D");
        second.addLine("http://c", "C");
        group.setChannel(Arrays.asList(first, second));
        // 模拟软重载：健康/屏蔽状态按 URL 记忆，但频道是全新对象、顺序为解析原序
        LineBlockStore.block("http://b", "B");
        for (int i = 0; i < 3; i++) LineHealth.failure("http://d");
        LiveSession.reorderAll(Arrays.asList(group));
        assertEquals("[http://a, http://b]", first.getUrls().toString());
        assertEquals("[http://c, http://d]", second.getUrls().toString());
    }

    @Test
    public void reorderAllKeepsCleanTreeUnchanged() {
        Group group = new Group("组");
        Channel channel = new Channel();
        channel.addLine("http://a", "A");
        channel.addLine("http://b", "B");
        group.setChannel(Arrays.asList(channel));
        LiveSession.reorderAll(Arrays.asList(group));
        assertEquals("[http://a, http://b]", channel.getUrls().toString());
    }
}
