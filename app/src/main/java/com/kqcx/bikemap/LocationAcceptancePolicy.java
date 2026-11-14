package com.kqcx.bikemap;

final class LocationAcceptancePolicy {
    private LocationAcceptancePolicy() {
    }

    static boolean isOlder(
            long candidateElapsedNanos,
            long currentElapsedNanos,
            long candidateWallTimeMs,
            long currentWallTimeMs,
            long toleranceMs
    ) {
        long toleranceNanos = toleranceMs * 1_000_000L;
        if (candidateElapsedNanos > 0L && currentElapsedNanos > 0L) {
            return candidateElapsedNanos + toleranceNanos < currentElapsedNanos;
        }
        return candidateWallTimeMs > 0L
                && currentWallTimeMs > 0L
                && candidateWallTimeMs + toleranceMs < currentWallTimeMs;
    }

    static boolean isSignificantlyNewer(
            long candidateElapsedNanos,
            long currentElapsedNanos,
            long candidateWallTimeMs,
            long currentWallTimeMs,
            long thresholdMs
    ) {
        long thresholdNanos = thresholdMs * 1_000_000L;
        if (candidateElapsedNanos > 0L && currentElapsedNanos > 0L) {
            return candidateElapsedNanos > currentElapsedNanos
                    && candidateElapsedNanos - currentElapsedNanos > thresholdNanos;
        }
        return candidateWallTimeMs > 0L
                && currentWallTimeMs > 0L
                && candidateWallTimeMs - currentWallTimeMs > thresholdMs;
    }

    static boolean shouldAccept(
            boolean candidateNetwork,
            boolean currentGps,
            boolean currentGpsFresh,
            boolean candidateHasAccuracy,
            float candidateAccuracyMeters,
            boolean currentHasAccuracy,
            float currentAccuracyMeters,
            boolean significantlyNewer
    ) {
        if (candidateNetwork
                && currentGps
                && (currentGpsFresh || !significantlyNewer)) {
            return false;
        }
        if (currentHasAccuracy
                && currentAccuracyMeters > 0f
                && !candidateHasAccuracy) {
            return false;
        }
        return !candidateHasAccuracy
                || !currentHasAccuracy
                || VehicleFinderMath.canReplaceLocation(
                candidateAccuracyMeters,
                currentAccuracyMeters,
                significantlyNewer
        );
    }
}
