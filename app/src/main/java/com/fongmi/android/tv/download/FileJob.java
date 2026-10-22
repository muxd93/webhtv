package com.fongmi.android.tv.download;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.Response;

/** 直链文件（mp4/mkv/flv 等）下载器：Range 探测类型 + 断点续传（.part）+ 长度校验 */
public class FileJob {

    public static final int PROBE_HLS = 1;
    public static final int PROBE_FILE = 2;
    public static final int PROBE_DASH = 3;
    public static final int PROBE_UNKNOWN = 4;

    private static final int PROBE_BYTES = 2048;
    private static final int BUFFER = 16384;

    public interface Listener {

        void onSourceHash(String hash);

        void onProgress(long doneBytes, long totalBytes, long speed);

        void onCompleted(long totalBytes);

        void onError(String msg);
    }

    /** 探测结果：kind 为类型；reason 非空表示失败原因（用于精确报错，不再一律“暂不支持”）；mimeType 用于无扩展名 URL 补全文件扩展名 */
    public static class Probe {

        public final int kind;
        public final String reason;
        public final String mimeType;

        public Probe(int kind, String reason, String mimeType) {
            this.kind = kind;
            this.reason = reason;
            this.mimeType = mimeType;
        }
    }

    private final String url;
    private final Map<String, String> headers;
    private final File dir;
    private final String fileName;
    private final String expectedHash;
    private final Listener listener;
    private volatile boolean canceled;

    private long lastSpeedBytes;
    private long lastSpeedTime;

    public FileJob(String url, Map<String, String> headers, File dir, String fileName, String expectedHash, Listener listener) {
        this.url = url;
        this.headers = headers == null ? new HashMap<>() : headers;
        this.dir = dir;
        this.fileName = fileName;
        this.expectedHash = expectedHash == null ? "" : expectedHash;
        this.listener = listener;
    }

    public void cancel() {
        canceled = true;
    }

    /**
     * 类型探测：GET Range 0-2047，按 content-type / 内容判定 m3u8、dash、普通文件。
     * 带 Range 探针被部分 CDN 拒绝（403/416）时会自动去掉 Range 重试一次；
     * 失败时通过 Probe.reason 给出真实原因（HTTP 状态码 / 网页内容 / 网络异常）。
     */
    public static Probe probe(String url, Map<String, String> headers) {
        // 非 http(s) 协议（rtmp/rtsp/magnet 等）：OkHttp 无法处理，直接给明确原因
        if (!url.startsWith("http://") && !url.startsWith("https://")) return new Probe(PROBE_UNKNOWN, "protocol", null);
        Map<String, String> requestHeaders = headers == null ? new HashMap<>() : new HashMap<>(headers);
        requestHeaders.put("Range", "bytes=0-" + (PROBE_BYTES - 1));
        try (Response res = OkHttp.newCall(url, requestHeaders).execute()) {
            if (!res.isSuccessful()) {
                // 去掉 Range 再试一次：不少 CDN/源站直接拒绝 Range 探针
                Map<String, String> plain = headers == null ? new HashMap<>() : new HashMap<>(headers);
                try (Response retry = OkHttp.newCall(url, plain).execute()) {
                    if (!retry.isSuccessful() || retry.body() == null) return new Probe(PROBE_UNKNOWN, "HTTP " + retry.code(), null);
                    return classify(retry);
                }
            }
            if (res.body() == null) return new Probe(PROBE_UNKNOWN, "empty body", null);
            return classify(res);
        } catch (Exception e) {
            return new Probe(PROBE_UNKNOWN, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null);
        }
    }

    private static Probe classify(Response res) throws Exception {
        String contentType = res.header("Content-Type");
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("mpegurl")) return new Probe(PROBE_HLS, null, contentType);
        if (type.contains("dash+xml")) return new Probe(PROBE_DASH, null, contentType);
        byte[] head = new byte[PROBE_BYTES];
        int read;
        try (InputStream is = res.body().byteStream()) {
            read = is.read(head);
        }
        if (read <= 0) return new Probe(PROBE_UNKNOWN, "empty body", contentType);
        // 去掉 BOM 再判断：部分 CDN 会给 m3u8/XML 加 UTF-8 BOM，直接 startsWith 会漏判
        String text = new String(head, 0, read, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        if (text.startsWith("#EXTM3U")) return new Probe(PROBE_HLS, null, contentType);
        if (text.startsWith("<?xml") && text.contains("<MPD")) return new Probe(PROBE_DASH, null, contentType);
        // Smooth Streaming（ISM）：播放器有模块能放，但分片 URL 模板是 media3 私有逻辑，离线下载无法可靠复刻 → 明确拒绝
        if (text.contains("<SmoothStreamingMedia")) return new Probe(PROBE_UNKNOWN, "smooth", contentType);
        // 返回网页：源站把播放页/嗅探页直接给了下载链路（或需要登录/已过期跳转）
        if (text.startsWith("<") || text.startsWith("<!DOCTYPE")) return new Probe(PROBE_UNKNOWN, "html", contentType);
        if (text.startsWith("{")) return new Probe(PROBE_UNKNOWN, "json", contentType);
        return new Probe(PROBE_FILE, null, contentType);
    }

    public void run() {
        try {
            download();
        } catch (Throwable e) {
            if (!canceled) listener.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void download() throws Exception {
        String actual = sourceHash(url);
        if (!expectedHash.isEmpty() && !expectedHash.equals(actual)) {
            // 重新解析拿到了不同的直链地址（URL 过期重解析常见），旧 .part/旧文件作废
            Path.clear(dir);
        }
        if (!dir.isDirectory()) dir.mkdirs();
        listener.onSourceHash(actual);
        File target = new File(dir, fileName);
        File part = new File(target.getAbsolutePath() + ".part");
        long existing = part.exists() ? part.length() : 0;
        Map<String, String> requestHeaders = new HashMap<>(headers);
        if (existing > 0) requestHeaders.put("Range", "bytes=" + existing + "-");
        long expected = existing;
        long written = existing;
        try (Response res = OkHttp.newCall(url, requestHeaders).execute()) {
            if (!res.isSuccessful()) throw new IllegalStateException("HTTP " + res.code());
            if (res.body() == null) throw new IllegalStateException("empty body");
            if (existing > 0 && res.code() != 206) {
                existing = 0;
                written = 0;
                if (part.exists()) Path.clear(part);
            }
            String contentLength = res.header("Content-Length");
            if (contentLength != null) {
                try {
                    long value = Long.parseLong(contentLength.trim());
                    expected = res.code() == 206 ? existing + value : value;
                } catch (NumberFormatException ignored) {
                }
            }
            try (BufferedInputStream input = new BufferedInputStream(res.body().byteStream()); FileOutputStream os = new FileOutputStream(part, existing > 0)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (canceled) return;
                    os.write(buffer, 0, read);
                    written += read;
                    publish(written, expected);
                }
                os.flush();
            }
        }
        // 长度校验：已知总长度而写入不足 → 响应被截断，不能当完成
        if (expected > 0 && written != expected) throw new IllegalStateException("length");
        if (canceled) return;
        if (part.exists() && !part.renameTo(target)) throw new IllegalStateException("rename failed");
        listener.onCompleted(target.length());
    }

    private void publish(long done, long total) {
        long now = System.currentTimeMillis();
        long speed = 0;
        if (lastSpeedTime > 0) {
            long delta = Math.max(1, now - lastSpeedTime);
            speed = (done - lastSpeedBytes) * 1000 / delta;
        }
        lastSpeedTime = now;
        lastSpeedBytes = done;
        listener.onProgress(done, total, speed);
    }

    private static String sourceHash(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            BigInteger no = new BigInteger(1, digest.digest(url.getBytes(StandardCharsets.UTF_8)));
            StringBuilder sb = new StringBuilder(no.toString(16));
            while (sb.length() < 32) sb.insert(0, "0");
            return sb.toString().toLowerCase(Locale.US);
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }
}
