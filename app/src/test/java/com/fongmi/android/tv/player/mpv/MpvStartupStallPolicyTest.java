package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.Player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvStartupStallPolicyTest {

    private static MpvStartupStallPolicy.Decision evaluate(
            boolean firstFrame, boolean playWhenReady, int state,
            long positionMs, boolean positionKnown, MpvStartupStallPolicy.Sample previous,
            long now) {
        return evaluate(firstFrame, playWhenReady, state, positionMs, positionKnown, previous, false, now);
    }

    private static MpvStartupStallPolicy.Decision evaluate(
            boolean firstFrame, boolean playWhenReady, int state,
            long positionMs, boolean positionKnown, MpvStartupStallPolicy.Sample previous,
            boolean interrupted, long now) {
        return MpvStartupStallPolicy.evaluate(
                firstFrame, playWhenReady, state, positionMs, positionKnown, previous, interrupted, now);
    }

    @Test
    public void missingFirstFrameOnlyObserves() {
        MpvStartupStallPolicy.Decision decision = evaluate(
                false, true, Player.STATE_READY, 5_000, true,
                new MpvStartupStallPolicy.Sample(1_000, 1), 10_000);

        assertEquals(MpvStartupStallPolicy.Action.OBSERVE, decision.action());
        assertFalse(decision.next().known());
    }

    @Test
    public void pausedSessionNeverFallsBack() {
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, false, Player.STATE_READY, 5_000, true,
                new MpvStartupStallPolicy.Sample(5_000, 1_000), 10_000);

        assertEquals(MpvStartupStallPolicy.Action.OBSERVE, decision.action());
    }

    @Test
    public void bufferingSessionNeverFallsBack() {
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_BUFFERING, 5_000, true,
                new MpvStartupStallPolicy.Sample(5_000, 1_000), 10_000);

        assertEquals(MpvStartupStallPolicy.Action.OBSERVE, decision.action());
        assertFalse(decision.next().known());
    }

    @Test
    public void unknownPositionNeverFallsBack() {
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_READY, 0, false,
                new MpvStartupStallPolicy.Sample(5_000, 1_000), 10_000);

        assertEquals(MpvStartupStallPolicy.Action.OBSERVE, decision.action());
    }

    @Test
    public void firstReadySampleOnlyStartsTheWindow() {
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_READY, 4_000, true,
                MpvStartupStallPolicy.Sample.none(), 20_000);

        assertEquals(MpvStartupStallPolicy.Action.WAIT, decision.action());
        assertTrue(decision.next().known());
        assertEquals(4_000, decision.next().positionMs());
        assertEquals(20_000, decision.next().atMs());
    }

    @Test
    public void windowShorterThanTheTickKeepsWaiting() {
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_READY, 4_000, true,
                new MpvStartupStallPolicy.Sample(4_000, 20_000), 22_000);

        assertEquals(MpvStartupStallPolicy.Action.WAIT, decision.action());
        assertEquals(20_000, decision.next().atMs());
    }

    @Test
    public void frozenPlayheadAcrossOneTickFallsBack() {
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_READY, 4_000, true,
                new MpvStartupStallPolicy.Sample(4_000, 20_000), 25_000);

        assertEquals(MpvStartupStallPolicy.Action.FALLBACK, decision.action());
    }

    @Test
    public void subHalfSecondDriftIsStillAFallback() {
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_READY, 4_200, true,
                new MpvStartupStallPolicy.Sample(4_000, 20_000), 25_000);

        assertEquals(MpvStartupStallPolicy.Action.FALLBACK, decision.action());
    }

    @Test
    public void backwardSeekOfTenSecondsNeverFallsBack() {
        // 用户后退 10s：位置回退不是「没有前进」，不能判卡死
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_READY, 4_000, true,
                new MpvStartupStallPolicy.Sample(14_000, 20_000), 25_000);

        assertEquals(MpvStartupStallPolicy.Action.OBSERVE, decision.action());
        assertEquals(4_000, decision.next().positionMs());
        assertEquals(25_000, decision.next().atMs());
    }

    @Test
    public void pauseInsideTheWindowNeverFallsBack() {
        // 暂停 5s 再播：两次采样之间发生过暂停，位置只前进了 200ms 也不能判卡死
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_READY, 4_200, true,
                new MpvStartupStallPolicy.Sample(4_000, 20_000), true, 25_000);

        assertEquals(MpvStartupStallPolicy.Action.OBSERVE, decision.action());
        assertFalse(decision.next().known());
    }

    @Test
    public void trueStallStillFallsBackAfterASeekReset() {
        // 作废采样后重新观察一个窗口：位置仍然不动就必须继续判卡死
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_READY, 4_000, true,
                new MpvStartupStallPolicy.Sample(4_000, 25_000), 30_000);

        assertEquals(MpvStartupStallPolicy.Action.FALLBACK, decision.action());
    }

    @Test
    public void advancingPlayheadMovesTheWindow() {
        MpvStartupStallPolicy.Decision decision = evaluate(
                true, true, Player.STATE_READY, 5_000, true,
                new MpvStartupStallPolicy.Sample(4_000, 20_000), 25_000);

        assertEquals(MpvStartupStallPolicy.Action.OBSERVE, decision.action());
        assertEquals(5_000, decision.next().positionMs());
        assertEquals(25_000, decision.next().atMs());
    }
}
