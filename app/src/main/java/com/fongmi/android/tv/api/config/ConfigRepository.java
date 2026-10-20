package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.utils.Prefers;

import java.util.Locale;

import okhttp3.HttpUrl;

/**
 * 配置仓库：URL 规范化、upsert（唯一键冲突回查补齐 id）、显式激活。
 * 所有 Config 的写操作统一走这里，替代原先散落的静态工厂。
 *
 * 命名说明：本类的 Config 指「一个源条目」（点播/直播/壁纸源），
 * 与 VodConfig/LiveConfig/WallConfig（已加载的配置运行态）不是同一概念。
 */
public final class ConfigRepository {

    private ConfigRepository() {
    }

    /** 规范化 URL：trim → scheme/host 小写 → 去默认端口 → 去尾斜杠；query 保留；非 http(s) 原样 trim */
    public static String canonicalize(String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.isEmpty()) return value;
        try {
            HttpUrl httpUrl = HttpUrl.parse(value);
            if (httpUrl == null) return value;
            String scheme = httpUrl.scheme().toLowerCase(Locale.ROOT);
            String host = httpUrl.host().toLowerCase(Locale.ROOT);
            int port = httpUrl.port();
            int defaultPort = scheme.equals("https") ? 443 : 80;
            StringBuilder builder = new StringBuilder();
            builder.append(scheme).append("://").append(host);
            if (port != defaultPort) builder.append(':').append(port);
            String path = httpUrl.encodedPath();
            if (TextUtils.isEmpty(path) || "/".equals(path)) {
                builder.append('/');
            } else {
                builder.append(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
            }
            String query = httpUrl.encodedQuery();
            if (!TextUtils.isEmpty(query)) builder.append('?').append(query);
            return builder.toString();
        } catch (Exception e) {
            return value;
        }
    }

    /** 落库或更新；唯一键/主键冲突时回查补齐 id，杜绝 id=-1 导致的静默丢失 */
    public static Config upsert(Config config) {
        if (config == null || config.isEmpty()) return config;
        if (!TextUtils.isEmpty(config.getUrl())) config.setUrl(canonicalize(config.getUrl()));
        long id = AppDatabase.get().getConfigDao().insert(config);
        if (id == -1) {
            Config current = AppDatabase.get().getConfigDao().find(config.getUrl(), config.getType());
            if (current == null && config.getId() > 0) current = AppDatabase.get().getConfigDao().findById(config.getId());
            if (current == null) return config;
            config.setId(current.getId());
            AppDatabase.get().getConfigDao().update(config);
        } else {
            config.setId(Math.toIntExact(id));
        }
        return config;
    }

    /** 激活为当前源：显式 active 标志 + 清同类型其他激活 + Prefers 镜像（替代 time DESC 隐式推断） */
    public static Config activate(Config config) {
        if (config == null || config.isEmpty()) return config;
        config.setActive(true);
        config.setTime(System.currentTimeMillis());
        upsert(config);
        if (config.getId() > 0) AppDatabase.get().getConfigDao().clearActive(config.getType(), config.getId());
        Prefers.put("config_" + config.getType(), config.getUrl());
        return config;
    }

    public static Config getActive(int type) {
        return AppDatabase.get().getConfigDao().findActive(type);
    }

    /** 当前源；无激活源时回退最近使用的源；都没有则返回空配置 */
    public static Config getActiveOrEmpty(int type) {
        Config active = getActive(type);
        if (active != null) return active;
        Config latest = AppDatabase.get().getConfigDao().findLatest(type);
        return latest != null ? latest : Config.create(type);
    }

    public static Config findByUrl(String url, int type) {
        Config item = AppDatabase.get().getConfigDao().find(canonicalize(url), type);
        return item != null ? item : new Config().type(type).url(canonicalize(url));
    }

    /** 从线上的 Config 对象（url/type/name）upsert 出本地配置，供远程推送/同步使用 */
    public static Config upsertFrom(Config other) {
        if (other == null || other.isEmpty()) return Config.create(0);
        return upsert(new Config().type(other.getType()).url(other.getUrl()).name(other.getName()));
    }

    public static void deleteByUrl(String url, int type) {
        AppDatabase.get().getConfigDao().delete(canonicalize(url), type);
    }
}
