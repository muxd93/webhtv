package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.Player;

/**
 * Detects an mpv session that claims to play but never advances its clock.
 * The device log showed first frame presented, decoders idle, the playhead
 * frozen for 12.9s and then an end-file with {@code error=success}, so no
 * {@code PlaybackException} existed to trigger the normal engine fallback.
 */
public final class MpvStartupStallPolicy {

    // The telemetry tick is 5s, so one window must fit inside a single tick.
    public static final long STALL_WINDOW_MS = 4_000L;
    public static final long MIN_ADVANCE_MS = 500L;

    public enum Action {
        OBSERVE,
        WAIT,
        FALLBACK
    }

    public record Sample(long positionMs, long atMs) {
        public static Sample none() {
            return new Sample(0, -1);
        }

        public boolean known() {
            return atMs > 0;
        }
    }

    public record Decision(Action action, Sample next) {
    }

    private MpvStartupStallPolicy() {
    }

    public static Decision evaluate(
            boolean firstFrameSeen,
            boolean playWhenReady,
            int playbackState,
            long positionMs,
            boolean positionKnown,
            Sample previous,
            boolean interrupted,
            long nowMs) {
        if (!firstFrameSeen
                || !playWhenReady
                || playbackState != Player.STATE_READY
                || !positionKnown) {
            return new Decision(Action.OBSERVE, Sample.none());
        }
        // 采样窗口内发生过 seek/暂停：位置不动或只前进了很少一段，不代表播放器卡住
        if (interrupted) {
            return new Decision(Action.OBSERVE, Sample.none());
        }
        if (!previous.known()) {
            return new Decision(Action.WAIT, new Sample(positionMs, nowMs));
        }
        // 用户后退 seek、循环重播会让位置回退，这不是「没有前进」
        if (positionMs < previous.positionMs()) {
            return new Decision(Action.OBSERVE, new Sample(positionMs, nowMs));
        }
        if (nowMs - previous.atMs() < STALL_WINDOW_MS) {
            return new Decision(Action.WAIT, previous);
        }
        if (positionMs - previous.positionMs() >= MIN_ADVANCE_MS) {
            return new Decision(Action.OBSERVE, new Sample(positionMs, nowMs));
        }
        return new Decision(Action.FALLBACK, previous);
    }
}
