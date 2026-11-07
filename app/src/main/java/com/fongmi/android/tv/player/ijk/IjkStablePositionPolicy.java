package com.fongmi.android.tv.player.ijk;

import androidx.media3.common.Player;

/**
 * Chooses which ijk position reading survives into the reported player state.
 * A seek always clears the snapshot first, so a genuine restart near zero is
 * reported as is; only a glitch in the middle of playback keeps the snapshot.
 */
public final class IjkStablePositionPolicy {

    /** Below this ijk's buffering edge can report a counter restarting from zero. */
    public static final long NEAR_ZERO_MS = 1_000L;

    private IjkStablePositionPolicy() {
    }

    public record Decision(long positionMs, boolean cache) {
    }

    /**
     * @param actual        the raw reading from ijk
     * @param previous      the last trustworthy reading, or a non-positive value
     *                      when the snapshot is empty (start of session or a seek)
     * @param playbackState one of {@link Player} STATE constants
     * @param pastDuration  whether the reading is beyond the known duration
     */
    public static Decision resolve(
            long actual, long previous, int playbackState, boolean pastDuration) {
        boolean hasSnapshot = previous > 0;
        long snapshot = hasSnapshot ? previous : 0;
        if (actual > 0 && !pastDuration) {
            // 10-13ms readings appear right around a buffering transition while
            // playback continues, so they must not reset the snapshot either.
            if (actual < NEAR_ZERO_MS
                    && hasSnapshot
                    && playbackState == Player.STATE_READY) {
                return new Decision(snapshot, false);
            }
            return new Decision(actual, true);
        }
        if (actual > 0) return new Decision(snapshot, false);
        if (playbackState == Player.STATE_BUFFERING) return new Decision(snapshot, false);
        return new Decision(0, false);
    }
}
