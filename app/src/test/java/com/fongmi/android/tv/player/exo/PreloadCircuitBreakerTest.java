package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreloadCircuitBreakerTest {

    @Test
    public void thresholdTwoOpensOnlyOnSecondFailure() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(2, 60_000);
        breaker.onFailure(1_000);
        assertFalse(breaker.isOpen());
        assertEquals(1, breaker.failureCount());
        breaker.onFailure(2_000);
        assertTrue(breaker.isOpen());
        assertTrue(breaker.blocks(2_000));
        assertEquals(60_000, breaker.cooldownRemainingMs(2_000));
    }

    @Test
    public void thresholdOneOpensImmediately() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(1, 60_000);
        breaker.onFailure(5_000);
        assertTrue(breaker.isOpen());
        assertTrue(breaker.blocks(5_000));
    }

    @Test
    public void cooldownBlocksThenAllowsSingleProbe() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(1, 60_000);
        breaker.onFailure(0);
        assertTrue(breaker.blocks(59_999));
        assertFalse(breaker.blocks(60_000));
        assertTrue(breaker.consumeProbeDue(60_000));
        assertFalse(breaker.consumeProbeDue(61_000));
        assertFalse(breaker.blocks(61_000));
    }

    @Test
    public void probeNotAnnouncedBeforeCooldownElapses() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(1, 60_000);
        breaker.onFailure(0);
        assertFalse(breaker.consumeProbeDue(30_000));
        assertTrue(breaker.blocks(30_000));
    }

    @Test
    public void probeSuccessClosesAndClearsStreak() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(2, 60_000);
        breaker.onFailure(0);
        breaker.onFailure(1_000);
        breaker.consumeProbeDue(61_000);
        assertTrue(breaker.onSuccess());
        assertFalse(breaker.isOpen());
        assertFalse(breaker.blocks(62_000));
        assertEquals(0, breaker.failureCount());
        assertTrue(breaker.cooldownRemainingMs(62_000) == 0);
    }

    @Test
    public void successWhileClosedStillClearsStreak() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(2, 60_000);
        breaker.onFailure(0);
        assertFalse(breaker.onSuccess());
        assertEquals(0, breaker.failureCount());
        breaker.onFailure(1_000);
        breaker.onFailure(2_000);
        assertTrue(breaker.isOpen());
    }

    @Test
    public void probeFailureDoublesCooldown() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(1, 60_000);
        breaker.onFailure(0);
        breaker.consumeProbeDue(60_000);
        breaker.onFailure(62_000);
        assertTrue(breaker.isOpen());
        assertTrue(breaker.blocks(181_999));
        assertFalse(breaker.blocks(182_000));
        assertEquals(120_000, breaker.cooldownRemainingMs(62_000));
    }

    @Test
    public void repeatedProbeFailuresCapCooldown() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(1, 60_000);
        long now = 0;
        breaker.onFailure(now);
        for (int round = 0; round < 8; round++) {
            now += breaker.cooldownRemainingMs(now);
            breaker.consumeProbeDue(now);
            breaker.onFailure(now);
        }
        assertEquals(PreloadCircuitBreaker.MAX_COOLDOWN_MS, breaker.cooldownRemainingMs(now));
    }

    @Test
    public void clearFailureStreakKeepsOpenState() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(2, 60_000);
        breaker.onFailure(0);
        breaker.onFailure(1_000);
        breaker.clearFailureStreak();
        assertTrue(breaker.isOpen());
        assertEquals(2, breaker.failureCount());
        PreloadCircuitBreaker closed = new PreloadCircuitBreaker(2, 60_000);
        closed.onFailure(0);
        closed.clearFailureStreak();
        assertEquals(0, closed.failureCount());
        closed.onFailure(1_000);
        assertFalse(closed.isOpen());
    }

    @Test
    public void resetReturnsToFreshState() {
        PreloadCircuitBreaker breaker = new PreloadCircuitBreaker(1, 60_000);
        breaker.onFailure(0);
        breaker.reset();
        assertFalse(breaker.isOpen());
        assertFalse(breaker.blocks(1_000));
        assertFalse(breaker.consumeProbeDue(2_000));
        assertEquals(0, breaker.failureCount());
    }
}
