package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.bean.Rule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RuleConfig {

    private List<String> ads = List.of();
    private List<Rule> rules = List.of();
    private boolean dirty;

    public static RuleConfig get() {
        return Loader.INSTANCE;
    }

    public List<String> getAds() {
        if (dirty) merge();
        return ads;
    }

    public List<Rule> getRules() {
        if (dirty) merge();
        return rules;
    }

    void invalidate() {
        dirty = true;
    }

    /** 点播 + 直播 + 规则池并集（两端 getter 已含池内并集，此处再去重一次） */
    private void merge() {
        Set<String> ads = new LinkedHashSet<>(VodConfig.get().getAds());
        ads.addAll(LiveConfig.get().getAds());
        this.ads = new ArrayList<>(ads);
        List<Rule> rules = new ArrayList<>();
        for (Rule rule : VodConfig.get().getRules()) if (!rules.contains(rule)) rules.add(rule);
        for (Rule rule : LiveConfig.get().getRules()) if (!rules.contains(rule)) rules.add(rule);
        this.rules = rules;
        dirty = false;
    }

    private static class Loader {
        static volatile RuleConfig INSTANCE = new RuleConfig();
    }
}
