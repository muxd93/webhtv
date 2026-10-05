package com.fongmi.android.tv.live;

import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Init;
import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用户手动屏蔽的直播线路（LIVE8B）：按线路原始 URL（md5 键）全局生效——同一 URL
 * 在任何源里都被视为屏蔽。语义为"沉底+标记"：屏蔽线排在沉底线之后，仍可在弹窗中
 * 手动选中，自动换线与换台预热跳过；长按原线路即可取消屏蔽。
 * 低频显式动作，写穿持久化 filesDir/live/blocked_lines.json：无节流、无容量裁剪、
 * 无过期（区别于 LineHealth 的可丢健康分），屏蔽集合绝不能被清理管道回收。
 */
public final class LineBlockStore {

    private static final Map<String, BlockEntry> BLOCKED = new ConcurrentHashMap<>();
    private static final AtomicBoolean SAVING = new AtomicBoolean();
    private static volatile boolean loaded;

    private LineBlockStore() {
    }

    /** 预热：进直播页后台加载历史屏蔽集合，避免主线程读盘。 */
    public static void init() {
        load();
    }

    public static boolean isBlocked(String url) {
        if (url == null || url.isEmpty()) return false;
        return BLOCKED.containsKey(key(url));
    }

    public static int count() {
        return BLOCKED.size();
    }

    /** 屏蔽线路；name 为屏蔽时的线路名快照，便于管理场景辨认。 */
    public static void block(String url, String name) {
        if (url == null || url.isEmpty()) return;
        BlockEntry entry = new BlockEntry();
        entry.url = url;
        entry.name = name == null ? "" : name;
        entry.ts = System.currentTimeMillis();
        BLOCKED.put(key(url), entry);
        scheduleSave();
    }

    public static void unblock(String url) {
        if (url == null || url.isEmpty()) return;
        if (BLOCKED.remove(key(url)) != null) scheduleSave();
    }

    public static void clear() {
        if (BLOCKED.isEmpty()) return;
        BLOCKED.clear();
        scheduleSave();
    }

    private static void scheduleSave() {
        if (!androidAvailable() || !SAVING.compareAndSet(false, true)) return;
        Task.submit(() -> {
            SAVING.set(false);
            save();
        });
    }

    /** 设备环境探测：JVM 单测下 Init.context() 抛 NPE（无 Android 宿主），据此跳过持久化。 */
    private static boolean androidAvailable() {
        try {
            return Init.context() != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private static synchronized void load() {
        if (loaded || !androidAvailable()) return;
        loaded = true;
        try {
            String json = Path.read(file());
            if (json == null || json.isEmpty()) return;
            Map<String, BlockEntry> map = new Gson().fromJson(json, new TypeToken<Map<String, BlockEntry>>() {
            }.getType());
            if (map != null) BLOCKED.putAll(map);
        } catch (Throwable ignored) {
        }
    }

    private static void save() {
        try {
            File base = new File(Init.context().getFilesDir(), "live");
            if (!base.exists()) base.mkdirs();
            File temp = new File(base, "blocked_lines.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(new Gson().toJson(BLOCKED).getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(new File(base, "blocked_lines.json"))) temp.delete();
        } catch (Throwable ignored) {
        }
    }

    private static File file() {
        return new File(new File(Init.context().getFilesDir(), "live"), "blocked_lines.json");
    }

    /** 纯 Java MD5（与 LineHealth 同形键；本类保持零 Android 依赖，JVM 可测）。 */
    private static String key(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return out.toString();
        } catch (Exception e) {
            return url;
        }
    }

    static void resetForTest() {
        BLOCKED.clear();
        loaded = true;
    }

    private static class BlockEntry {

        String url;
        String name;
        long ts;
    }
}
