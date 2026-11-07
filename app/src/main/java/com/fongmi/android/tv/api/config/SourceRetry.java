package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ConfigDepot;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.utils.Prefers;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户主动重试时的负缓存穿透。
 *
 * <p>自动加载与启动路径一律不清任何退避状态；只有用户在设置页点「刷新」或多仓行长按刷新时
 * 才调用本类，先把本次要重试的源的失败窗口清掉再发起加载，避免「源已经修好但仍在退避窗口内被跳过」。
 *
 * <p>键格式必须与 {@link DepotExpander}（{@code depot-retry-}）和 {@code JarLoader}（{@code jar-retry-}）一致。
 */
public final class SourceRetry {

    private static final String DEPOT_KEY = "depot-retry-";

    private static final String JAR_KEY = "jar-retry-";

    private SourceRetry() {
    }

    /** 清本源负缓存后由调用方发起加载；只影响本源与其所属多仓，其它源的退避窗口原样保留 */
    public static void reset(Config config) {
        if (config == null) return;
        for (String key : keys(config.getUrl(), related(config))) Prefers.remove(key);
        clearJar();
    }

    /** 待清除的键：本源 url + 关联 url 的 depot 负缓存键（去重、忽略空值） */
    static List<String> keys(String url, List<String> related) {
        Set<String> keys = new LinkedHashSet<>();
        if (!isEmpty(url)) keys.add(depotKey(url));
        if (related != null) for (String item : related) if (!isEmpty(item)) keys.add(depotKey(item));
        return new ArrayList<>(keys);
    }

    static String depotKey(String url) {
        return DEPOT_KEY + md5(url);
    }

    static String jarKey(String url) {
        return JAR_KEY + md5(url);
    }

    /** 关联 url：多仓本体取全部子源；多仓子源取父多仓的全部子源；普通单源为空 */
    private static List<String> related(Config config) {
        try {
            Config depot = config.isDepot() ? config : ConfigRepository.findParentDepot(config);
            if (depot == null || depot.getId() <= 0) return new ArrayList<>();
            List<ConfigDepot> relations = AppDatabase.get().getConfigDepotDao().findByConfig(depot.getId());
            List<String> urls = new ArrayList<>(relations.size());
            for (ConfigDepot relation : relations) urls.add(relation.getUrl());
            return urls;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    /**
     * 清空全部 jar 负缓存：站点 jar 只有配置正文解析后才可见，配置本身失败时连站点表都拿不到，
     * 按 md5 精确清覆盖不到这种主场景。jar 退避上限已压到 1 小时，误清代价有界，且只发生在用户主动重试时。
     */
    private static void clearJar() {
        try {
            for (String key : new ArrayList<>(Prefers.getPrefers().getAll().keySet())) if (key.startsWith(JAR_KEY)) Prefers.remove(key);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /** 与 Util.md5 同格式；本类不依赖 Android TextUtils，保证键拼装逻辑可在 JVM 单测里验证 */
    static String md5(String src) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(src.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            return builder.toString().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
