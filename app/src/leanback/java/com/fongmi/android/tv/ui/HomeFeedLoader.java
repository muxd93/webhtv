package com.fongmi.android.tv.ui;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HomeFeedLoader {

    public interface Listener {
        void onFeed(HomeFeed feed);
    }

    private final Listener mListener;
    private int mEpoch;

    public HomeFeedLoader(Listener listener) {
        mListener = listener;
    }

    public void load() {
        int epoch = ++mEpoch;
        Task.submit(() -> {
            HomeFeed feed;
            try {
                List<History> history = History.get();
                List<Keep> keeps = Keep.getVod();
                Map<String, History> progress = new HashMap<>();
                for (History item : history) progress.put(item.getKey(), item);
                feed = new HomeFeed(history, history.size(), keeps, keeps.size(), progress, Config.nameMap());
            } catch (Exception e) {
                SpiderDebug.log("home-feed", "local load failed: %s", e.getMessage());
                feed = HomeFeed.EMPTY;
            }
            HomeFeed result = feed;
            App.post(() -> {
                if (epoch == mEpoch) mListener.onFeed(result);
            });
        });
    }

    public void cancel() {
        mEpoch++;
    }
}
