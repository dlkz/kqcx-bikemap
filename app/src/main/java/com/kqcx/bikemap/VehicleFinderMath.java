package com.kqcx.bikemap;

final class VehicleFinderMath {
    static final int ACCURACY_UNKNOWN = -1;
    static final int ACCURACY_GOOD = 0;
    static final int ACCURACY_MODERATE = 1;
    static final int ACCURACY_POOR = 2;
    static final int TARGET_FRESH = 0;
    static final int TARGET_AGING = 1;
    static final int TARGET_STALE = 2;
    static final int TARGET_UNKNOWN = 3;
    private static final long TARGET_AGING_MS = 10L * 60L * 1_000L;
    private static final long TARGET_STALE_MS = 60L * 60L * 1_000L;

    private VehicleFinderMath() {
    }

    static float normalizeDegrees(float degrees) {
        float normalized = degrees % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    static float normalizeSignedDegrees(float degrees) {
        float normalized = normalizeDegrees(degrees);
        return normalized > 180f ? normalized - 360f : normalized;
    }

    static boolean isFresh(
            long locationElapsedNanos,
            long nowElapsedNanos,
            long locationWallTimeMs,
            long nowWallTimeMs,
            long maxAgeMs
    ) {
        if (locationElapsedNanos > 0L && nowElapsedNanos > 0L) {
            long ageMs = (nowElapsedNanos - locationElapsedNanos) / 1_000_000L;
            return ageMs >= 0L && ageMs <= maxAgeMs;
        }
        if (locationWallTimeMs > 0L && nowWallTimeMs > 0L) {
            long ageMs = nowWallTimeMs - locationWallTimeMs;
            return ageMs >= 0L && ageMs <= maxAgeMs;
        }
        return false;
    }

    static int accuracyQuality(float accuracyMeters, int previousQuality) {
        if (Float.isNaN(accuracyMeters) || accuracyMeters <= 0f) {
            return ACCURACY_UNKNOWN;
        }
        if (previousQuality == ACCURACY_GOOD && accuracyMeters <= 45f) {
            return ACCURACY_GOOD;
        }
        if (previousQuality == ACCURACY_MODERATE
                && accuracyMeters >= 30f
                && accuracyMeters <= 90f) {
            return ACCURACY_MODERATE;
        }
        if (previousQuality == ACCURACY_POOR && accuracyMeters >= 70f) {
            return ACCURACY_POOR;
        }
        if (accuracyMeters <= 35f) {
            return ACCURACY_GOOD;
        }
        return accuracyMeters <= 80f ? ACCURACY_MODERATE : ACCURACY_POOR;
    }

    static int bucketAccuracyMeters(float accuracyMeters, int quality) {
        if (quality == ACCURACY_POOR) {
            return Math.max(50, (int) Math.ceil(accuracyMeters / 50f) * 50);
        }
        return Math.max(10, (int) Math.ceil(accuracyMeters / 10f) * 10);
    }

    static int approximateDistanceMeters(float distanceMeters, int quality) {
        int step;
        if (distanceMeters < 100f) {
            step = quality == ACCURACY_GOOD || quality == ACCURACY_UNKNOWN ? 5 : 10;
        } else {
            step = quality == ACCURACY_GOOD || quality == ACCURACY_UNKNOWN ? 10 : 50;
        }
        return Math.max(step, Math.round(distanceMeters / step) * step);
    }

    static boolean isReliableNearTarget(float distanceMeters, float accuracyMeters) {
        return accuracyMeters > 0f
                && accuracyMeters <= 20f
                && distanceMeters <= Math.max(8f, accuracyMeters);
    }

    static int targetFreshness(boolean uploadTimeKnown, long ageMs) {
        if (!uploadTimeKnown) {
            return TARGET_UNKNOWN;
        }
        if (ageMs > TARGET_STALE_MS) {
            return TARGET_STALE;
        }
        return ageMs > TARGET_AGING_MS ? TARGET_AGING : TARGET_FRESH;
    }
}
