package com.kqcx.bikemap;

final class VehicleFinderMath {
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
}
