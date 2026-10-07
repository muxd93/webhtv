package androidx.media3.mpvplayer;

/**
 * Single-owner lease for the MPV preload cache overlay on the current media
 * item. The overlay raises {@code cache-secs} and &mdash; only while mpv's
 * {@code cache-on-disk} mode is active &mdash; {@code demuxer-max-bytes}. The
 * lease ends on media replacement, on a policy hold, or when the resource
 * pressure controller moves the bytes option below the leased target
 * (yield); it may extend again once the bytes baseline is restored.
 */
final class MpvPreloadOverlayState {

    private boolean applied;
    private boolean diskModeApplied;
    private boolean yielded;
    private int baselineSeconds;
    private long baselineBytes;
    private long yieldBaselineBytes;
    private int targetSeconds;
    private long targetBytes;

    boolean applied() {
        return applied;
    }

    boolean diskModeApplied() {
        return diskModeApplied;
    }

    boolean yielded() {
        return yielded;
    }

    int baselineSeconds() {
        return baselineSeconds;
    }

    long baselineBytes() {
        return baselineBytes;
    }

    int targetSeconds() {
        return targetSeconds;
    }

    long targetBytes() {
        return targetBytes;
    }

    boolean begin(int baselineSeconds, long baselineBytes) {
        if (applied) return false;
        applied = true;
        this.baselineSeconds = Math.max(0, baselineSeconds);
        this.baselineBytes = Math.max(0, baselineBytes);
        targetSeconds = 0;
        targetBytes = 0;
        return true;
    }

    void setDiskModeApplied(boolean diskModeApplied) {
        this.diskModeApplied = diskModeApplied;
    }

    void commit(int targetSeconds, long targetBytes) {
        this.targetSeconds = Math.max(0, targetSeconds);
        this.targetBytes = Math.max(0, targetBytes);
    }

    /** True when another writer moved native bytes below the leased target. */
    boolean bytesLeaseBroken(long nativeBytes) {
        return applied && diskModeApplied && targetBytes > 0 && nativeBytes < targetBytes;
    }

    /** Relinquishes the lease until native bytes return to the captured baseline. */
    void beginYield() {
        applied = false;
        yielded = true;
        yieldBaselineBytes = baselineBytes;
        targetSeconds = 0;
        targetBytes = 0;
    }

    /** Returns true when no yield is pending or the bytes baseline was restored. */
    boolean maybeRecover(long nativeBytes) {
        if (!yielded) return true;
        if (nativeBytes < yieldBaselineBytes) return false;
        yielded = false;
        yieldBaselineBytes = 0;
        return true;
    }

    void clear() {
        applied = false;
        diskModeApplied = false;
        yielded = false;
        baselineSeconds = 0;
        baselineBytes = 0;
        yieldBaselineBytes = 0;
        targetSeconds = 0;
        targetBytes = 0;
    }
}
