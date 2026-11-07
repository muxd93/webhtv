package com.fongmi.android.tv.player.ijk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;

import org.junit.Test;

public class IjkStablePositionPolicyTest {

    @Test
    public void nearZeroReadingDuringPlaybackKeepsTheSnapshot() {
        // The device log carried 10-13ms readings right around a buffering edge
        // while playback continued at 15s, so the readout must not follow it.
        assertEquals(15_139L, IjkStablePositionPolicy
                .resolve(13, 15_139, Player.STATE_READY, false).positionMs());
        assertFalse(IjkStablePositionPolicy
                .resolve(13, 15_139, Player.STATE_READY, false).cache());
    }

    @Test
    public void nearZeroReadingWithEmptySnapshotIsReportedAndCached() {
        // Startup from the beginning of a stream is a real reading.
        assertEquals(240L, IjkStablePositionPolicy
                .resolve(240, -1, Player.STATE_BUFFERING, false).positionMs());
        assertTrue(IjkStablePositionPolicy
                .resolve(240, -1, Player.STATE_BUFFERING, false).cache());
    }

    @Test
    public void nearZeroReadingWhileBufferingStillCaches() {
        // Only a ready player proves the readout is a glitch; while buffering the
        // counter restart is expected and the snapshot is replaced.
        assertEquals(500L, IjkStablePositionPolicy
                .resolve(500, 9_000, Player.STATE_BUFFERING, false).positionMs());
        assertTrue(IjkStablePositionPolicy
                .resolve(500, 9_000, Player.STATE_BUFFERING, false).cache());
    }

    @Test
    public void advancingReadingIsReportedAndCached() {
        assertEquals(15_453L, IjkStablePositionPolicy
                .resolve(15_453, 15_139, Player.STATE_READY, false).positionMs());
        assertTrue(IjkStablePositionPolicy
                .resolve(15_453, 15_139, Player.STATE_READY, false).cache());
    }

    @Test
    public void readingPastTheDurationFallsBackToTheSnapshotWithoutCaching() {
        // getCurrentPosition can carry the absolute container timeline.
        assertEquals(15_139L, IjkStablePositionPolicy
                .resolve(12_203_935, 15_139, Player.STATE_READY, true).positionMs());
        assertFalse(IjkStablePositionPolicy
                .resolve(12_203_935, 15_139, Player.STATE_READY, true).cache());
    }

    @Test
    public void zeroReadingWhileBufferingKeepsTheSnapshot() {
        assertEquals(4_398L, IjkStablePositionPolicy
                .resolve(0, 4_398, Player.STATE_BUFFERING, false).positionMs());
        assertFalse(IjkStablePositionPolicy
                .resolve(0, 4_398, Player.STATE_BUFFERING, false).cache());
    }

    @Test
    public void zeroReadingWithoutSnapshotReportsZero() {
        // A seek to the very beginning clears the snapshot, so this branch can
        // never resurrect the pre-seek position.
        assertEquals(0L, IjkStablePositionPolicy
                .resolve(0, -1, Player.STATE_READY, false).positionMs());
        assertFalse(IjkStablePositionPolicy
                .resolve(0, -1, Player.STATE_READY, false).cache());
    }
}
