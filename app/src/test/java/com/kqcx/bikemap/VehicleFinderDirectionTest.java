package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VehicleFinderDirectionTest {
    @Test
    public void resolvesDirectionModesInPriorityOrder() {
        assertMode(
                VehicleFinderDirection.Mode.LOCATION_STALE,
                false, true, true, true, true, true, 1_000L
        );
        assertMode(
                VehicleFinderDirection.Mode.PROVIDER_UNAVAILABLE,
                true, false, true, true, true, true, 1_000L
        );
        assertMode(
                VehicleFinderDirection.Mode.NEAR_TARGET,
                true, true, true, true, true, false, 1_000L
        );
        assertMode(
                VehicleFinderDirection.Mode.NEAR_TARGET,
                true, true, false, true, false, false, 1_000L
        );
        assertMode(
                VehicleFinderDirection.Mode.RELATIVE_ARROW,
                true, true, true, false, true, false, 1_000L
        );
        assertMode(
                VehicleFinderDirection.Mode.MAP_BEARING,
                true, true, true, false, false, false, 1_000L
        );
        assertMode(
                VehicleFinderDirection.Mode.HOLD,
                true, true, false, false, false, true,
                VehicleFinderMath.DIRECTION_HOLD_MAX_AGE_MS
        );
        assertMode(
                VehicleFinderDirection.Mode.FIX_TOO_OLD,
                true, true, false, false, false, false,
                VehicleFinderMath.DIRECTION_HOLD_MAX_AGE_MS + 1L
        );
        assertMode(
                VehicleFinderDirection.Mode.ACCURACY_LIMIT,
                true, true, false, false, false, false, 1_000L
        );
    }

    private static void assertMode(
            VehicleFinderDirection.Mode expected,
            boolean locationUsable,
            boolean providerAvailable,
            boolean directionDataReady,
            boolean inTargetArea,
            boolean canShowArrow,
            boolean directionHold,
            long qualityAgeMs
    ) {
        assertEquals(expected, VehicleFinderDirection.resolve(
                locationUsable,
                providerAvailable,
                directionDataReady,
                inTargetArea,
                canShowArrow,
                directionHold,
                qualityAgeMs
        ));
    }
}
