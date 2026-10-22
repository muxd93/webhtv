package com.fongmi.android.tv.download;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;

import com.fongmi.android.tv.BuildConfig;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Response;

/**
 * HLS 下载器：解析 master/media playlist → 选最高码率变体 → 下载分片（AES-128 本地化）
 * → 增量重写本地 playlist（未完成不写 ENDLIST，支持边下边播）。
 * 断点续传安全性：以 playlist 内容指纹（sourceHash）为准，指纹变化说明重新解析拿到了
 * 不同的分片序列（URL 过期重解析常见），此时清空剧集目录重新下载，避免旧分片错配。
 */
public class HlsJob {

    private static final String TAG = "TV-download-hls";
    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private static final int BUFFER = 16384;
    private static final int SEGMENT_ATTEMPTS = 2;
    private static final long PLAYLIST_WRITE_INTERVAL_MS = 1500;
    // 分片并行度：进程级共享池，总连接数有界（盒子友好）；单任务时可独占满额
    private static final int SEGMENT_THREADS = 3;
    private static final java.util.concurrent.ExecutorService SEGMENT_POOL = java.util.concurrent.Executors.newFixedThreadPool(SEGMENT_THREADS);

    public interface Listener {

        void onSourceHash(String hash);

        void onProgress(int doneSegments, int totalSegments, long doneBytes, long totalBytes, long speed);

        void onCompleted(long totalBytes);

        void onError(String msg);
    }

    private final String url;
    private final Map<String, String> headers;
    private final File dir;
    private final String expectedHash;
    private final Listener listener;
    private volatile boolean canceled;

    private String sourceHash = "";
    private long lastPlaylistWrite;
    private long doneBytes;
    private long knownTotalBytes;
    private long lastSpeedBytes;
    private long lastSpeedTime;

    public HlsJob(String url, Map<String, String> headers, File dir, String expectedHash, Listener listener) {
        this.url = url;
        this.headers = headers == null ? new HashMap<>() : headers;
        this.dir = dir;
        this.expectedHash = expectedHash == null ? "" : expectedHash;
        this.listener = listener;
    }

    public void cancel() {
        canceled = true;
    }

    public void run() {
        try {
            download();
        } catch (Throwable e) {
            if (!canceled) listener.onError(message(e));
        }
    }

    private void download() throws Exception {
        HlsMediaPlaylist playlist = fetchMediaPlaylist();
        checkSupport(playlist);
        if (!expectedHash.isEmpty() && !expectedHash.equals(sourceHash)) {
            // 分片序列已变化（URL 过期后重新解析几乎必然发生），旧分片全部作废
            Log.w(TAG, "source hash changed, wiping " + dir);
            Path.clear(dir);
        }
        if (!dir.isDirectory()) dir.mkdirs();
        List<HlsMediaPlaylist.Segment> segments = playlist.segments;
        if (segments.isEmpty()) throw new IllegalStateException("empty playlist");
        if (canceled) return;

        String keyFile = fetchKey(playlist, segments);
        String initFile = fetchInit(segments);
        downloadSegments(playlist, segments, keyFile, initFile);
    }

    /**
     * 分片并行下载 + 按序提交：
     * 工作线程只负责把文件落盘（各自独立 .part → rename，天然线程安全）；
     * 提交循环严格按序号推进，保证 playlist 单调增长、进度与 doneBytes 连续，
     * 已存在的分片（断点续传）直接计入完成。
     */
    private void downloadSegments(HlsMediaPlaylist playlist, List<HlsMediaPlaylist.Segment> segments, String keyFile, String initFile) throws Exception {
        int total = segments.size();
        boolean[] finished = new boolean[total];
        long[] lengths = new long[total];
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Object lock = new Object();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            File target = new File(dir, String.format(Locale.US, "seg_%05d.%s", i, DownloadPath.ext(segments.get(i).url)));
            long existing = target.exists() ? target.length() : 0;
            if (existing > 0) {
                finished[i] = true;
                lengths[i] = existing;
                continue;
            }
            final int index = i;
            futures.add(SEGMENT_POOL.submit(() -> {
                if (canceled || errorRef.get() != null) return;
                try {
                    long length = fetchSegment(segments.get(index), new File(dir, String.format(Locale.US, "seg_%05d.%s", index, DownloadPath.ext(segments.get(index).url))));
                    synchronized (lock) {
                        lengths[index] = length;
                        finished[index] = true;
                        lock.notifyAll();
                    }
                } catch (Throwable e) {
                    synchronized (lock) {
                        errorRef.compareAndSet(null, e);
                        lock.notifyAll();
                    }
                }
            }));
        }
        long bytes = 0;
        int committed = 0;
        try {
            while (committed < total) {
                synchronized (lock) {
                    // 定时等待：取消/错误均可及时退出，不依赖额外通知
                    while (!finished[committed] && errorRef.get() == null && !canceled) {
                        try {
                            lock.wait(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (canceled) return;
                if (errorRef.get() != null) break;
                bytes += lengths[committed];
                committed++;
                doneBytes = bytes;
                // playlist 写节流：播放器按秒级轮询，无需每分片重写；收尾一次必须写
                long now = System.currentTimeMillis();
                if (now - lastPlaylistWrite >= PLAYLIST_WRITE_INTERVAL_MS) {
                    lastPlaylistWrite = now;
                    writePlaylist(playlist, segments, committed, keyFile, initFile, false);
                }
                publishProgress(committed, total, bytes);
            }
        } finally {
            // 不中断在途下载（避免池线程残留中断标记影响后续任务），仅阻止未开始的
            for (Future<?> future : futures) future.cancel(false);
        }
        if (canceled) return;
        Throwable error = errorRef.get();
        if (error != null) throw (error instanceof Exception) ? (Exception) error : new IllegalStateException(error);
        writePlaylist(playlist, segments, total, keyFile, initFile, true);
        knownTotalBytes = bytes;
        listener.onCompleted(bytes);
    }

    private HlsMediaPlaylist fetchMediaPlaylist() throws Exception {
        byte[] bytes = fetch(url, headers, MAX_PLAYLIST_BYTES);
        HlsPlaylist playlist = new HlsPlaylistParser().parse(Uri.parse(url), new ByteArrayInputStream(bytes));
        if (playlist instanceof HlsMultivariantPlaylist master) {
            HlsMultivariantPlaylist.Variant variant = pickVariant(master.variants);
            if (variant == null) throw new IllegalStateException("no variant");
            String variantUrl = variant.url.toString();
            if (BuildConfig.DEBUG) Log.i(TAG, String.format("master variant bitrate=%s url=%s", variant.format == null ? -1 : variant.format.bitrate, variantUrl));
            bytes = fetch(variantUrl, headers, MAX_PLAYLIST_BYTES);
            playlist = new HlsPlaylistParser().parse(Uri.parse(variantUrl), new ByteArrayInputStream(bytes));
        }
        if (!(playlist instanceof HlsMediaPlaylist media)) throw new IllegalStateException("not a media playlist");
        if (!media.hasEndTag) throw new IllegalStateException("live playlist");
        sourceHash = md5(bytes);
        listener.onSourceHash(sourceHash);
        return media;
    }

    private HlsMultivariantPlaylist.Variant pickVariant(List<HlsMultivariantPlaylist.Variant> variants) {
        if (variants == null || variants.isEmpty()) return null;
        // 设备可解码优先，同分取最高码率（避免下载本机解不动的 HEVC 最高轨）
        HlsMultivariantPlaylist.Variant best = null;
        for (HlsMultivariantPlaylist.Variant item : variants) {
            if (item.format == null) continue;
            if (best == null || best.format == null || TrackRank.compare(item.format, best.format) < 0) best = item;
        }
        return best != null ? best : variants.get(0);
    }

    private void checkSupport(HlsMediaPlaylist playlist) throws Exception {
        if (playlist.protectionSchemes != null) throw new IllegalStateException("drm");
        String firstInit = null;
        String firstKey = null;
        Set<String> ivs = new HashSet<>();
        for (HlsMediaPlaylist.Segment segment : playlist.segments) {
            if (segment.drmInitData != null || segment.sampleAesEncryptionKeyUri != null) throw new IllegalStateException("drm");
            if (segment.byteRangeLength != C.LENGTH_UNSET || segment.byteRangeOffset != 0) throw new IllegalStateException("byterange");
            if (segment.initializationSegment != null) {
                String init = segment.initializationSegment.url;
                if (firstInit == null) firstInit = init;
                else if (!firstInit.equals(init)) throw new IllegalStateException("multi-map");
            }
            if (segment.fullSegmentEncryptionKeyUri != null) {
                if (firstKey == null) firstKey = segment.fullSegmentEncryptionKeyUri;
                else if (!firstKey.equals(segment.fullSegmentEncryptionKeyUri)) throw new IllegalStateException("key-rotation");
            }
            if (segment.encryptionIV != null) ivs.add(segment.encryptionIV);
        }
        // 各分片 IV 不同：本地 playlist 只写一个 EXT-X-KEY，无法逐分片表达 → 明确拒绝
        if (ivs.size() > 1) throw new IllegalStateException("iv-rotation");
    }

    private String fetchKey(HlsMediaPlaylist playlist, List<HlsMediaPlaylist.Segment> segments) throws Exception {
        for (HlsMediaPlaylist.Segment segment : segments) {
            if (segment.fullSegmentEncryptionKeyUri == null) continue;
            File key = new File(dir, "key.bin");
            if (!key.exists() || key.length() == 0) {
                byte[] bytes = fetch(segment.fullSegmentEncryptionKeyUri, headers, 1024 * 1024);
                try (FileOutputStream os = new FileOutputStream(key)) {
                    os.write(bytes);
                }
            }
            return "key.bin";
        }
        return null;
    }

    private String fetchInit(List<HlsMediaPlaylist.Segment> segments) throws Exception {
        for (HlsMediaPlaylist.Segment segment : segments) {
            if (segment.initializationSegment == null) continue;
            String ext = DownloadPath.ext(segment.initializationSegment.url);
            File init = new File(dir, "init." + ext);
            if (!init.exists() || init.length() == 0) {
                byte[] bytes = fetch(segment.initializationSegment.url, headers, 64 * 1024 * 1024);
                try (FileOutputStream os = new FileOutputStream(init)) {
                    os.write(bytes);
                }
            }
            return "init." + ext;
        }
        return null;
    }

    private long fetchSegment(HlsMediaPlaylist.Segment segment, File target) throws Exception {
        for (int attempt = 1; attempt <= SEGMENT_ATTEMPTS; attempt++) {
            File part = new File(target.getAbsolutePath() + ".part");
            long existing = part.exists() ? part.length() : 0;
            Map<String, String> requestHeaders = new HashMap<>(headers);
            if (existing > 0) requestHeaders.put("Range", "bytes=" + existing + "-");
            long expected = 0;
            long written = 0;
            try (Response res = OkHttp.newCall(segment.url, requestHeaders).execute()) {
                if (!res.isSuccessful()) throw new IllegalStateException("HTTP " + res.code());
                if (res.body() == null) throw new IllegalStateException("empty body");
                if (existing > 0 && res.code() != 206) {
                    existing = 0;
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
                        if (canceled) return expected;
                        os.write(buffer, 0, read);
                        written += read;
                    }
                    os.flush();
                }
            }
            // 长度校验：已知 Content-Length 而写入不足，说明响应被截断（CDN 断流/反爬坑）
            if (expected > 0 && written != expected) {
                Log.w(TAG, "segment length mismatch expected=" + expected + " written=" + written + " attempt=" + attempt);
                if (part.exists()) Path.clear(part);
                if (attempt < SEGMENT_ATTEMPTS) continue;
                throw new IllegalStateException("length");
            }
            if (part.exists() && !part.renameTo(target)) throw new IllegalStateException("rename failed");
            return expected;
        }
        throw new IllegalStateException("length");
    }

    private byte[] fetch(String url, Map<String, String> headers, long maxBytes) throws Exception {
        try (Response res = OkHttp.newCall(url, headers).execute()) {
            if (!res.isSuccessful()) throw new IllegalStateException("HTTP " + res.code());
            if (res.body() == null) throw new IllegalStateException("empty body");
            try (InputStream is = res.body().byteStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[BUFFER];
                int read;
                long total = 0;
                while ((read = is.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) throw new IllegalStateException("too large");
                    bos.write(buffer, 0, read);
                }
                return bos.toByteArray();
            }
        }
    }

    private void publishProgress(int done, int total, long bytes) {
        long now = System.currentTimeMillis();
        long speed = 0;
        if (lastSpeedTime > 0) {
            long delta = Math.max(1, now - lastSpeedTime);
            speed = (bytes - lastSpeedBytes) * 1000 / delta;
        }
        lastSpeedTime = now;
        lastSpeedBytes = bytes;
        listener.onProgress(done, total, bytes, knownTotalBytes, speed);
    }

    private void writePlaylist(HlsMediaPlaylist playlist, List<HlsMediaPlaylist.Segment> segments, int done, String keyFile, String initFile, boolean complete) {
        try {
            List<HlsPlaylistText.SegmentSpec> specs = new ArrayList<>(done);
            for (int i = 0; i < done; i++) specs.add(new HlsPlaylistText.SegmentSpec(segments.get(i).durationUs, segments.get(i).relativeDiscontinuitySequence, DownloadPath.ext(segments.get(i).url)));
            String text = HlsPlaylistText.build(playlist.mediaSequence, playlist.version, playlist.targetDurationUs, keyFile, findIv(segments), initFile, specs, complete);
            Path.write(DownloadPath.playlist(dir), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "write playlist failed " + e.getMessage());
        }
    }

    private String findIv(List<HlsMediaPlaylist.Segment> segments) {
        for (HlsMediaPlaylist.Segment segment : segments) if (segment.encryptionIV != null) return segment.encryptionIV;
        return null;
    }

    private String message(Throwable e) {
        String msg = e.getMessage();
        if ("drm".equals(msg)) return "drm";
        if ("byterange".equals(msg)) return "byterange";
        if ("live playlist".equals(msg)) return "live";
        if ("multi-map".equals(msg)) return "multi-map";
        if ("key-rotation".equals(msg)) return "key-rotation";
        if ("iv-rotation".equals(msg)) return "iv-rotation";
        if ("length".equals(msg)) return "length";
        return msg == null ? e.getClass().getSimpleName() : msg;
    }

    private static String md5(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            BigInteger no = new BigInteger(1, digest.digest(bytes));
            StringBuilder sb = new StringBuilder(no.toString(16));
            while (sb.length() < 32) sb.insert(0, "0");
            return sb.toString().toLowerCase(Locale.US);
        } catch (Exception e) {
            return String.valueOf(bytes.length);
        }
    }
}
