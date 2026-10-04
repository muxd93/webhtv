package com.fongmi.android.tv.utils;

import java.util.Locale;

/**
 * 本地/代理 scheme 到本机服务的纯字符串路由（无 Android 依赖，供 JVM 单测）。
 * file 接受任意斜杠形态（file:/x、file://x、file:///x、file://localhost/x），
 * 统一映射为「存储根绝对路径」，修复历史 FileChooser 保存的单斜杠 file:/ 无法转换的问题。
 */
public final class UrlSchemes {

    private UrlSchemes() {
    }

    public static String convert(String url, String assetsBase, String fileBase, String proxyBase) {
        if (url == null) return "";
        String text = url.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("assets://")) return assetsBase + text.substring(9);
        if (lower.startsWith("proxy://")) return proxyBase + text.substring(8);
        if (lower.startsWith("file:")) return file(text.substring(5), fileBase);
        return text;
    }

    /** scheme 后的剩余部分统一为「/存储根绝对路径」；localhost 主机段视为本机别名剥离。 */
    private static String file(String rest, String fileBase) {
        rest = rest.replaceFirst("^/+", "");
        if (rest.regionMatches(true, 0, "localhost/", 0, "localhost/".length())) rest = rest.substring(10);
        if (rest.isEmpty()) return fileBase;
        return fileBase + (rest.startsWith("/") ? rest : "/" + rest);
    }
}
