package com.fongmi.android.tv.player.exo;

/**
 * Cooldown based half-open circuit breaker for background preload. While
 * open the breaker refuses tasks until the cooldown elapses; the next task
 * then acts as a single probe. Success closes the breaker, a failure while
 * open re-arms it with a doubled cooldown capped at {@link #MAX_COOLDOWN_MS}.
 */
final class PreloadCircuitBreaker {

    static final long MAX_COOLDOWN_MS = 600_000;
    private static final long MIN_COOLDOWN_MS = 1_000;

    private final int threshold;
    private final long baseCooldownMs;

    private boolean open;
    private boolean probeAnnounced;
    private int failures;
    private long cooldownMs;
    private long probeNotBeforeMs;

    PreloadCircuitBreaker(int threshold, long baseCooldownMs) {
        this.threshold = Math.max(1, threshold);
        this.baseCooldownMs = Math.max(MIN_COOLDOWN_MS, baseCooldownMs);
    }

    boolean blocks(long nowMs) {
        return open && nowMs < probeNotBeforeMs;
    }

    boolean consumeProbeDue(long nowMs) {
        if (!open || nowMs < probeNotBeforeMs || probeAnnounced) return false;
        probeAnnounced = true;
        return true;
    }

    boolean isOpen() {
        return open;
    }

    int failureCount() {
        return failures;
    }

    long cooldownRemainingMs(long nowMs) {
        return open ? Math.max(0, probeNotBeforeMs - nowMs) : 0;
    }

    void onFailure(long nowMs) {
        if (open) {
            cooldownMs = Math.min(MAX_COOLDOWN_MS, Math.max(baseCooldownMs, cooldownMs * 2));
            probeNotBeforeMs = saturatedAdd(nowMs, cooldownMs);
            probeAnnounced = false;
            return;
        }
        failures++;
        if (failures < threshold) return;
        open = true;
        probeAnnounced = false;
        cooldownMs = baseCooldownMs;
        probeNotBeforeMs = saturatedAdd(nowMs, cooldownMs);
    }

    boolean onSuccess() {
        boolean wasOpen = open;
        open = false;
        probeAnnounced = false;
        failures = 0;
        cooldownMs = 0;
        probeNotBeforeMs = 0;
        return wasOpen;
    }

    void clearFailureStreak() {
        if (!open) failures = 0;
    }

    void reset() {
        open = false;
        probeAnnounced = false;
        failures = 0;
        cooldownMs = 0;
        probeNotBeforeMs = 0;
    }

    private static long saturatedAdd(long value, long increment) {
        if (increment <= 0) return value;
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
