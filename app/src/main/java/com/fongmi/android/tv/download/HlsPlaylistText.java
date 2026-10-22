package com.fongmi.android.tv.download;

import java.util.List;
import java.util.Locale;

/**
 * 本地 playlist 文本生成（纯函数，无 IO 无 Android 依赖，可单测）。
 * 规则：未完成不写 EXT-X-ENDLIST（播放器按 live 轮询追加 → 边下边播）；
 * 保留原始 mediaSequence（AES 无显式 IV 时播放器按序号推导 IV，必须保序）；
 * 按 relativeDiscontinuitySequence 变化补 EXT-X-DISCONTINUITY。
 */
public class HlsPlaylistText {

    public static class SegmentSpec {

        public final long durationUs;
        public final int relativeDiscontinuitySequence;
        public final String ext;

        public SegmentSpec(long durationUs, int relativeDiscontinuitySequence, String ext) {
            this.durationUs = durationUs;
            this.relativeDiscontinuitySequence = relativeDiscontinuitySequence;
            this.ext = ext == null || ext.isEmpty() ? "ts" : ext;
        }
    }

    private HlsPlaylistText() {
    }

    public static String build(long mediaSequence, int version, long targetDurationUs, String keyUri, String keyIv, String initFile, List<SegmentSpec> segments, boolean complete) {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:").append(version > 0 ? version : 3).append("\n");
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append("\n");
        sb.append("#EXT-X-TARGETDURATION:").append(Math.max(1, (long) Math.ceil(targetDurationUs / 1000000.0))).append("\n");
        if (complete) sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
        if (keyUri != null) {
            sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"").append(keyUri).append("\"");
            if (keyIv != null) sb.append(",IV=").append(keyIv);
            sb.append("\n");
        }
        if (initFile != null) sb.append("#EXT-X-MAP:URI=\"").append(initFile).append("\"\n");
        int lastDiscontinuity = 0;
        for (int i = 0; i < segments.size(); i++) {
            SegmentSpec segment = segments.get(i);
            if (segment.relativeDiscontinuitySequence > lastDiscontinuity) {
                sb.append("#EXT-X-DISCONTINUITY\n");
                lastDiscontinuity = segment.relativeDiscontinuitySequence;
            }
            sb.append(String.format(Locale.US, "#EXTINF:%.6f,\n", segment.durationUs / 1000000.0));
            sb.append(String.format(Locale.US, "seg_%05d.%s\n", i, segment.ext));
        }
        if (complete) sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();
    }

    /** 播放中的 playlist 是否已含结束标记（用于调试/断言） */
    public static boolean isComplete(String text) {
        return text != null && text.contains("#EXT-X-ENDLIST");
    }

    /** 已写入的分片行数（用于调试/断言） */
    public static int countSegments(String text) {
        if (text == null) return 0;
        int count = 0;
        for (String line : text.split("\n")) if (line.startsWith("seg_")) count++;
        return count;
    }
}
