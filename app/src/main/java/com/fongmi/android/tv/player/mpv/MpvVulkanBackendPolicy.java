package com.fongmi.android.tv.player.mpv;

import android.os.Build;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.setting.MpvPerformanceSetting;
import com.github.catvod.utils.Prefers;

import java.util.Locale;

/** Selects the low-power Vulkan import path while remembering proven driver failures. */
public final class MpvVulkanBackendPolicy {

    public static final String OPTION = "android-vulkan-aimagereader-backend";
    public static final String AUTO = "auto";
    public static final String DIRECT = "direct";
    public static final String LEGACY = "legacy";
    public static final String STABLE = "stable";
    private static final String KEY_STABLE_ENVIRONMENT = "mpv_vulkan_stable_environment_v1";
    /** Driver failures are re-probed after this TTL instead of being remembered forever. */
    static final long STABLE_RECORD_TTL_MS = 3L * 24 * 60 * 60 * 1000;

    private MpvVulkanBackendPolicy() {
    }

    public static String configuredBackend() {
        return resolveConfigured(MpvPerformanceSetting.getVulkanBackendOption(),
                MpvConfigStore.getOptionValue(OPTION),
                MpvPerformanceSetting.isPerformancePriority());
    }

    public static String appOverride() {
        return normalize(MpvPerformanceSetting.getVulkanBackendOption());
    }

    public static boolean isAutomaticConfig() {
        String value = configuredBackend();
        return value.isEmpty() || AUTO.equals(value);
    }

    public static String automaticOverride() {
        return isStableRemembered() ? STABLE : "";
    }

    public static void rememberDirectFailure() {
        Prefers.put(KEY_STABLE_ENVIRONMENT,
                encodeStableEnvironment(currentEnvironment(), System.currentTimeMillis()));
    }

    public static boolean isStableRemembered() {
        return isStableRememberedAt(System.currentTimeMillis());
    }

    static boolean isStableRememberedAt(long nowMs) {
        String stored = Prefers.getString(KEY_STABLE_ENVIRONMENT);
        if (stored == null || stored.isEmpty()) return false;
        int separator = stored.lastIndexOf('|');
        if (separator < 0) {
            // Legacy record without a timestamp: honor it for this app version;
            // the next failure re-records it with a timestamp.
            return currentEnvironment().equals(stored);
        }
        long recordedAtMs;
        try {
            recordedAtMs = Long.parseLong(stored.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return false;
        }
        return currentEnvironment().equals(stored.substring(0, separator))
                && isStableRecordFresh(recordedAtMs, nowMs, STABLE_RECORD_TTL_MS);
    }

    static String encodeStableEnvironment(String environment, long recordedAtMs) {
        return environment + '|' + recordedAtMs;
    }

    static String storedEnvironment(String stored) {
        if (stored == null) return "";
        int separator = stored.lastIndexOf('|');
        return separator < 0 ? stored : stored.substring(0, separator);
    }

    static boolean isStableRecordFresh(long recordedAtMs, long nowMs, long ttlMs) {
        return recordedAtMs > 0 && nowMs >= recordedAtMs && nowMs - recordedAtMs <= ttlMs;
    }

    static String resolve(String configured, boolean stableRemembered) {
        String value = normalize(configured);
        if (!value.isEmpty() && !AUTO.equals(value)) return value;
        return stableRemembered ? STABLE : AUTO;
    }

    static String resolveConfigured(String appSetting, String configSetting,
                                    boolean performancePriority) {
        String app = normalize(appSetting);
        String config = normalize(configSetting);
        if (performancePriority) return app.isEmpty() ? config : app;
        return config.isEmpty() ? app : config;
    }

    private static String currentEnvironment() {
        return safe(Build.FINGERPRINT) + '|' + safe(Build.HARDWARE) + '|'
                + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? safe(Build.SOC_MODEL) : "")
                + '|' + Build.VERSION.SDK_INT + '|' + BuildConfig.VERSION_CODE;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case AUTO, DIRECT, LEGACY, STABLE, "compute", "fragment" -> normalized;
            default -> "";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
