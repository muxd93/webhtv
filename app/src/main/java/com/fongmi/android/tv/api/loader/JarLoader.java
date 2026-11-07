package com.fongmi.android.tv.api.loader;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FeatureGate;
import com.fongmi.android.tv.utils.LocalProxyTrace;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import dalvik.system.DexClassLoader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class JarLoader {

    /** 同一 jar 失败后的阶梯退避：30 分钟起，每次翻倍，封顶 1 小时（与 DepotBackoff 的上限对齐） */
    private static final long RETRY_BASE = 30 * 60 * 1000L;
    private static final long RETRY_MAX = 60 * 60 * 1000L;
    private static final String RETRY_KEY = "jar-retry-";

    /** youtube iframe 探测：connect 2.5s 覆盖 DNS/TLS 首次握手，read 4s 覆盖 10KB 级脚本，call 6s 兜住重定向与重试 */
    private static final long PROBE_CONNECT_TIMEOUT_MS = 2500;
    private static final long PROBE_READ_TIMEOUT_MS = 4000;
    private static final long PROBE_CALL_TIMEOUT_MS = 6000;
    private static final String YOUTUBE_PROBE_URL = "https://www.youtube.com/iframe_api";

    private final ConcurrentHashMap<String, DexClassLoader> loaders;
    private final ConcurrentHashMap<String, ProxyMethod> methods;
    private final ConcurrentHashMap<String, Spider> spiders;
    private final ConcurrentHashMap<String, Object> locks;
    /** 站点初始化失败退避：失败不进 spiders 永久缓存，只挡一小段时间 */
    private final SpiderRetryGuard spiderRetry;
    /** 已探测过的 jar key：同一 jar 进程内只探一次，避免每个子仓/站点重复等待 */
    private final Set<String> probed;
    private volatile String recent;

    public JarLoader() {
        loaders = new ConcurrentHashMap<>();
        methods = new ConcurrentHashMap<>();
        spiders = new ConcurrentHashMap<>();
        locks = new ConcurrentHashMap<>();
        spiderRetry = new SpiderRetryGuard();
        probed = ConcurrentHashMap.newKeySet();
    }

    public void clear() {
        SpiderDebug.log("jar-loader", "clear loaders=%s spiders=%s methods=%s", loaders.size(), spiders.size(), methods.size());
        spiders.values().forEach(Spider::destroy);
        methods.values().forEach(ProxyMethod::close);
        loaders.clear();
        methods.clear();
        spiders.clear();
        locks.clear();
        spiderRetry.clear();
        probed.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
        SpiderDebug.log("jar-loader", "recent=%s", recent);
    }

    public String getRecent() {
        return recent == null ? "" : recent;
    }

    private void load(String key, File file) {
        long start = System.currentTimeMillis();
        if (Thread.interrupted()) {
            SpiderDebug.log("jar-loader", "load skip interrupted key=%s", key);
            return;
        }
        if (!Path.exists(file)) {
            SpiderDebug.log("jar-loader", "load skip missing key=%s file=%s", key, file);
            return;
        }
        if (!file.setReadOnly()) {
            SpiderDebug.log("jar-loader", "load skip readonly failed key=%s file=%s size=%s", key, file.getAbsolutePath(), file.length());
            return;
        }
        String cachePath = Path.jar().getAbsolutePath();
        SpiderDebug.log("jar-loader", "load start key=%s file=%s size=%s cache=%s", key, file.getAbsolutePath(), file.length(), cachePath);
        DexClassLoader loader = new CspDexClassLoader(file.getAbsolutePath(), cachePath, cachePath, App.get().getClassLoader());
        invokeInit(key, loader);
        invokeNetworkCompat(key, loader);
        invokeProxy(key, loader, file);
        loaders.put(key, loader);
        SpiderDebug.log("jar-loader", "load done key=%s cost=%sms", key, System.currentTimeMillis() - start);
    }

    private void invokeNetworkCompat(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.parser.merge.j0.b");
            Method method = clz.getMethod("b", okhttp3.OkHttpClient.class);
            OkHttpClient client = OkHttp.client().newBuilder().addInterceptor(chain -> {
                Request request = chain.request();
                boolean iframe = request.url().host().endsWith("youtube.com") && request.url().encodedPath().contains("iframe_api");
                long start = System.currentTimeMillis();
                try {
                    Response response = chain.proceed(request);
                    if (iframe) SpiderDebug.log("youtube-iframe", "response code=%s final=%s type=%s encoding=%s length=%s cost=%sms", response.code(), response.request().url(), response.header("Content-Type"), response.header("Content-Encoding"), response.header("Content-Length"), System.currentTimeMillis() - start);
                    return response;
                } catch (Throwable e) {
                    if (iframe) SpiderDebug.log("youtube-iframe", "failed url=%s cost=%sms error=%s", request.url(), System.currentTimeMillis() - start, error(e));
                    throw e;
                }
            }).build();
            Class<?> environment = loader.loadClass("com.github.catvod.parser.merge.j0.c");
            environment.getConstructor(okhttp3.OkHttpClient.class).newInstance(client);
            SpiderDebug.log("jar-loader", "network client injected key=%s", key);
            probeYoutube(key);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Optional compatibility hook used only by jars which expose it.
        } catch (Throwable e) {
            SpiderDebug.log("jar-loader", "network client inject failed key=%s error=%s", key, error(e));
        }
    }

    /**
     * youtube iframe 端点探测：结果只落日志，不参与任何能力判断。
     * 开关关闭时不发任何请求；打开时每个 jar 进程内只探一次，最长 6s（此前走注入 client 的 30s 默认超时）。
     */
    private void probeYoutube(String key) {
        if (!claimProbe(FeatureGate.youtube(), probed, key)) return;
        long start = System.currentTimeMillis();
        try (Response response = OkHttp.newCall(probeClient(), YOUTUBE_PROBE_URL).execute()) {
            String body = response.body() == null ? null : response.body().string();
            SpiderDebug.log("youtube-iframe", "probe success key=%s code=%s chars=%s cost=%sms", key, response.code(), body == null ? -1 : body.length(), System.currentTimeMillis() - start);
        } catch (Throwable e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            SpiderDebug.log("youtube-iframe", "probe failed key=%s cost=%sms error=%s", key, System.currentTimeMillis() - start, cause.getClass().getName() + ":" + cause.getMessage());
            SpiderDebug.log("youtube-iframe", cause);
        }
    }

    /** 探测准入：开关关闭或该 jar 已探过都不发请求（纯判断，便于单测） */
    static boolean claimProbe(boolean featureEnabled, Set<String> probed, String key) {
        return featureEnabled && probed.add(key);
    }

    private static OkHttpClient probeClient() {
        return OkHttp.client().newBuilder().connectTimeout(PROBE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS).readTimeout(PROBE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).writeTimeout(PROBE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).callTimeout(PROBE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
    }

    private void invokeInit(String key, DexClassLoader loader) {
        long start = System.currentTimeMillis();
        try {
            SpiderDebug.log("jar-loader", "jar init start key=%s", key);
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            Method method = clz.getMethod("init", Context.class);
            method.invoke(clz, App.get());
            SpiderDebug.log("jar-loader", "jar init done key=%s cost=%sms", key, System.currentTimeMillis() - start);
        } catch (Throwable e) {
            SpiderDebug.log("jar-loader", "jar init error key=%s cost=%sms error=%s", key, System.currentTimeMillis() - start, error(e));
            SpiderDebug.log("jar-loader", e);
            e.printStackTrace();
        }
    }

    private void invokeProxy(String key, DexClassLoader loader, File file) {
        long start = System.currentTimeMillis();
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            Method method = clz.getMethod("proxy", Map.class);
            PanProxyCompat compat = null;
            try {
                compat = PanProxyCompat.install(file, loader, message -> SpiderDebug.log("pan-proxy", message));
                if (compat != null) {
                    SpiderDebug.log("jar-loader", "pan proxy lifecycle compat enabled key=%s sha256=%s", key, PanProxyCompat.JAR_SHA256);
                }
            } catch (Exception e) {
                SpiderDebug.log("jar-loader", "pan proxy lifecycle compat unavailable key=%s error=%s", key, error(e));
            }
            methods.put(key, new ProxyMethod(method, compat));
            SpiderDebug.log("jar-loader", "proxy method ready key=%s cost=%sms", key, System.currentTimeMillis() - start);
        } catch (Throwable e) {
            SpiderDebug.log("jar-loader", "proxy method missing key=%s cost=%sms error=%s", key, System.currentTimeMillis() - start, error(e));
            e.printStackTrace();
        }
    }

    public boolean parseJar(String key, String jar) {
        if (loaders.containsKey(key)) return true;
        if (jar.startsWith("assets")) jar = UrlUtil.convert(jar);
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            if (loaders.containsKey(key)) return true;
            String[] texts = jar.split(";md5;");
            String md5 = texts.length > 1 ? texts[1].trim() : "";
            if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim();
            jar = texts[0];
            if (waitRetry(key)) {
                SpiderDebug.log("jar-loader", "parse skip retry window key=%s source=%s state=%s", key, source(jar), retryState(key));
                return false;
            }
            SpiderDebug.log("jar-loader", "parse start key=%s source=%s md5=%s", key, source(jar), !md5.isEmpty());
            try {
                if (!md5.isEmpty() && Util.equals(jar, md5)) {
                    load(key, Path.jar(jar));
                    clearRetry(key);
                } else if (jar.startsWith("http")) {
                    load(key, Download.create(jar, Path.jar(jar)).get());
                    clearRetry(key);
                } else if (jar.startsWith("file")) {
                    load(key, Path.local(jar));
                }
            } catch (RuntimeException e) {
                recordRetry(key, e);
                throw e;
            } catch (Throwable e) {
                recordRetry(key, e);
                throw new RuntimeException(e.getMessage(), e);
            }
            LocalProxyTrace.trace("jar-parse", "key=" + key + " url=" + jar);
            return loaders.containsKey(key);
        }
    }

    private String retryState(String key) {
        try {
            return Prefers.getString(RETRY_KEY + key);
        } catch (Throwable e) {
            return "";
        }
    }

    /** 退避窗口内：不再发起网络，直接返回「无可用 loader」 */
    private boolean waitRetry(String key) {
        try {
            String state = retryState(key);
            if (state.isEmpty()) return false;
            String[] parts = state.split(":");
            long until = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
            return System.currentTimeMillis() < until;
        } catch (Throwable e) {
            return false;
        }
    }

    private void recordRetry(String key, Throwable e) {
        try {
            String state = retryState(key);
            int attempts = 1;
            if (!state.isEmpty()) {
                String[] parts = state.split(":");
                attempts = parts.length > 0 ? Integer.parseInt(parts[0]) + 1 : 1;
            }
            long wait = Math.min(RETRY_BASE * (1L << Math.min(attempts - 1, 8)), RETRY_MAX);
            Prefers.put(RETRY_KEY + key, attempts + ":" + (System.currentTimeMillis() + wait));
            SpiderDebug.log("jar-loader", "parse failed key=%s attempts=%s retry_in=%sm error=%s", key, attempts, wait / 1000, error(e));
        } catch (Throwable ignored) {
        }
    }

    private void clearRetry(String key) {
        try {
            if (!retryState(key).isEmpty()) Prefers.remove(RETRY_KEY + key);
        } catch (Throwable ignored) {
        }
    }

    public DexClassLoader dex(String jar) {
        try {
            String jaKey = Util.md5(jar);
            parseJar(jaKey, jar);
            return loaders.get(jaKey);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        String jaKey = Util.md5(jar);
        String spKey = jaKey + key;
        Spider cached = spiders.get(spKey);
        if (cached != null) return cached;
        long start = System.currentTimeMillis();
        if (spiderRetry.shouldSkip(spKey, start)) {
            SpiderDebug.log("jar-loader", "spider retry skip site=%s api=%s jar=%s window=%sms", key, api, jaKey, SpiderRetryGuard.BACKOFF_MS);
            return new SpiderNull();
        }
        // jar 下载/校验移到 computeIfAbsent 之外：避免在 Map bin 锁内做网络请求，串行化同 bin 的其它站点
        try {
            parseJar(jaKey, jar);
        } catch (Throwable e) {
            SpiderDebug.log("jar-loader", "jar parse error site=%s api=%s jar=%s error=%s", key, api, jaKey, error(e));
        }
        Spider created = spiders.computeIfAbsent(spKey, k -> {
            try {
                SpiderDebug.log("jar-loader", "spider init start site=%s api=%s jar=%s ext=%s", key, api, jaKey, ext == null ? 0 : ext.length());
                DexClassLoader loader = loaders.get(jaKey);
                if (loader == null) {
                    SpiderDebug.log("jar-loader", "spider init skip loader missing site=%s api=%s jar=%s cost=%sms", key, api, jaKey, System.currentTimeMillis() - start);
                    spiderRetry.failed(k, System.currentTimeMillis());
                    return null;
                }
                Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + api.split("csp_")[1]).newInstance();
                spider.siteKey = key;
                spider.init(App.get(), ext);
                SpiderDebug.log("jar-loader", "spider init done site=%s api=%s jar=%s class=%s cost=%sms", key, api, jaKey, spider.getClass().getName(), System.currentTimeMillis() - start);
                LocalProxyTrace.trace("spider-init", "site=" + key + " api=" + api + " jar=" + jaKey + " class=" + spider.getClass().getName());
                spiderRetry.succeeded(k);
                return spider;
            } catch (Throwable e) {
                SpiderDebug.log("jar-loader", "spider init error site=%s api=%s jar=%s cost=%sms error=%s", key, api, jaKey, System.currentTimeMillis() - start, error(e));
                SpiderDebug.log("jar-loader", e);
                e.printStackTrace();
                spiderRetry.failed(k, System.currentTimeMillis());
                return null;
            }
        });
        return created == null ? new SpiderNull() : created;
    }

    private String source(String jar) {
        if (jar.startsWith("http")) return "http:" + Util.md5(jar);
        if (jar.startsWith("file")) return "file:" + jar.length();
        return jar;
    }

    private String error(Throwable e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getClass().getSimpleName() + ":" + cause.getMessage();
    }

    private DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Json" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
        return (JSONObject) method.invoke(null, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Mix" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
        return (JSONObject) method.invoke(null, jxs, name, flag, url);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        ProxyMethod method = recent != null ? methods.get(recent) : null;
        Object[] result = proxyInvoke(method, params);
        if (result != null) return result;
        return tryOthers(params);
    }

    private Object[] tryOthers(Map<String, String> p) {
        return methods.entrySet().stream().filter(e -> !e.getKey().equals(recent)).map(e -> proxyInvoke(e.getValue(), p)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Object[] proxyInvoke(ProxyMethod proxy, Map<String, String> params) {
        try {
            if (proxy == null) return null;
            if (proxy.compat != null && "pan".equals(params.get("do"))) {
                return proxy.compat.invoke(() -> (Object[]) proxy.method.invoke(null, params));
            }
            return (Object[]) proxy.method.invoke(null, params);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    private record ProxyMethod(Method method, PanProxyCompat compat) {
        void close() {
            if (compat != null) compat.close();
        }
    }
}
