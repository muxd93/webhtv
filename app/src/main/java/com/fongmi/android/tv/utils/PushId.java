package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.text.TextUtils;

/**
 * 推送/本地/SMB 播放 id 的唯一格式与解析点。
 * <p>
 * 两种形态：
 * <ul>
 *   <li>单文件：{@code 名称$URL}（文件夹连播的每个集数段同构）</li>
 *   <li>文件夹连播：{@code 文件夹名|||名称1$URL1#名称2$URL2}</li>
 * </ul>
 * 所有拼串入口与展示名解析必须经此类，禁止在调用点手工拼接或剥离；
 * detailContent 是 {@code |||} 前缀的唯一剥离点，Flag.setEpisodes 不做任何剥离。
 */
public class PushId {

    /** 单文件播放 id，同时也是连播串里的一个集数段 */
    public static String segment(String name, String url) {
        return clean(name) + "$" + url;
    }

    /** 文件夹连播 id，segments 为 {@code 名称$URL#名称$URL} 复合串 */
    public static String folder(String name, String segments) {
        return clean(name) + "|||" + segments;
    }

    /** 去掉文件夹前缀，返回集数复合串 */
    public static String playList(String id) {
        if (TextUtils.isEmpty(id)) return "";
        int folder = id.indexOf("|||");
        return folder >= 0 ? id.substring(folder + 3) : id;
    }

    /** 展示名：文件夹名 > 首段名称 > URL 末段兜底 */
    public static String displayName(String id) {
        if (TextUtils.isEmpty(id)) return "";
        int folder = id.indexOf("|||");
        if (folder >= 0) return id.substring(0, folder).trim();
        int dollar = id.indexOf('$');
        if (dollar > 0) return id.substring(0, dollar).trim();
        return lastName(id);
    }

    /** URL 解码后的最后一段路径；无路径段时原样返回 */
    public static String lastName(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String path = Uri.parse(url).getPath();
        if (!TextUtils.isEmpty(path)) {
            String[] segments = path.split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (!segments[i].isEmpty()) return segments[i];
            }
        }
        int slash = url.lastIndexOf('/');
        return slash >= 0 && slash + 1 < url.length() ? url.substring(slash + 1) : url;
    }

    /** 名称段不得含分隔符，否则集数切分与展示名解析会被破坏 */
    private static String clean(String name) {
        return name == null ? "" : name.replaceAll("[#$|]", " ").trim();
    }
}
