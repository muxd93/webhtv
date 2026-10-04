package com.fongmi.android.tv.live;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Init;
import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;

/**
 * 被动线路健康记分（SYS3/Stage 3）：零网络成本，信号来自真实播放结果。
 * 规则：连续失败 ≥3 次的线路沉底（稳定双桶序：健康线路原序在前、沉底线路原序在后，
 * 只沉底不删除、用户仍可手动选中）；任一次播放成功即回血清零。
 * 状态按线路原始 URL（md5 键）记忆，节流落盘 filesDir/live/line_health.json；
 * 进直播页预热加载，会话渲染时对全部频道重排一次。
 */
public final class LineHealth {

    private static final int SINK_AFTER = 3;
    private static final int MAX_ENTRIES = 2000;
    private static final long STALE_MS = 30L * 24 * 3600 * 1000L;
    private static final long SAVE_DELAY = 5L;
    /** 键 = md5(线路原始 URL)；value = 连续失败次数与最近时间。 */
    private static final Map<String, Entry> STATE = new ConcurrentHashMap<>();
    private static final AtomicBoolean SAVING = new AtomicBoolean();
    private static volatile boolean loaded;

    private LineHealth() {
    }

    /** 预热：进直播页后台加载历史状态，避免渲染线程读盘。 */
    public static void init() {
        load();
    }

    /** 播放成功：连续失败清零（回血）。 */
    public static void success(String url) {
        onResult(url, true);
    }

    /** 播放失败：连续失败 +1。返回本次是否刚好达到沉底阈值（供会话内即时重排）。 */
    public static boolean failure(String url) {
        return onResult(url, false);
    }

    private static boolean onResult(String url, boolean success) {
        if (url == null || url.isEmpty()) return false;
        Entry entry = STATE.computeIfAbsent(key(url), k -> new Entry());
        boolean justSunk;
        synchronized (entry) {
            entry.failures = success ? 0 : entry.failures + 1;
            entry.ts = System.currentTimeMillis();
            justSunk = !success && entry.failures == SINK_AFTER;
        }
        scheduleSave();
        return justSunk;
    }

    /** 线路是否已沉底。 */
    public static boolean isSunk(String url) {
        if (url == null || url.isEmpty()) return false;
        Entry entry = STATE.get(key(url));
        return entry != null && entry.failures >= SINK_AFTER;
    }

    /**
     * 渲染前对单个频道沉底重排：线路与名称同步，选中线路按 URL 原位保留。
     * 全健康/全沉底时不重排，保持解析原序。
     */
    public static void reorder(Channel channel) {
        List<String> urls = channel.getUrls();
        int size = urls.size();
        if (size < 2) return;
        int selected = channel.getIndex();
        String selectedUrl = selected >= 0 && selected < size ? urls.get(selected) : "";
        boolean[] sunk = new boolean[size];
        int count = 0;
        for (int i = 0; i < size; i++) {
            sunk[i] = isSunk(urls.get(i));
            if (sunk[i]) count++;
        }
        if (count == 0 || count == size) return;
        int[] order = order(size, i -> sunk[i]);
        channel.orderLines(order);
        List<String> reordered = channel.getUrls();
        if (!selectedUrl.isEmpty()) {
            for (int i = 0; i < reordered.size(); i++) {
                if (reordered.get(i).equals(selectedUrl)) {
                    channel.setIndex(i);
                    break;
                }
            }
        }
    }

    /** 稳定双桶排序：健康线路保持原序在前，沉底线路保持原序在后（纯函数，供单测）。 */
    static int[] order(int size, IntPredicate sunk) {
        List<Integer> healthy = new ArrayList<>(size), dead = new ArrayList<>();
        for (int i = 0; i < size; i++) (sunk.test(i) ? dead : healthy).add(i);
        healthy.addAll(dead);
        int[] result = new int[size];
        for (int i = 0; i < size; i++) result[i] = healthy.get(i);
        return result;
    }

    /** 纯 Java MD5：本类保持零 Android 依赖（JVM 可测），键仅本类内部使用。 */
    /** 设备环境探测：JVM 单测下 Init.context() 抛 NPE（无 Android 宿主），据此跳过持久化。 */
    private static boolean androidAvailable() {
        try {
            return Init.context() != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 纯 Java MD5：本类保持零 Android 依赖（JVM 可测），键仅本类内部使用。 */
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

    private static void scheduleSave() {
        if (!androidAvailable() || !SAVING.compareAndSet(false, true)) return;
        if (Init.context() == null || !SAVING.compareAndSet(false, true)) return;
        Task.schedule(() -> {
            SAVING.set(false);
            save();
        }, SAVE_DELAY, TimeUnit.SECONDS);
    }

    private static synchronized void load() {
        if (loaded || !androidAvailable()) return;
        loaded = true;
        try {
            String json = Path.read(file());
            if (json == null || json.isEmpty()) return;
            Map<String, Entry> map = new Gson().fromJson(json, new TypeToken<Map<String, Entry>>() {
            }.getType());
            if (map != null) STATE.putAll(map);
        } catch (Throwable ignored) {
        }
    }

    private static void save() {
        try {
            long now = System.currentTimeMillis();
            Map<String, Entry> keep = new HashMap<>();
            for (Map.Entry<String, Entry> e : STATE.entrySet()) {
                Entry entry = e.getValue();
                synchronized (entry) {
                    if (now - entry.ts <= STALE_MS) keep.put(e.getKey(), entry);
                }
            }
            if (keep.size() > MAX_ENTRIES) {
                List<Map.Entry<String, Entry>> list = new ArrayList<>(keep.entrySet());
                list.sort((a, b) -> Long.compare(b.getValue().ts, a.getValue().ts));
                Map<String, Entry> trimmed = new HashMap<>();
                for (int i = 0; i < MAX_ENTRIES; i++) trimmed.put(list.get(i).getKey(), list.get(i).getValue());
                keep = trimmed;
            }
            File base = new File(Init.context().getFilesDir(), "live");
            if (!base.exists()) base.mkdirs();
            File file = file(base);
            File temp = new File(base, "line_health.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(new Gson().toJson(keep).getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Throwable ignored) {
        }
    }

    private static File file() {
        return file(new File(Init.context().getFilesDir(), "live"));
    }

    private static File file(File base) {
        return new File(base, "line_health.json");
    }

    static void resetForTest() {
        STATE.clear();
        loaded = true;
    }

    private static class Entry {

        int failures;
        long ts;
    }
}
