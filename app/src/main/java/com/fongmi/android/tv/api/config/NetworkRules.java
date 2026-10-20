package com.fongmi.android.tv.api.config;

import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 源级网络规则管理：headers/proxy/hosts 按类型（点播/直播）各自持有一份快照，
 * 任一路配置加载成功后全量重建全局规则，替代早期只 addAll 不清除的累积模式
 * （旧源规则残留在换源后继续生效，表现为换了源仍走旧代理/旧 hosts）。
 *
 * 应用级代理（ProxySetting，name=app）不属于源级快照，重建时保留。
 */
public final class NetworkRules {

    private static final String APP_PROXY = "app";

    private static final Map<Integer, Rule> rules = new ConcurrentHashMap<>();

    private static final class Rule {

        private final List<Header> headers = new ArrayList<>();
        private final List<Proxy> proxies = new ArrayList<>();
        private final List<String> hosts = new ArrayList<>();
    }

    private NetworkRules() {
    }

    private static Rule rule(int type) {
        return rules.computeIfAbsent(type, t -> new Rule());
    }

    public static void setHeaders(int type, List<Header> headers) {
        rule(type).headers.clear();
        if (headers != null) rule(type).headers.addAll(headers);
    }

    public static void setProxies(int type, List<Proxy> proxies) {
        rule(type).proxies.clear();
        if (proxies != null) rule(type).proxies.addAll(proxies);
    }

    public static void setHosts(int type, List<String> hosts) {
        rule(type).hosts.clear();
        if (hosts != null) rule(type).hosts.addAll(hosts);
    }

    /** 全量重建：清空源级规则后按各类型最新快照重放（headers 跨类型按 host+header 去重） */
    public static void rebuild() {
        OkHttp.responseInterceptor().clear();
        OkHttp.dns().clear();
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Proxy proxy : OkHttp.selector().getProxy()) if (!APP_PROXY.equals(proxy.getName())) names.add(proxy.getName());
        for (String name : names) OkHttp.selector().remove(name);
        Map<String, Header> headers = new LinkedHashMap<>();
        for (Rule rule : rules.values()) {
            for (Header header : rule.headers) headers.putIfAbsent(header.getHost() + "|" + header.getHeader(), header);
            OkHttp.selector().addAll(rule.proxies);
            OkHttp.dns().addAll(rule.hosts);
        }
        if (!headers.isEmpty()) OkHttp.responseInterceptor().addAll(new ArrayList<>(headers.values()));
    }

    /** 进程退出/全局清理时调用，避免陈旧快照被下次重建重放 */
    public static void reset() {
        rules.clear();
    }
}
