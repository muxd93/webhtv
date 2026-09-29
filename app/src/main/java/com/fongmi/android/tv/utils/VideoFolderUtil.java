package com.fongmi.android.tv.utils;

import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 本地视频文件夹连播：把目录下（可含子目录）的视频按文件名排序，
 * 拼成 PUSH 站点可播的 "文件名$file://...#..." 复合 id。
 */
public class VideoFolderUtil {

    private static final String[] EXTENSIONS = {"mp4", "mkv", "avi", "ts", "flv", "mov", "wmv", "rmvb", "3gp", "webm", "m4v", "mpeg", "mpg", "vob", "m2ts", "m3u8", "ogv", "f4v"};

    public static boolean isVideoFile(String name) {
        if (name == null || !name.contains(".")) return false;
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        for (String item : EXTENSIONS) if (item.equals(ext)) return true;
        return false;
    }

    /** 纯播放串："文件名$file://...#..."；目录无视频时返回空串 */
    public static String buildFolderPlayUrl(File dir, boolean recursive) {
        List<String> parts = new ArrayList<>();
        collect(dir, recursive, parts);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('#');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static void collect(File dir, boolean recursive, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            if (f.isDirectory()) {
                if (recursive) collect(f, true, out);
            } else if (isVideoFile(f.getName())) {
                out.add(PushId.segment(f.getName(), Uri.fromFile(f).toString()));
            }
        }
    }
}
