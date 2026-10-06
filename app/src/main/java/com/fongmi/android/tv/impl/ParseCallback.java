package com.fongmi.android.tv.impl;

import com.fongmi.android.tv.player.ParseJob;

import java.util.List;
import java.util.Map;

public interface ParseCallback {

    void onParseSuccess(Map<String, String> headers, String url, String from);

    default void onParseSuccessMulti(Map<String, String> headers, List<ParseJob.Quality> qualities, String from) {
        if (qualities != null && !qualities.isEmpty()) onParseSuccess(headers, qualities.get(0).url(), from);
    }

    void onParseError();
}
