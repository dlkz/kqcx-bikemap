package com.kqcx.bikemap;

final class VehicleFinderDirection {
    enum Mode {
        LOCATION_STALE("location-stale"),
        PROVIDER_UNAVAILABLE("provider-unavailable"),
        NEAR_TARGET("near-target"),
        RELATIVE_ARROW("relative-arrow"),
        MAP_BEARING("map-bearing"),
        HOLD("direction-hold"),
        FIX_TOO_OLD("fix-older-than-8s"),
        ACCURACY_LIMIT("direction-accuracy-limited");

        final String logName;

        Mode(String logName) {
            this.logName = logName;
        }
    }

    private VehicleFinderDirection() {
    }

    static Mode resolve(
            boolean locationUsable,
            boolean providerAvailable,
            boolean directionDataReady,
            boolean inEstimatedTargetArea,
            boolean canShowDirectionArrow,
            boolean directionHold,
            long qualityAgeMs
    ) {
        if (!locationUsable) {
            return Mode.LOCATION_STALE;
        }
        if (!providerAvailable) {
            return Mode.PROVIDER_UNAVAILABLE;
        }
        if (inEstimatedTargetArea) {
            return Mode.NEAR_TARGET;
        }
        if (directionDataReady) {
            return canShowDirectionArrow
                    ? Mode.RELATIVE_ARROW
                    : Mode.MAP_BEARING;
        }
        if (directionHold) {
            return Mode.HOLD;
        }
        return qualityAgeMs > VehicleFinderMath.DIRECTION_HOLD_MAX_AGE_MS
                ? Mode.FIX_TOO_OLD
                : Mode.ACCURACY_LIMIT;
    }
}
