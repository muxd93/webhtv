package com.fongmi.android.tv.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class RowDiff {

    public interface Target {
        int size();

        Object get(int index);

        void removeAt(int index);

        void insertAt(int index, Object item);
    }

    private RowDiff() {
    }

    public static void apply(Target target, List<?> next, Function<Object, String> keyOf) {
        List<Object> prev = snapshot(target);
        if (sameKeys(prev, next, keyOf)) return;
        for (int i = prev.size() - 1; i >= 0; i--) {
            if (!containsKey(next, keyOf.apply(prev.get(i)), keyOf)) target.removeAt(i);
        }
        List<Object> now = snapshot(target);
        for (int i = 0; i < next.size(); i++) {
            Object item = next.get(i);
            if (i < now.size() && keyOf.apply(now.get(i)).equals(keyOf.apply(item))) continue;
            target.insertAt(i, item);
            now.add(i, item);
        }
        while (now.size() > next.size()) {
            target.removeAt(now.size() - 1);
            now.remove(now.size() - 1);
        }
    }

    private static List<Object> snapshot(Target target) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < target.size(); i++) items.add(target.get(i));
        return items;
    }

    private static boolean sameKeys(List<Object> prev, List<?> next, Function<Object, String> keyOf) {
        if (prev.size() != next.size()) return false;
        for (int i = 0; i < prev.size(); i++) {
            if (!keyOf.apply(prev.get(i)).equals(keyOf.apply(next.get(i)))) return false;
        }
        return true;
    }

    private static boolean containsKey(List<?> items, String key, Function<Object, String> keyOf) {
        for (Object item : items) {
            if (keyOf.apply(item).equals(key)) return true;
        }
        return false;
    }
}
