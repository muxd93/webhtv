package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvPreloadOverlayStateTest {

    @Test
    public void beginIsIdempotentWhileApplied() {
        MpvPreloadOverlayState state = new MpvPreloadOverlayState();
        assertTrue(state.begin(20, 64 << 20));
        assertFalse(state.begin(30, 128 << 20));
        assertEquals(20, state.baselineSeconds());
        assertEquals(64L << 20, state.baselineBytes());
    }

    @Test
    public void commitRecordsLeasedTargets() {
        MpvPreloadOverlayState state = new MpvPreloadOverlayState();
        state.begin(20, 64L << 20);
        state.commit(300, 128L << 20);
        assertEquals(300, state.targetSeconds());
        assertEquals(128L << 20, state.targetBytes());
        assertTrue(state.applied());
    }

    @Test
    public void leaseBreaksOnlyBelowTargetWithDiskMode() {
        MpvPreloadOverlayState state = new MpvPreloadOverlayState();
        state.begin(20, 64L << 20);
        state.commit(300, 128L << 20);
        assertFalse(state.bytesLeaseBroken(128L << 20));
        assertFalse(state.bytesLeaseBroken((128L << 20) + 1));
        assertFalse(state.bytesLeaseBroken((64L << 20)));
        state.setDiskModeApplied(true);
        assertTrue(state.bytesLeaseBroken(64L << 20));
        assertFalse(state.bytesLeaseBroken(128L << 20));
        assertFalse(state.bytesLeaseBroken((128L << 20) + 1));
    }

    @Test
    public void yieldDropsLeaseUntilBaselineRestored() {
        MpvPreloadOverlayState state = new MpvPreloadOverlayState();
        state.begin(20, 64L << 20);
        state.setDiskModeApplied(true);
        state.commit(300, 128L << 20);
        state.beginYield();
        assertFalse(state.applied());
        assertTrue(state.yielded());
        assertFalse(state.maybeRecover(32L << 20));
        assertTrue(state.yielded());
        assertTrue(state.maybeRecover(64L << 20));
        assertFalse(state.yielded());
        assertTrue(state.maybeRecover(1));
    }

    @Test
    public void clearResetsEverything() {
        MpvPreloadOverlayState state = new MpvPreloadOverlayState();
        state.begin(20, 64L << 20);
        state.setDiskModeApplied(true);
        state.commit(300, 128L << 20);
        state.clear();
        assertFalse(state.applied());
        assertFalse(state.diskModeApplied());
        assertFalse(state.yielded());
        assertEquals(0, state.baselineSeconds());
        assertEquals(0, state.baselineBytes());
        assertEquals(0, state.targetSeconds());
        assertEquals(0, state.targetBytes());
    }

    @Test
    public void negativeInputsAreClamped() {
        MpvPreloadOverlayState state = new MpvPreloadOverlayState();
        state.begin(-5, -1);
        assertEquals(0, state.baselineSeconds());
        assertEquals(0, state.baselineBytes());
        state.commit(-1, -1);
        assertEquals(0, state.targetSeconds());
        assertEquals(0, state.targetBytes());
    }
}
