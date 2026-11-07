package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.DexClassLoader;

public class BaseLoader {

    private static final String MSG_JAR_UNAVAILABLE = "当前站点组件加载失败，部分内容可能无法显示";

    private final AtomicBoolean jarNotified;
    private final JarLoader jarLoader;
    private final PyLoader pyLoader;
    private final JsLoader jsLoader;

    private BaseLoader() {
        jarNotified = new AtomicBoolean();
        jarLoader = new JarLoader();
        pyLoader = new PyLoader();
        jsLoader = new JsLoader();
    }

    public static BaseLoader get() {
        return Loader.INSTANCE;
    }

    private static boolean isJs(String api) {
        return api.contains(".js");
    }

    private static boolean isPy(String api) {
        return api.contains(".py");
    }

    private static boolean isCsp(String api) {
        return api.startsWith("csp_");
    }

    public void clear() {
        Task.execute(() -> {
            jarLoader.clear();
            pyLoader.clear();
            jsLoader.clear();
        });
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        if (isPy(api)) return pyLoader.getSpider(key, api, ext);
        else if (isJs(api)) return jsLoader.getSpider(key, api, ext, jar);
        else if (isCsp(api)) return jarLoader.getSpider(key, api, ext, jar);
        else return new SpiderNull();
    }

    public Spider getSpider(String key) {
        Site site = VodConfig.get().getSite(key);
        Live live = LiveConfig.get().getLive(key);
        if (!site.isEmpty()) return site.spider();
        if (!live.isEmpty()) return live.spider();
        return new SpiderNull();
    }

    public void setRecent(String key, String api, String jar) {
        if (isJs(api)) jsLoader.setRecent(key);
        else if (isPy(api)) pyLoader.setRecent(key);
        else if (isCsp(api)) jarLoader.setRecent(Util.md5(jar));
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (params.containsKey("siteKey")) return getSpider(params.get("siteKey")).proxy(params);
        if ("js".equals(params.get("do"))) return jsLoader.proxy(params);
        if ("py".equals(params.get("do"))) return pyLoader.proxy(params);
        return jarLoader.proxy(params);
    }

    public void parseJar(String jar, boolean recent) {
        if (TextUtils.isEmpty(jar)) return;
        String key = Util.md5(jar);
        boolean available = jarLoader.parseJar(key, jar);
        if (recent) jarLoader.setRecent(key);
        if (!available) notifyJarUnavailable(key);
    }

    /**
     * jar 不可用但未抛异常时（退避窗口跳过、本地文件缺失、加载失败）：
     * 只对当前激活站点/直播源提示一次；首次下载失败仍由既有配置加载异常路径提示，此处不重复。
     */
    private void notifyJarUnavailable(String key) {
        if (!isActiveJar(key)) {
            SpiderDebug.log("jar-loader", "jar unavailable key=%s inactive, log only", key);
            return;
        }
        if (!jarNotified.compareAndSet(false, true)) return;
        SpiderDebug.log("jar-loader", "active jar unavailable key=%s, notify once", key);
        App.post(() -> Notify.show(MSG_JAR_UNAVAILABLE));
    }

    /** 激活判定：当前激活配置登记的 recent、首页站点 jar、当前选中直播源 jar */
    private boolean isActiveJar(String key) {
        if (key.equals(jarLoader.getRecent())) return true;
        try {
            Site site = VodConfig.get().getHome();
            if (!site.isEmpty() && !site.getJar().isEmpty() && Util.md5(site.getJar()).equals(key)) return true;
            Live live = LiveConfig.get().getHome();
            return !live.isEmpty() && !live.getJar().isEmpty() && Util.md5(live.getJar()).equals(key);
        } catch (Throwable e) {
            return false;
        }
    }

    public DexClassLoader dex(String jar) {
        return jarLoader.dex(jar);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    private static class Loader {
        static volatile BaseLoader INSTANCE = new BaseLoader();
    }
}
