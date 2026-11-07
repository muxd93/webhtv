package com.fongmi.android.tv.utils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * goProxy / goProxy_arm / tgsou 类第三方进程的「首现」诊断。
 *
 * <p>只在可疑时机（插件下载/加载、spider 初始化）触发，10s 冷却，不做常驻轮询；
 * 进程启动时刻由 {@code /proc/<pid>/stat} 的 starttime 反推，再与插件事件时间线对齐，
 * 从而把进程关联回具体 jar 与站点。全部由 {@link SpiderDebug#isEnabled()} 门控，关闭时零开销。
 */
public final class LocalProxyTrace {

    private static final String TAG = "local-proxy-trace";
    private static final String PS_CMD = "ps -A | grep -E 'goProxy|goProxy_arm|tgsou' || true";
    private static final long SCAN_COOLDOWN_MS = 10_000L;
    private static final int MAX_EVENTS = 24;
    private static final int MAX_JARS = 8;

    private static final AtomicLong LAST_SCAN = new AtomicLong();
    private static final AtomicLong T0 = new AtomicLong(System.currentTimeMillis());
    private static final Deque<Event> EVENTS = new ArrayDeque<>();
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static volatile String firstSeen = "";

    private LocalProxyTrace() {
    }

    /** 记录一次可疑时机，并在冷却允许时做一次进程扫描（异步，不阻塞调用方）。 */
    public static void trace(String moment, String detail) {
        if (!SpiderDebug.isEnabled()) return;
        record(moment, detail);
        long now = System.currentTimeMillis();
        long last = LAST_SCAN.get();
        if (now - last < SCAN_COOLDOWN_MS || !LAST_SCAN.compareAndSet(last, now)) return;
        Task.execute(() -> scan(moment));
    }

    /** 供手动导出拼接：首现上下文 + 最近事件时间线。 */
    public static String report() {
        StringBuilder builder = new StringBuilder();
        builder.append("firstseen=").append(firstSeen.isEmpty() ? "(none)" : firstSeen);
        synchronized (EVENTS) {
            for (Event event : EVENTS) builder.append('\n').append(event.text).append(" @").append(time(event.at));
        }
        return builder.toString();
    }

    private static void record(String moment, String detail) {
        Event event = new Event(System.currentTimeMillis(), "t+" + (System.currentTimeMillis() - T0.get()) + "ms " + moment + " " + detail);
        synchronized (EVENTS) {
            EVENTS.addLast(event);
            while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        }
        SpiderDebug.log(TAG, "event %s", event.text);
    }

    private static void scan(String moment) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", PS_CMD});
            String output = readProcess(process);
            process.waitFor();
            List<Integer> pids = parsePids(output);
            SpiderDebug.log(TAG, "scan moment=%s processes=%s at=+%sms", moment, pids.size(), System.currentTimeMillis() - T0.get());
            for (int pid : pids) report(pid, moment);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "scan failed moment=%s error=%s", moment, e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static void report(int pid, String moment) {
        String cmdline = readText("/proc/" + pid + "/cmdline").replace('\0', ' ').trim();
        String key = pid + "|" + cmdline;
        if (!SEEN.add(key)) return;
        long startMillis = startMillis(System.currentTimeMillis(), uptimeMillis(), statStartTicks(readText("/proc/" + pid + "/stat")));
        boolean first = firstSeen.isEmpty();
        String line = "firstseen=" + first + " pid=" + pid + " start=" + (startMillis <= 0 ? "n/a" : time(startMillis)) + " age=" + (startMillis <= 0 ? "n/a" : (System.currentTimeMillis() - startMillis) + "ms") + " cmdline=" + cmdline + " exe=" + readLink("/proc/" + pid + "/exe") + " moment=" + moment;
        if (first) firstSeen = line;
        SpiderDebug.log(TAG, "%s", line);
        SpiderDebug.log(TAG, "context\n%s", context(startMillis));
    }

    /** 关联上下文：已加载 jar、可疑落盘文件、以及「进程启动时刻之前的最后一个插件事件」。 */
    private static String context(long startMillis) {
        StringBuilder builder = new StringBuilder();
        builder.append("jars dir=").append(Path.jar().getAbsolutePath());
        File[] jars = Path.jar().listFiles();
        if (jars != null) {
            List<File> list = new ArrayList<>();
            for (File jar : jars) list.add(jar);
            list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 0; i < list.size() && i < MAX_JARS; i++) {
                builder.append('\n').append("  ").append(list.get(i).getName()).append(" size=").append(list.get(i).length()).append(" modified=").append(time(list.get(i).lastModified()));
            }
        }
        builder.append("\nproxy files:");
        appendProxyFiles(builder, Path.files());
        appendProxyFiles(builder, Path.cache());
        builder.append("\nlast-event-before-start=").append(eventBefore(startMillis));
        builder.append("\nevents:");
        synchronized (EVENTS) {
            for (Event event : EVENTS) builder.append('\n').append(event.text).append(" @").append(time(event.at));
        }
        return builder.toString();
    }

    private static void appendProxyFiles(StringBuilder builder, File dir) {
        File[] files = dir.listFiles((file, name) -> {
            String lower = name.toLowerCase(Locale.US);
            return lower.contains("goproxy") || lower.contains("tgsou");
        });
        if (files == null || files.length == 0) {
            builder.append("\n  dir=").append(dir.getAbsolutePath()).append(" (none)");
            return;
        }
        for (File file : files) builder.append("\n  ").append(file.getAbsolutePath()).append(" size=").append(file.length()).append(" modified=").append(time(file.lastModified())).append(" exec=").append(file.canExecute());
    }

    private static String eventBefore(long startMillis) {
        if (startMillis <= 0) return "n/a";
        String text = "n/a";
        synchronized (EVENTS) {
            for (Event event : EVENTS) {
                if (event.at <= startMillis) text = event.text + " @" + time(event.at);
                else break;
            }
        }
        return text;
    }

    /** {@code ps -A} 输出：用户名 PID ... 名称，取第二列。 */
    static List<Integer> parsePids(String output) {
        List<Integer> pids = new ArrayList<>();
        if (output == null || output.isEmpty()) return pids;
        for (String line : output.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            try {
                pids.add(Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return pids;
    }

    /** {@code /proc/<pid>/stat} 的第 22 个字段（自开机起的 tick 数），进程名可能含空格/括号。 */
    static long statStartTicks(String stat) {
        if (stat == null) return 0L;
        int end = stat.lastIndexOf(')');
        if (end < 0 || end + 2 >= stat.length()) return 0L;
        String[] parts = stat.substring(end + 2).trim().split("\\s+");
        if (parts.length <= 19) return 0L;
        try {
            return Long.parseLong(parts[19]);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 由开机时长与 starttime 反推进程启动的墙钟时刻；Android USER_HZ 为 100。 */
    static long startMillis(long now, long uptime, long ticks) {
        if (now <= 0 || uptime <= 0 || ticks <= 0) return 0L;
        long age = uptime - ticks * 10L;
        return age <= 0 ? 0L : now - age;
    }

    private static long uptimeMillis() {
        try {
            String uptime = readText("/proc/uptime").trim();
            int space = uptime.indexOf(' ');
            double seconds = Double.parseDouble(space > 0 ? uptime.substring(0, space) : uptime);
            return (long) (seconds * 1000);
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static String readProcess(Process process) throws Exception {
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            StringBuilder builder = new StringBuilder();
            int read;
            while ((read = input.read(buffer)) != -1 && builder.length() < 4000) {
                builder.append(new String(buffer, 0, read, "UTF-8"));
            }
            return builder.toString();
        }
    }

    private static String readText(String path) {
        try (InputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[1024];
            StringBuilder builder = new StringBuilder();
            int read;
            while ((read = input.read(buffer)) != -1 && builder.length() < 1024) {
                builder.append(new String(buffer, 0, read, "UTF-8"));
            }
            return builder.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String readLink(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String time(long millis) {
        if (millis <= 0) return "n/a";
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(millis));
    }

    private static final class Event {

        private final long at;
        private final String text;

        private Event(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }
}
