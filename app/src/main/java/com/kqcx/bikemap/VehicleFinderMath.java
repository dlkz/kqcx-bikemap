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
    static final long DIRECTION_MAX_AGE_MS = 5_000L;
    static final long DIRECTION_HOLD_MAX_AGE_MS = 8_000L;
    static final float DIRECTION_MAX_ACCURACY_METERS = 25f;
    static final float DIRECTION_HOLD_MAX_ACCURACY_METERS = 45f;
    static final float DIRECTION_ACCURACY_BASE_METERS = 5f;
    static final float DIRECTION_HOLD_ACCURACY_BASE_METERS = 10f;
    static final float DIRECTION_ACCURACY_DISTANCE_RATIO = 0.25f;
    static final float DIRECTION_HOLD_ACCURACY_DISTANCE_RATIO = 0.5f;
    static final float DIRECTION_HOLD_MAX_DISPLACEMENT_METERS = 5f;
    static final float DIRECTION_HOLD_MAX_BEARING_DELTA_DEGREES = 45f;
    static final float LOCATION_ACCURACY_WORSEN_LIMIT_METERS = 10f;
    static final float LOCATION_ACCURACY_REPLACE_LIMIT_METERS = 30f;
    static final float LOCATION_ACCURACY_REPLACE_MULTIPLIER = 2f;
    static final long LOCATION_REACQUIRE_MAX_WAIT_MS = 5_000L;
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
        long ageMs = locationAgeMs(
                locationElapsedNanos,
                nowElapsedNanos,
                locationWallTimeMs,
                nowWallTimeMs
        );
        return ageMs >= 0L && ageMs <= maxAgeMs;
    }

    static long locationAgeMs(
            long locationElapsedNanos,
            long nowElapsedNanos,
            long locationWallTimeMs,
            long nowWallTimeMs
    ) {
        if (locationElapsedNanos > 0L && nowElapsedNanos > 0L) {
            return (nowElapsedNanos - locationElapsedNanos) / 1_000_000L;
        }
        if (locationWallTimeMs > 0L && nowWallTimeMs > 0L) {
            return nowWallTimeMs - locationWallTimeMs;
        }
        return Long.MAX_VALUE;
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

    static boolean canUseLocationForDirection(
            long locationAgeMs,
            float accuracyMeters,
            float distanceMeters,
            boolean providerAvailable
    ) {
        return providerAvailable
                && locationAgeMs >= 0L
                && locationAgeMs <= DIRECTION_MAX_AGE_MS
                && isDirectionAccuracySufficient(distanceMeters, accuracyMeters);
    }

    static boolean canShowDirectionArrow(
            long locationAgeMs,
            float accuracyMeters,
            float distanceMeters,
            boolean providerAvailable,
            boolean headingReliable
    ) {
        return headingReliable && canUseLocationForDirection(
                locationAgeMs,
                accuracyMeters,
                distanceMeters,
                providerAvailable
        );
    }

    static boolean canHoldDirection(
            long locationAgeMs,
            float accuracyMeters,
            float distanceMeters,
            boolean providerAvailable,
            boolean hasLastBearing,
            boolean headingReliable
    ) {
        return hasLastBearing
                && headingReliable
                && providerAvailable
                && locationAgeMs >= 0L
                && locationAgeMs <= DIRECTION_HOLD_MAX_AGE_MS
                && accuracyMeters > 0f
                && accuracyMeters <= directionHoldAccuracyLimitMeters(distanceMeters);
    }

    static boolean isDirectionAccuracySufficient(
            float distanceMeters,
            float accuracyMeters
    ) {
        return accuracyMeters > 0f
                && distanceMeters > 0f
                && accuracyMeters <= directionAccuracyLimitMeters(distanceMeters);
    }

    static float directionAccuracyLimitMeters(float distanceMeters) {
        return Math.min(
                DIRECTION_MAX_ACCURACY_METERS,
                Math.max(
                        DIRECTION_ACCURACY_BASE_METERS,
                        distanceMeters * DIRECTION_ACCURACY_DISTANCE_RATIO
                )
        );
    }

    static float directionHoldAccuracyLimitMeters(float distanceMeters) {
        return Math.min(
                DIRECTION_HOLD_MAX_ACCURACY_METERS,
                Math.max(
                        DIRECTION_HOLD_ACCURACY_BASE_METERS,
                        distanceMeters * DIRECTION_HOLD_ACCURACY_DISTANCE_RATIO
                )
        );
    }

    static boolean isHeldDirectionConsistent(
            float heldBearingDegrees,
            float currentBearingDegrees,
            float heldDistanceMeters,
            float currentDistanceMeters
    ) {
        return Math.abs(normalizeSignedDegrees(
                currentBearingDegrees - heldBearingDegrees
        )) <= DIRECTION_HOLD_MAX_BEARING_DELTA_DEGREES
                && Math.abs(currentDistanceMeters - heldDistanceMeters)
                <= DIRECTION_HOLD_MAX_DISPLACEMENT_METERS;
    }

    static boolean canReplaceLocation(
            float candidateAccuracyMeters,
            float currentAccuracyMeters,
            boolean significantlyNewer
    ) {
        if (candidateAccuracyMeters <= 0f || currentAccuracyMeters <= 0f) {
            return true;
        }
        float worseBy = candidateAccuracyMeters - currentAccuracyMeters;
        if (worseBy <= LOCATION_ACCURACY_WORSEN_LIMIT_METERS) {
            return true;
        }
        if (worseBy > LOCATION_ACCURACY_REPLACE_LIMIT_METERS
                || candidateAccuracyMeters
                > currentAccuracyMeters * LOCATION_ACCURACY_REPLACE_MULTIPLIER
                + LOCATION_ACCURACY_REPLACE_LIMIT_METERS) {
            return false;
        }
        return significantlyNewer;
    }

    static boolean headingSamplesConsistent(
            float referenceDegrees,
            float candidateDegrees,
            float toleranceDegrees
    ) {
        return Math.abs(normalizeSignedDegrees(
                candidateDegrees - referenceDegrees
        )) <= toleranceDegrees;
    }

    static boolean canCommitReacquiredLocation(
            boolean gpsProvider,
            float accuracyMeters
    ) {
        return gpsProvider
                && accuracyMeters > 0f
                && accuracyMeters <= 35f;
    }

    static boolean isAfterReacquireStart(
            long candidateElapsedNanos,
            long reacquireStartElapsedNanos,
            long candidateWallTimeMs,
            long reacquireStartWallTimeMs
    ) {
        if (candidateElapsedNanos > 0L && reacquireStartElapsedNanos > 0L) {
            return candidateElapsedNanos >= reacquireStartElapsedNanos;
        }
        return candidateWallTimeMs > 0L
                && reacquireStartWallTimeMs > 0L
                && candidateWallTimeMs >= reacquireStartWallTimeMs;
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
