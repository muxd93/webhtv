package com.fongmi.android.tv.live;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** LIVE10 回归：整树三桶重排（render/softReload 共用）与统一邻台目的地（stepChannel/预热目标共用）。 */
public class LiveSessionTest {

    private static final Predicate<Group> SKIP_NONE = g -> false;

    @After
    public void reset() {
        LineHealth.resetForTest();
        LineBlockStore.resetForTest();
    }

    private static Group groupWithChannels(String name, int count) {
        Group group = new Group(name);
        List<Channel> channels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Channel channel = new Channel();
            channel.addLine("http://" + name + "-" + i, name + i);
            channels.add(channel);
        }
        group.setChannel(channels);
        return group;
    }

    // ---- reorderAll：软重载后新对象按 URL 命中健康/屏蔽状态恢复桶序 ----

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

    // ---- neighborDestination：与原 stepChannel/stepGroup 语义对拍 ----

    @Test
    public void neighborStepsWithinGroupAndWrapsWithoutAcross() {
        Group a = groupWithChannels("A", 3);
        List<Group> groups = Arrays.asList(a);
        a.setPosition(1);
        assertArrayEquals(new int[]{0, 2}, LiveSession.neighborDestination(a, groups, 1, false));
        assertArrayEquals(new int[]{0, 0}, LiveSession.neighborDestination(a, groups, -1, false));
        a.setPosition(2);
        assertArrayEquals(new int[]{0, 0}, LiveSession.neighborDestination(a, groups, 1, false));
        a.setPosition(0);
        assertArrayEquals(new int[]{0, 2}, LiveSession.neighborDestination(a, groups, -1, false));
    }

    @Test
    public void neighborCrossesGroupsSkippingSkipGroupsWhenAcross() {
        Group keep = groupWithChannels("K", 2);
        Group a = groupWithChannels("A", 2);
        Group b = groupWithChannels("B", 2);
        List<Group> groups = Arrays.asList(keep, a, b);
        Predicate<Group> skipKeep = keep::equals;
        a.setPosition(1);
        assertArrayEquals(new int[]{2, 0}, LiveSession.neighborDestination(a, groups, 1, true, skipKeep));
        b.setPosition(0);
        assertArrayEquals(new int[]{1, 1}, LiveSession.neighborDestination(b, groups, -1, true, skipKeep));
        b.setPosition(1);
        // B 尾继续下一台：跨过 skip 组环绕到 A 首
        assertArrayEquals(new int[]{1, 0}, LiveSession.neighborDestination(b, groups, 1, true, skipKeep));
        // across 关闭时同位置只组内环绕
        assertArrayEquals(new int[]{2, 0}, LiveSession.neighborDestination(b, groups, 1, false));
    }

    @Test
    public void neighborReentersSingleGroupAtCurrentPositionWhenAcross() {
        Group a = groupWithChannels("A", 3);
        List<Group> groups = Arrays.asList(a);
        a.setPosition(2);
        assertArrayEquals(new int[]{0, 2}, LiveSession.neighborDestination(a, groups, 1, true, SKIP_NONE));
        a.setPosition(0);
        assertArrayEquals(new int[]{0, 0}, LiveSession.neighborDestination(a, groups, -1, true, SKIP_NONE));
    }

    @Test
    public void neighborLandsOnOriginalGroupWhenAllOthersSkip() {
        Group keep1 = groupWithChannels("K1", 1);
        Group a = groupWithChannels("A", 2);
        Group keep2 = groupWithChannels("K2", 1);
        List<Group> groups = Arrays.asList(keep1, a, keep2);
        Predicate<Group> skipOthers = g -> g != a;
        a.setPosition(1);
        assertArrayEquals(new int[]{1, 0}, LiveSession.neighborDestination(a, groups, 1, true, skipOthers));
        a.setPosition(0);
        assertArrayEquals(new int[]{1, 1}, LiveSession.neighborDestination(a, groups, -1, true, skipOthers));
    }

    @Test
    public void neighborLandsEmptyGroupWithSentinelPositionAndLeavesEmptyCurrent() {
        Group a = groupWithChannels("A", 2);
        Group empty = new Group("E");
        List<Group> groups = Arrays.asList(a, empty);
        // 原语义 first?0:size-1：正向落 0、反向落 -1，空组由调用方 isEmpty 兜底不 tune
        a.setPosition(1);
        assertArrayEquals(new int[]{1, 0}, LiveSession.neighborDestination(a, groups, 1, true, SKIP_NONE));
        a.setPosition(0);
        assertArrayEquals(new int[]{1, -1}, LiveSession.neighborDestination(a, groups, -1, true, SKIP_NONE));
        // 空当前组也能跨出（默认 position=-1）
        assertArrayEquals(new int[]{0, 0}, LiveSession.neighborDestination(empty, groups, 1, true, SKIP_NONE));
        assertArrayEquals(new int[]{0, 1}, LiveSession.neighborDestination(empty, groups, -1, true, SKIP_NONE));
    }

    @Test
    public void neighborReturnsNullWithoutGroups() {
        Group a = groupWithChannels("A", 2);
        assertEquals(null, LiveSession.neighborDestination(a, Arrays.asList(), 1, true));
        assertEquals(null, LiveSession.neighborDestination(null, Arrays.asList(a), 1, true));
    }
}
