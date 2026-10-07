package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.setting.LiveSetting;

import java.util.ArrayList;
import java.util.List;

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

    private void merge() {
        List<String> ads = new ArrayList<>(VodConfig.get().getAds());
        ads.addAll(LiveConfig.get().getAds());
        // 多仓聚合池（DEPOT3）：开关开启时并入池内广告规则（已跨仓去重、剔除被删/禁用）
        if (LiveSetting.isPool()) for (String ad : DepotPool.adsView()) if (!ads.contains(ad)) ads.add(ad);
        this.ads = ads;
        List<Rule> rules = new ArrayList<>(VodConfig.get().getRules());
        rules.addAll(LiveConfig.get().getRules());
        this.rules = rules;
        dirty = false;
    }

    private static class Loader {
        static volatile RuleConfig INSTANCE = new RuleConfig();
    }
}
