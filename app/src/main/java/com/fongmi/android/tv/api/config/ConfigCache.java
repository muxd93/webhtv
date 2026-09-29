package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.github.catvod.Init;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 站源配置 JSON 的磁盘缓存：键为配置 URL 的 md5，值是校验/解码后的原始文本。
 * 放在 filesDir 而非 cacheDir，避免系统清理缓存后断网时无回退可用。
 * 只在 Task 执行线程读写；put 采用临时文件 + rename，避免进程中断留下半截 JSON。
 */
public final class ConfigCache {

    private ConfigCache() {
    }

    private static File dir() {
        File dir = new File(Init.context().getFilesDir(), "config");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File file(String url) {
        return new File(dir(), Util.md5(url) + ".json");
    }

    public static String get(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            File file = file(url);
            if (!file.exists()) return null;
            String json = Path.read(file);
            return TextUtils.isEmpty(json) ? null : json;
        } catch (Throwable e) {
            return null;
        }
    }

    public static void put(String url, String json) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(json)) return;
        File temp = null;
        try {
            File file = file(url);
            temp = new File(dir(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Throwable e) {
            if (temp != null) temp.delete();
        }
    }

    public static void delete(String url) {
        if (TextUtils.isEmpty(url)) return;
        File file = file(url);
        if (file.exists()) file.delete();
    }
}
