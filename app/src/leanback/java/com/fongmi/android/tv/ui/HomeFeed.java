package com.fongmi.android.tv.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HomeFeed {

    public static final HomeFeed EMPTY = new HomeFeed(Collections.emptyList(), 0, Collections.emptyList(), 0, Collections.emptyMap());

    private final List<History> history;
    private final int historyTotal;
    private final List<Keep> keeps;
    private final int keepTotal;
    private final Map<String, History> progress;

    public HomeFeed(List<History> history, int historyTotal, List<Keep> keeps, int keepTotal, Map<String, History> progress) {
        this.history = history == null ? new ArrayList<>() : new ArrayList<>(history);
        this.historyTotal = historyTotal;
        this.keeps = keeps == null ? new ArrayList<>() : new ArrayList<>(keeps);
        this.keepTotal = keepTotal;
        this.progress = progress == null ? new HashMap<>() : new HashMap<>(progress);
    }

    public List<History> getHistory() {
        return history;
    }

    public int getHistoryTotal() {
        return historyTotal;
    }

    public List<Keep> getKeeps() {
        return keeps;
    }

    public int getKeepTotal() {
        return keepTotal;
    }

    public Map<String, History> getProgress() {
        return progress;
    }

    @Nullable
    public History getProgress(String key) {
        return progress.get(key);
    }

    public boolean isEmpty() {
        return history.isEmpty() && keeps.isEmpty() && progress.isEmpty();
    }

    @NonNull
    @Override
    public String toString() {
        return "HomeFeed{history=" + history.size() + "/" + historyTotal + ", keeps=" + keeps.size() + "/" + keepTotal + '}';
    }
}
