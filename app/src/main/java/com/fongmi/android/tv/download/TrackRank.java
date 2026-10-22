package com.fongmi.android.tv.download;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

/**
 * 轨道选择的“设备可解码优先”排序（HLS 变体 / DASH 表示共用）。
 * 背景：master/mpd 常把最高码率轨给 HEVC，老盒子只有 AVC 硬解——按纯码率选轨会
 * 下载一个本机解不动的文件。策略是降权而非排除：设备确认不支持只排在有硬件解码的
 * 轨之后（应用还有软件/ffmpeg 解码兜底，不能一票否决）。
 */
public final class TrackRank {

    public static final int SCORE_UNKNOWN = 0;
    public static final int SCORE_SOFTWARE = 1;
    public static final int SCORE_DEVICE = 2;

    private TrackRank() {
    }

    /** 解码适配分：2=设备有 MediaCodec 且尺寸支持；1=无 MediaCodec（软件解码或可容忍）；0=信息不足（中性） */
    public static int score(Format format) {
        if (format == null) return SCORE_UNKNOWN;
        try {
            String codecMime = MimeTypes.getVideoMediaMimeType(format.codecs);
            if (codecMime == null) return SCORE_UNKNOWN;
            MediaCodecInfo info = MediaCodecUtil.getDecoderInfo(codecMime, false, false);
            if (info == null) return SCORE_SOFTWARE;
            if (info.capabilities == null || info.capabilities.getVideoCapabilities() == null) return SCORE_DEVICE;
            if (format.width == Format.NO_VALUE || format.height == Format.NO_VALUE) return SCORE_DEVICE;
            android.media.MediaCodecInfo.VideoCapabilities caps = info.capabilities.getVideoCapabilities();
            // 帧率未知按 30fps 保守评估；与 PlayerOsdController 一致地兼容旋转元数据（竖放横片）
            boolean ok = caps.areSizeAndRateSupported(format.width, format.height, 30) || caps.areSizeAndRateSupported(format.height, format.width, 30);
            return ok ? SCORE_DEVICE : SCORE_SOFTWARE;
        } catch (Exception e) {
            // 探测不可用（含单测环境的 android 桩）：按中性处理，不阻塞选轨
            return SCORE_UNKNOWN;
        }
    }

    /** 排序比较器：先按解码适配分降序，再按码率降序（纯函数，可单测） */
    public static int compare(Format a, Format b) {
        int scoreDiff = Integer.compare(score(b), score(a));
        if (scoreDiff != 0) return scoreDiff;
        long bitrateA = a == null || a.bitrate == Format.NO_VALUE ? -1 : a.bitrate;
        long bitrateB = b == null || b.bitrate == Format.NO_VALUE ? -1 : b.bitrate;
        return Long.compare(bitrateB, bitrateA);
    }
}
