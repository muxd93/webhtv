package com.fongmi.android.tv.download;

import android.net.Uri;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.dash.DashSegmentIndex;
import androidx.media3.exoplayer.dash.manifest.AdaptationSet;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.RangedUri;
import androidx.media3.exoplayer.dash.manifest.Representation;

import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Response;

/**
 * DASH 下载器：解析 mpd → 选最高码率视频轨 + 音频轨 → 下载分片（或整文件）→ 生成本地 mpd。
 * 与 HlsJob 同构：来源指纹比对（重新解析拿到不同 mpd 即清目录重下）、断点续传、长度校验；
 * 分段（SegmentTemplate/SegmentList）下载期间本地 mpd 只写入已完成分片 → 边下边播；
 * 单文件（SegmentBase，B站等）整文件下载完成后才可播。
 */
public class DashJob {

    private static final String TAG = "TV-download-dash";
    private static final int MAX_MPD_BYTES = 4 * 1024 * 1024;
    private static final int BUFFER = 16384;
    private static final int SEGMENT_ATTEMPTS = 2;
    private static final long MPD_WRITE_INTERVAL_MS = 1500;

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
    private List<TrackPlan> plans = new ArrayList<>();
    private int doneSegments;
    private int totalSegments;
    private long doneBytes;
    private long lastSpeedBytes;
    private long lastSpeedTime;
    private long lastMpdWrite;

    public DashJob(String url, Map<String, String> headers, File dir, String expectedHash, Listener listener) {
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
        byte[] mpdBytes = fetchMpd();
        sourceHash = md5(mpdBytes);
        listener.onSourceHash(sourceHash);
        DashManifest manifest = new DashManifestParser().parse(Uri.parse(url), new ByteArrayInputStream(mpdBytes));
        if (manifest.dynamic) throw new IllegalStateException("live");
        if (manifest.getPeriodCount() != 1) throw new IllegalStateException("multi-period");
        if (!expectedHash.isEmpty() && !expectedHash.equals(sourceHash)) {
            // mpd 内容已变化（URL 过期后重新解析常见），旧分片全部作废
            Log.w(TAG, "source hash changed, wiping " + dir);
            Path.clear(dir);
        }
        if (!dir.isDirectory()) dir.mkdirs();

        plans = plan(manifest, manifest.getPeriod(0));
        if (plans.isEmpty()) throw new IllegalStateException("no-track");
        for (TrackPlan plan : plans) totalSegments += plan.segments.size() + (plan.initSegment == null ? 0 : 1);
        if (BuildConfig.DEBUG) Log.i(TAG, String.format("plans=%d segments=%d duration=%dms", plans.size(), totalSegments, manifest.getPeriodDurationMs(0)));

        for (TrackPlan plan : plans) {
            if (canceled) return;
            downloadTrack(manifest, plan);
            if (canceled) return;
        }
        writeMpd(manifest, plans, true);
        listener.onCompleted(doneBytes);
    }

    // ---------- 规划 ----------

    private List<TrackPlan> plan(DashManifest manifest, Period period) throws Exception {
        Representation video = pick(period, C.TRACK_TYPE_VIDEO);
        if (video == null) throw new IllegalStateException("no-video");
        Representation audio = pick(period, C.TRACK_TYPE_AUDIO);
        List<TrackPlan> plans = new ArrayList<>();
        plans.add(TrackPlan.create(video, "v", "video", baseOf(manifest, video), manifest.getPeriodDurationUs(0)));
        if (audio != null) plans.add(TrackPlan.create(audio, "a", "audio", baseOf(manifest, audio), manifest.getPeriodDurationUs(0)));
        return plans;
    }

    /** 选 Representation：设备可解码优先，同分取最高码率（跳过 DRM 轨） */
    private Representation pick(Period period, int trackType) {
        Representation best = null;
        for (AdaptationSet set : period.adaptationSets) {
            if (set.type != trackType) continue;
            for (Representation rep : set.representations) {
                if (rep.format != null && rep.format.drmInitData != null) continue;
                if (best == null || best.format == null) {
                    best = rep;
                } else if (rep.format != null && TrackRank.compare(rep.format, best.format) < 0) {
                    best = rep;
                }
            }
        }
        return best;
    }

    private String baseOf(DashManifest manifest, Representation rep) {
        if (!rep.baseUrls.isEmpty()) {
            String base = rep.baseUrls.get(0).url;
            if (base != null && (base.startsWith("http://") || base.startsWith("https://"))) return base;
        }
        return url;
    }

    // ---------- 下载 ----------

    private void downloadTrack(DashManifest manifest, TrackPlan plan) throws Exception {
        if (plan.initSegment != null && !new File(dir, plan.initSegment.file).exists()) {
            plan.bytes += fetchSegment(plan.initSegment, new File(dir, plan.initSegment.file));
            if (canceled) return;
            doneSegments++;
            publishProgress();
        }
        for (Segment segment : plan.segments) {
            if (canceled) return;
            File target = new File(dir, segment.file);
            long existing = target.exists() ? target.length() : 0;
            if (existing > 0 && (segment.length == C.LENGTH_UNSET || existing >= segment.length)) {
                plan.bytes += existing;
                plan.done++;
                doneSegments++;
                publishProgress();
                continue;
            }
            plan.bytes += fetchSegment(segment, target);
            plan.done++;
            doneSegments++;
            // 分段轨边下边写本地 mpd（单文件轨未下完时播放器无法按范围读，只在完成时写）
            if (!plan.single) {
                long now = System.currentTimeMillis();
                if (now - lastMpdWrite >= MPD_WRITE_INTERVAL_MS) {
                    lastMpdWrite = now;
                    writeMpd(manifest, plans, false);
                }
            }
            publishProgress();
        }
    }

    private long fetchSegment(Segment segment, File target) throws Exception {
        for (int attempt = 1; attempt <= SEGMENT_ATTEMPTS; attempt++) {
            File part = new File(target.getAbsolutePath() + ".part");
            long existing = part.exists() ? part.length() : 0;
            Map<String, String> requestHeaders = new HashMap<>(headers);
            if (segment.start > 0 || segment.length != C.LENGTH_UNSET) {
                long from = segment.start + existing;
                long to = segment.length == C.LENGTH_UNSET ? -1 : segment.start + segment.length - 1;
                requestHeaders.put("Range", "bytes=" + from + "-" + (to < 0 ? "" : String.valueOf(to)));
            }
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
                if (segment.length != C.LENGTH_UNSET && expected == 0) expected = segment.length - existing;
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

    private void publishProgress() {
        long bytes = 0;
        for (TrackPlan plan : plans) bytes += plan.bytes;
        doneBytes = bytes;
        long now = System.currentTimeMillis();
        long speed = 0;
        if (lastSpeedTime > 0) {
            long delta = Math.max(1, now - lastSpeedTime);
            speed = (doneBytes - lastSpeedBytes) * 1000 / delta;
        }
        lastSpeedTime = now;
        lastSpeedBytes = doneBytes;
        listener.onProgress(doneSegments, totalSegments, doneBytes, 0, speed);
    }

    // ---------- mpd 生成 ----------

    private void writeMpd(DashManifest manifest, List<TrackPlan> plans, boolean complete) {
        try {
            Path.write(DownloadPath.manifest(dir), buildXml(toModel(manifest, plans, complete)).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "write mpd failed " + e.getMessage());
        }
    }

    private static MpdModel toModel(DashManifest manifest, List<TrackPlan> plans, boolean complete) {
        MpdModel model = new MpdModel();
        model.durationMs = manifest.getPeriodDurationMs(0);
        for (TrackPlan plan : plans) {
            MpdModel.Track track = new MpdModel.Track();
            Format format = plan.rep.format;
            track.kind = plan.kind;
            track.mimeType = format == null ? "" : String.valueOf(format.sampleMimeType);
            track.codecs = format == null ? "" : String.valueOf(format.codecs);
            track.width = format == null ? 0 : format.width;
            track.height = format == null ? 0 : format.height;
            track.bandwidth = format == null ? 0 : format.bitrate;
            track.language = format == null ? "" : String.valueOf(format.language);
            track.baseFile = plan.single && !plan.segments.isEmpty() ? plan.segments.get(0).file : "";
            track.indexRange = plan.indexRange;
            track.initRange = plan.initRange;
            track.initFile = plan.single || plan.initSegment == null ? "" : plan.initSegment.file;
            track.mediaTemplate = plan.mediaTemplate();
            track.startNumber = plan.firstNumber;
            track.timescale = plan.timescale;
            int count = complete ? plan.segments.size() : plan.done;
            for (int i = 0; i < count; i++) {
                Segment segment = plan.segments.get(i);
                track.starts.add(segment.startUs);
                track.durations.add(segment.durationUs);
            }
            model.tracks.add(track);
        }
        return model;
    }

    /** 纯数据模型：与 media3 类型解耦，便于单测 */
    static final class MpdModel {

        static final class Track {

            public String kind = "";
            public String mimeType = "";
            public String codecs = "";
            public int width;
            public int height;
            public long bandwidth;
            public String language = "";
            public String baseFile = "";
            public String indexRange = "";
            public String initRange = "";
            public String initFile = "";
            public String mediaTemplate = "";
            public int startNumber = 1;
            public long timescale = 1000000;
            public final List<Long> starts = new ArrayList<>();
            public final List<Long> durations = new ArrayList<>();
        }

        public long durationMs;
        public final List<Track> tracks = new ArrayList<>();
    }

    /**
     * 生成本地 mpd XML：分段轨用 SegmentTimeline 表达“已下载的分片”（边下边播的关键），
     * 单文件轨保留 BaseURL + SegmentBase 原始 indexRange/Initialization range。
     */
    static String buildXml(MpdModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\"");
        if (model.durationMs > 0) sb.append(" mediaPresentationDuration=\"").append(iso8601(model.durationMs)).append("\"");
        sb.append(" minBufferTime=\"PT1.5S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n");
        sb.append("  <Period>\n");
        for (MpdModel.Track track : model.tracks) {
            sb.append("    <AdaptationSet mimeType=\"").append(esc(track.mimeType)).append("\"");
            if (!track.language.isEmpty()) sb.append(" lang=\"").append(esc(track.language)).append("\"");
            sb.append(" segmentAlignment=\"true\">\n");
            sb.append("      <Representation");
            if (track.bandwidth > 0) sb.append(" bandwidth=\"").append(track.bandwidth).append("\"");
            if (!track.codecs.isEmpty()) sb.append(" codecs=\"").append(esc(track.codecs)).append("\"");
            if (track.width > 0) sb.append(" width=\"").append(track.width).append("\" height=\"").append(track.height).append("\"");
            sb.append(">\n");
            if (!track.baseFile.isEmpty()) {
                sb.append("        <BaseURL>").append(esc(track.baseFile)).append("</BaseURL>\n");
                if (!track.indexRange.isEmpty()) {
                    sb.append("        <SegmentBase indexRange=\"").append(esc(track.indexRange)).append("\">\n");
                    if (!track.initRange.isEmpty()) sb.append("          <Initialization range=\"").append(esc(track.initRange)).append("\"/>\n");
                    sb.append("        </SegmentBase>\n");
                }
            } else {
                sb.append("        <SegmentTemplate timescale=\"").append(track.timescale).append("\"");
                if (!track.initFile.isEmpty()) sb.append(" initialization=\"").append(esc(track.initFile)).append("\"");
                if (!track.mediaTemplate.isEmpty()) sb.append(" media=\"").append(esc(track.mediaTemplate)).append("\" startNumber=\"").append(track.startNumber).append("\"");
                sb.append(">\n");
                if (!track.starts.isEmpty()) {
                    sb.append("          <SegmentTimeline>\n");
                    for (int i = 0; i < track.starts.size(); i++) {
                        sb.append("            <S t=\"").append(track.starts.get(i)).append("\" d=\"").append(track.durations.get(i)).append("\"/>\n");
                    }
                    sb.append("          </SegmentTimeline>\n");
                }
                sb.append("        </SegmentTemplate>\n");
            }
            sb.append("      </Representation>\n");
            sb.append("    </AdaptationSet>\n");
        }
        sb.append("  </Period>\n");
        sb.append("</MPD>\n");
        return sb.toString();
    }

    private static String iso8601(long ms) {
        return String.format(Locale.US, "PT%.3fS", ms / 1000.0);
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------- 工具 ----------

    private byte[] fetchMpd() throws Exception {
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma > 0) {
                String meta = url.substring(0, comma);
                String data = url.substring(comma + 1);
                byte[] bytes = meta.endsWith(";base64") ? android.util.Base64.decode(data, android.util.Base64.DEFAULT) : data.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_MPD_BYTES) throw new IllegalStateException("too large");
                return bytes;
            }
        }
        try (Response res = OkHttp.newCall(url, headers).execute()) {
            if (!res.isSuccessful()) throw new IllegalStateException("HTTP " + res.code());
            if (res.body() == null) throw new IllegalStateException("empty body");
            try (InputStream is = res.body().byteStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[BUFFER];
                int read;
                long total = 0;
                while ((read = is.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_MPD_BYTES) throw new IllegalStateException("too large");
                    bos.write(buffer, 0, read);
                }
                return bos.toByteArray();
            }
        }
    }

    private String message(Throwable e) {
        String msg = e.getMessage();
        if ("drm".equals(msg)) return "drm";
        if ("live".equals(msg)) return "live";
        if ("multi-period".equals(msg)) return "multi-period";
        if ("no-video".equals(msg)) return "no-video";
        if ("no-track".equals(msg)) return "unsupported";
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

    // ---------- 轨道计划 ----------

    static final class Segment {

        final String url;
        final long start;
        final long length;
        final String file;
        final long startUs;
        final long durationUs;

        Segment(String url, long start, long length, String file, long startUs, long durationUs) {
            this.url = url;
            this.start = start;
            this.length = length;
            this.file = file;
            this.startUs = startUs;
            this.durationUs = durationUs;
        }
    }

    static final class TrackPlan {

        final Representation rep;
        final String kind;
        final boolean single;
        final long timescale;
        final int firstNumber;
        final String indexRange;
        final String initRange;
        final Segment initSegment;
        final List<Segment> segments;
        int done;
        long bytes;

        private TrackPlan(Representation rep, String kind, boolean single, long timescale, int firstNumber, String indexRange, String initRange, Segment initSegment, List<Segment> segments) {
            this.rep = rep;
            this.kind = kind;
            this.single = single;
            this.timescale = timescale;
            this.firstNumber = firstNumber;
            this.indexRange = indexRange;
            this.initRange = initRange;
            this.initSegment = initSegment;
            this.segments = segments;
        }

        static TrackPlan create(Representation rep, String prefix, String kind, String baseUrl, long periodDurationUs) throws Exception {
            if (rep instanceof Representation.SingleSegmentRepresentation) {
                Representation.SingleSegmentRepresentation single = (Representation.SingleSegmentRepresentation) rep;
                String file = prefix + "_video." + ext(single.uri.toString());
                long length = single.contentLength == C.LENGTH_UNSET ? C.LENGTH_UNSET : single.contentLength;
                Segment segment = new Segment(single.uri.toString(), 0, length, file, 0, periodDurationUs);
                return new TrackPlan(rep, kind, true, 1000000, 1, range(rep.getIndexUri()), range(rep.getInitializationUri()), null, new ArrayList<>(Arrays.asList(segment)));
            }
            if (!(rep instanceof DashSegmentIndex)) throw new IllegalStateException("unsupported");
            DashSegmentIndex index = (DashSegmentIndex) rep;
            if (!index.isExplicit() && periodDurationUs == C.TIME_UNSET) throw new IllegalStateException("unsupported");
            long first = index.getFirstSegmentNum();
            long count = index.getSegmentCount(periodDurationUs);
            if (count <= 0 || count > 100000) throw new IllegalStateException("unsupported");
            Segment init = null;
            RangedUri initUri = rep.getInitializationUri();
            if (initUri != null) {
                String ref = initUri.resolveUriString(baseUrl);
                if (ref == null || ref.isEmpty()) throw new IllegalStateException("unsupported");
                init = new Segment(ref, initUri.start, initUri.length == C.LENGTH_UNSET ? C.LENGTH_UNSET : initUri.length, prefix + "_init." + ext(ref), 0, 0);
            }
            List<Segment> segments = new ArrayList<>();
            for (long n = first; n < first + count; n++) {
                RangedUri ranged = index.getSegmentUrl(n);
                if (ranged == null) throw new IllegalStateException("unsupported");
                String ref = ranged.resolveUriString(baseUrl);
                if (ref == null || ref.isEmpty()) throw new IllegalStateException("unsupported");
                long length = ranged.length == C.LENGTH_UNSET ? C.LENGTH_UNSET : ranged.length;
                segments.add(new Segment(ref, ranged.start, length, String.format(Locale.US, "%s_seg_%05d.%s", prefix, n, ext(ref)), index.getTimeUs(n), index.getDurationUs(n, periodDurationUs)));
            }
            return new TrackPlan(rep, kind, false, 1000000, (int) first, "", "", init, segments);
        }

        String mediaTemplate() {
            if (segments.isEmpty()) return "";
            String file = segments.get(0).file;
            int dot = file.lastIndexOf('.');
            int under = file.lastIndexOf('_');
            if (dot < 0 || under < 0) return file;
            return file.substring(0, under + 1) + "$Number%05d$." + file.substring(dot + 1);
        }

        private static String range(RangedUri uri) {
            if (uri == null) return "";
            long end = uri.length == C.LENGTH_UNSET ? -1 : uri.start + uri.length - 1;
            return uri.start + "-" + (end < 0 ? "" : String.valueOf(end));
        }
    }

    private static String ext(String url) {
        if (url == null) return "bin";
        String path = url;
        int query = path.indexOf('?');
        if (query > 0) path = path.substring(0, query);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot > slash && dot < path.length() - 1) return path.substring(dot + 1).toLowerCase(Locale.US);
        return "bin";
    }
}
