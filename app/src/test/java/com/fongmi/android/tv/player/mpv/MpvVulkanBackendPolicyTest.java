package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MpvVulkanBackendPolicyTest {

    @Test
    public void automaticModeUsesRememberedStableFallback() {
        assertEquals("auto", MpvVulkanBackendPolicy.resolve("", false));
        assertEquals("stable", MpvVulkanBackendPolicy.resolve("auto", true));
    }

    @Test
    public void explicitUserBackendAlwaysWins() {
        assertEquals("direct", MpvVulkanBackendPolicy.resolve("direct", true));
        assertEquals("legacy", MpvVulkanBackendPolicy.resolve("legacy", true));
        assertEquals("fragment", MpvVulkanBackendPolicy.resolve("fragment", true));
    }

    @Test
    public void selectedPriorityControlsConflictingBackend() {
        assertEquals("legacy", MpvVulkanBackendPolicy.resolveConfigured(
                "legacy", "direct", true));
        assertEquals("direct", MpvVulkanBackendPolicy.resolveConfigured(
                "legacy", "direct", false));
        assertEquals("fragment", MpvVulkanBackendPolicy.resolveConfigured(
                "", "fragment", true));
        assertEquals("legacy", MpvVulkanBackendPolicy.resolveConfigured(
                "legacy", "", false));
    }

    @Test
    public void encodedRecordKeepsEnvironmentWithPipeSeparators() {
        String environment = "fingerprint|hardware|soc|33|123";
        String encoded = MpvVulkanBackendPolicy.encodeStableEnvironment(environment, 1_000L);
        assertEquals(environment, MpvVulkanBackendPolicy.storedEnvironment(encoded));
    }

    @Test
    public void legacyRecordWithoutTimestampKeepsWholeValue() {
        assertEquals("legacy-env", MpvVulkanBackendPolicy.storedEnvironment("legacy-env"));
        assertEquals("", MpvVulkanBackendPolicy.storedEnvironment(null));
    }

    @Test
    public void stableRecordIsFreshOnlyInsideTtl() {
        long ttl = MpvVulkanBackendPolicy.STABLE_RECORD_TTL_MS;
        assertTrue(MpvVulkanBackendPolicy.isStableRecordFresh(1_000L, 1_000L, ttl));
        assertTrue(MpvVulkanBackendPolicy.isStableRecordFresh(1_000L, 1_000L + ttl, ttl));
        assertFalse(MpvVulkanBackendPolicy.isStableRecordFresh(1_000L, 1_000L + ttl + 1, ttl));
        assertFalse(MpvVulkanBackendPolicy.isStableRecordFresh(1_000L, 999L, ttl));
        assertFalse(MpvVulkanBackendPolicy.isStableRecordFresh(0L, 1_000L, ttl));
    }
}
