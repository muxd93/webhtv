package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Rule;
import com.github.catvod.bean.Doh;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 网络与拦截规则池：各仓（含多仓全部子仓）的规则按身份键并集去重，
 * 永久保留（重启不丢、删源不删），启动时一次性应用到 OkHttp 全局态。
 *
 * 入池范围（均为全局安全、跨仓互补）：
 * - headers/proxy/hosts/doh：网络路由层，并集不影响其他源；
 * - ads：WebView 广告 host 黑名单（CustomWebView 消费），并集更完整；
 * - rules：嗅探规则（Sniffer 消费，按 host 匹配才命中），并集提升覆盖率。
 *
 * 明确不入池：
 * - flags：直接进 spider 的 playerContent 分支选择，按当前激活源生效，
 *   多仓并集可能干扰当前 spider 决策。
 *
 * 身份键：headers=host+header、proxy=name、hosts=整串、doh/ads=字符串、rules=name；冲突首次命中。
 */
public final class RulePool {

    private static final String KEY = "rule_pool";

    private static final Object LOCK = new Object();

    /** 持久化数据（Prefers JSON）；transient 键集仅进程内有效 */
    private static Data data;

    private static final class Data {

        List<JsonElement> headers = new ArrayList<>();
        List<JsonElement> proxies = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        List<JsonElement> doh = new ArrayList<>();
        List<String> ads = new ArrayList<>();
        List<JsonElement> rules = new ArrayList<>();

        transient Set<String> headerKeys = new LinkedHashSet<>();
        transient Set<String> proxyKeys = new LinkedHashSet<>();
        transient Set<String> hostKeys = new LinkedHashSet<>();
        transient Set<String> dohKeys = new LinkedHashSet<>();
        transient Set<String> adsKeys = new LinkedHashSet<>();
        transient Set<String> ruleKeys = new LinkedHashSet<>();
    }

    private RulePool() {
    }

    private static Data load() {
        if (data != null) return data;
        Data result = new Data();
        try {
            String json = Prefers.getString(KEY, "");
            if (!json.isEmpty()) {
                Data stored = App.gson().fromJson(json, Data.class);
                if (stored != null) {
                    if (stored.headers != null) result.headers = stored.headers;
                    if (stored.proxies != null) result.proxies = stored.proxies;
                    if (stored.hosts != null) result.hosts = stored.hosts;
                    if (stored.doh != null) result.doh = stored.doh;
                    if (stored.ads != null) result.ads = stored.ads;
                    if (stored.rules != null) result.rules = stored.rules;
                }
            }
        } catch (Exception e) {
            result = new Data();
        }
        rebuildKeys(result);
        data = result;
        return result;
    }

    private static void rebuildKeys(Data d) {
        for (JsonElement element : d.headers) {
            try {
                for (Header item : Header.arrayFrom(wrap(element))) d.headerKeys.add(key(item));
            } catch (Exception ignored) {
            }
        }
        for (JsonElement element : d.proxies) {
            try {
                for (Proxy item : Proxy.arrayFrom(wrap(element))) d.proxyKeys.add(key(item));
            } catch (Exception ignored) {
            }
        }
        d.hostKeys.addAll(d.hosts);
        for (JsonElement element : d.doh) {
            try {
                Doh item = App.gson().fromJson(element, Doh.class);
                if (item != null) d.dohKeys.add(item.getUrl());
            } catch (Exception ignored) {
            }
        }
        d.adsKeys.addAll(d.ads);
        for (JsonElement element : d.rules) {
            try {
                for (Rule item : Rule.arrayFrom(wrap(element))) d.ruleKeys.add(item.getName());
            } catch (Exception ignored) {
            }
        }
    }

    private static void save(Data d) {
        data = d;
        try {
            Prefers.put(KEY, App.gson().toJson(d));
        } catch (Exception ignored) {
        }
    }

    private static JsonArray element(List<JsonElement> items) {
        JsonArray array = new JsonArray();
        for (JsonElement item : items) array.add(item);
        return array;
    }

    private static JsonArray wrap(JsonElement item) {
        JsonArray array = new JsonArray();
        array.add(item);
        return array;
    }

    private static String key(Header item) {
        return item.getHost() + "|" + item.getHeader();
    }

    private static String key(Proxy item) {
        return item.getName().isEmpty() ? "proxy:" + item.getUrls() : item.getName();
    }

    /** headers 入池并应用新增部分（ResponseInterceptor 不去重，靠 applied 集合保证只 add 一次） */
    public static void addHeaders(List<Header> items) {
        if (items == null || items.isEmpty()) return;
        synchronized (LOCK) {
            Data d = load();
            List<Header> fresh = new ArrayList<>();
            for (Header item : items) {
                String key = key(item);
                if (d.headerKeys.contains(key)) continue;
                d.headerKeys.add(key);
                d.headers.add(App.gson().toJsonTree(item));
                fresh.add(item);
            }
            if (fresh.isEmpty()) return;
            save(d);
            OkHttp.responseInterceptor().addAll(fresh);
        }
    }

    public static void addProxies(List<Proxy> items) {
        if (items == null || items.isEmpty()) return;
        synchronized (LOCK) {
            Data d = load();
            List<Proxy> fresh = new ArrayList<>();
            for (Proxy item : items) {
                String key = key(item);
                if (d.proxyKeys.contains(key)) continue;
                d.proxyKeys.add(key);
                d.proxies.add(App.gson().toJsonTree(item));
                fresh.add(item);
            }
            if (fresh.isEmpty()) return;
            save(d);
            OkHttp.selector().addAll(fresh);
        }
    }

    public static void addHosts(List<String> items) {
        if (items == null || items.isEmpty()) return;
        synchronized (LOCK) {
            Data d = load();
            List<String> fresh = new ArrayList<>();
            for (String item : items) {
                if (item == null || d.hostKeys.contains(item)) continue;
                d.hostKeys.add(item);
                d.hosts.add(item);
                fresh.add(item);
            }
            if (fresh.isEmpty()) return;
            save(d);
            OkHttp.dns().addAll(fresh);
        }
    }

    /** doh 入池，返回「自身 + 池中其他」的并集（doh 无全局应用点，由配置 getDoh 消费） */
    public static List<Doh> mergeDoh(List<Doh> own) {
        List<Doh> result = own == null ? new ArrayList<>() : new ArrayList<>(own);
        synchronized (LOCK) {
            Data d = load();
            Set<String> keys = new LinkedHashSet<>();
            for (Doh item : result) if (!item.getUrl().isEmpty()) keys.add(item.getUrl());
            boolean changed = false;
            if (own != null) for (Doh item : own) {
                if (item.getUrl().isEmpty() || d.dohKeys.contains(item.getUrl())) continue;
                d.dohKeys.add(item.getUrl());
                d.doh.add(App.gson().toJsonTree(item));
                changed = true;
            }
            for (JsonElement element : d.doh) {
                try {
                    Doh item = App.gson().fromJson(element, Doh.class);
                    if (item == null || item.getUrl().isEmpty() || !keys.add(item.getUrl())) continue;
                    result.add(item);
                } catch (Exception ignored) {
                }
            }
            if (changed) save(d);
        }
        return result;
    }

    /** ads 入池，返回「自身 + 池中其他」的并集（WebView 广告 host 黑名单，全局互补） */
    public static List<String> mergeAds(List<String> own) {
        List<String> result = own == null ? new ArrayList<>() : new ArrayList<>(own);
        synchronized (LOCK) {
            Data d = load();
            Set<String> keys = new LinkedHashSet<>(result);
            boolean changed = false;
            if (own != null) for (String item : own) {
                if (item == null || item.isEmpty() || d.adsKeys.contains(item)) continue;
                d.adsKeys.add(item);
                d.ads.add(item);
                changed = true;
            }
            for (String item : d.ads) if (keys.add(item)) result.add(item);
            if (changed) save(d);
        }
        return result;
    }

    /** rules 入池，返回「自身 + 池中其他」的并集（按 name 去重；嗅探时按 host 匹配才命中） */
    public static List<Rule> mergeRules(List<Rule> own) {
        List<Rule> result = own == null ? new ArrayList<>() : new ArrayList<>(own);
        synchronized (LOCK) {
            Data d = load();
            Set<String> keys = new LinkedHashSet<>();
            for (Rule item : result) keys.add(item.getName());
            boolean changed = false;
            if (own != null) for (Rule item : own) {
                if (d.ruleKeys.contains(item.getName())) continue;
                d.ruleKeys.add(item.getName());
                d.rules.add(App.gson().toJsonTree(item));
                changed = true;
            }
            for (JsonElement element : d.rules) {
                try {
                    for (Rule item : Rule.arrayFrom(wrap(element))) if (keys.add(item.getName())) result.add(item);
                } catch (Exception ignored) {
                }
            }
            if (changed) save(d);
        }
        return result;
    }

    /** 启动时全量应用（幂等；重复调用不会重复 addAll） */
    public static void apply() {
        synchronized (LOCK) {
            Data d = load();
            List<Header> headers = Header.arrayFrom(element(d.headers));
            List<Proxy> proxies = Proxy.arrayFrom(element(d.proxies));
            List<String> hosts = new ArrayList<>(d.hosts);
            if (!headers.isEmpty()) OkHttp.responseInterceptor().addAll(headers);
            if (!proxies.isEmpty()) OkHttp.selector().addAll(proxies);
            if (!hosts.isEmpty()) OkHttp.dns().addAll(hosts);
            for (Header item : headers) markHeader(key(item));
            for (Proxy item : proxies) markProxy(key(item));
            for (String host : hosts) markHost(host);
        }
    }

    private static void markHeader(String key) {
        load().headerKeys.add(key);
    }

    private static void markProxy(String key) {
        load().proxyKeys.add(key);
    }

    private static void markHost(String key) {
        load().hostKeys.add(key);
    }

    /** 池内规则总数（诊断/测试用） */
    public static int size() {
        synchronized (LOCK) {
            Data d = load();
            return d.headers.size() + d.proxies.size() + d.hosts.size() + d.doh.size() + d.ads.size() + d.rules.size();
        }
    }

    /** 测试辅助：清空内存镜像与持久化数据 */
    public static void resetForTest() {
        synchronized (LOCK) {
            data = new Data();
            Prefers.put(KEY, "");
        }
    }

    public static List<String> hosts() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(load().hosts);
        }
    }
}
