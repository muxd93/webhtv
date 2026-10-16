package com.fongmi.android.tv.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class RowDiffTest {

    private static String keyOf(Object item) {
        return Objects.toString(item);
    }

    private static class ListTarget implements RowDiff.Target {

        private final List<Object> items;

        private ListTarget(Object... initial) {
            items = new ArrayList<>(Arrays.asList(initial));
        }

        @Override
        public int size() {
            return items.size();
        }

        @Override
        public Object get(int index) {
            return items.get(index);
        }

        @Override
        public void removeAt(int index) {
            items.remove(index);
        }

        @Override
        public void insertAt(int index, Object item) {
            items.add(index, item);
        }

        private List<Object> result() {
            return items;
        }
    }

    private static void assertDiff(Object[] before, Object[] after, Object[] expected) {
        ListTarget target = new ListTarget(before);
        RowDiff.apply(target, Arrays.asList(after), RowDiffTest::keyOf);
        assertEquals(Arrays.asList(expected), target.result());
    }

    @Test
    public void identicalKeysProduceNoMutation() {
        assertDiff(new Object[]{"a", "b"}, new Object[]{"a", "b"}, new Object[]{"a", "b"});
    }

    @Test
    public void appendAtTail() {
        assertDiff(new Object[]{"a", "b"}, new Object[]{"a", "b", "c"}, new Object[]{"a", "b", "c"});
    }

    @Test
    public void removeFromTail() {
        assertDiff(new Object[]{"a", "b", "c"}, new Object[]{"a", "b"}, new Object[]{"a", "b"});
    }

    @Test
    public void insertInMiddleKeepsPrefix() {
        assertDiff(new Object[]{"a", "b", "c"}, new Object[]{"a", "x", "b", "c"}, new Object[]{"a", "x", "b", "c"});
    }

    @Test
    public void replaceMiddleItem() {
        assertDiff(new Object[]{"a", "b", "c"}, new Object[]{"a", "x", "c"}, new Object[]{"a", "x", "c"});
    }

    @Test
    public void fillFromEmpty() {
        assertDiff(new Object[]{}, new Object[]{"a", "b"}, new Object[]{"a", "b"});
    }

    @Test
    public void clearAll() {
        assertDiff(new Object[]{"a", "b"}, new Object[]{}, new Object[]{});
    }

    @Test
    public void reorderEndsAtTarget() {
        assertDiff(new Object[]{"a", "b", "c"}, new Object[]{"c", "b", "a"}, new Object[]{"c", "b", "a"});
    }

    @Test
    public void growAndShrinkTogether() {
        assertDiff(new Object[]{"a", "b", "c"}, new Object[]{"a", "c", "d", "e"}, new Object[]{"a", "c", "d", "e"});
    }

    @Test
    public void insertSectionBeforeExistingTail() {
        assertDiff(new Object[]{"func", "recommend", "vod1"}, new Object[]{"func", "history", "keep", "recommend", "vod1"}, new Object[]{"func", "history", "keep", "recommend", "vod1"});
    }

    @Test
    public void removeMiddleSectionKeepsTail() {
        assertDiff(new Object[]{"func", "history", "keep", "recommend", "vod1"}, new Object[]{"func", "recommend", "vod1"}, new Object[]{"func", "recommend", "vod1"});
    }

    @Test
    public void repeatedApplyIsStable() {
        ListTarget target = new ListTarget("a", "b");
        List<Object> next = Arrays.asList("a", "b", "c");
        RowDiff.apply(target, next, RowDiffTest::keyOf);
        RowDiff.apply(target, next, RowDiffTest::keyOf);
        assertEquals(Arrays.asList("a", "b", "c"), target.result());
    }

    @Test
    public void progressFlagTogglesAtTail() {
        assertDiff(new Object[]{"func", "vod1"}, new Object[]{"func", "vod1", "progress"}, new Object[]{"func", "vod1", "progress"});
        assertDiff(new Object[]{"func", "vod1", "progress"}, new Object[]{"func", "vod1"}, new Object[]{"func", "vod1"});
    }

    @Test
    public void equalKeysButDifferentInstancesProduceNoMutation() {
        ListTarget target = new ListTarget(new StringBuilder("a"), new StringBuilder("b"));
        RowDiff.apply(target, Arrays.asList(new StringBuilder("a"), new StringBuilder("b")), Object::toString);
        assertEquals(2, target.result().size());
        assertEquals("a", target.result().get(0).toString());
        assertEquals("b", target.result().get(1).toString());
    }
}
