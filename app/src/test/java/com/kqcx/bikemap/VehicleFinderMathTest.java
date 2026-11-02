package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VehicleFinderMathTest {
    @Test
    public void normalizesDegreesAndSignedTurns() {
        assertEquals(350f, VehicleFinderMath.normalizeDegrees(-10f), 0.001f);
        assertEquals(-10f, VehicleFinderMath.normalizeSignedDegrees(350f), 0.001f);
        assertEquals(180f, VehicleFinderMath.normalizeSignedDegrees(-180f), 0.001f);
    }

    @Test
    public void usesElapsedRealtimeAndRejectsFutureFixes() {
        assertTrue(VehicleFinderMath.isFresh(
                1_000_000_000L,
                121_000_000_000L,
                0L,
                0L,
                120_000L
        ));
        assertFalse(VehicleFinderMath.isFresh(
                122_000_000_000L,
                1_000_000_000L,
                0L,
                0L,
                120_000L
        ));
    }

    @Test
    public void fallsBackToWallClockWhenElapsedTimeMissing() {
        assertTrue(VehicleFinderMath.isFresh(
                0L,
                0L,
                1_000L,
                121_000L,
                120_000L
        ));
        assertFalse(VehicleFinderMath.isFresh(
                0L,
                0L,
                1_000L,
                121_001L,
                120_000L
        ));
    }
}
