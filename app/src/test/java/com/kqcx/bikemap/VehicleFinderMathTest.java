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

    @Test
    public void gradesAccuracyWithoutFlickeringAtBoundaries() {
        assertEquals(
                VehicleFinderMath.ACCURACY_UNKNOWN,
                VehicleFinderMath.accuracyQuality(0f, VehicleFinderMath.ACCURACY_MODERATE)
        );
        assertEquals(
                VehicleFinderMath.ACCURACY_GOOD,
                VehicleFinderMath.accuracyQuality(35f, VehicleFinderMath.ACCURACY_UNKNOWN)
        );
        assertEquals(
                VehicleFinderMath.ACCURACY_GOOD,
                VehicleFinderMath.accuracyQuality(45f, VehicleFinderMath.ACCURACY_GOOD)
        );
        assertEquals(
                VehicleFinderMath.ACCURACY_MODERATE,
                VehicleFinderMath.accuracyQuality(46f, VehicleFinderMath.ACCURACY_GOOD)
        );
        assertEquals(
                VehicleFinderMath.ACCURACY_MODERATE,
                VehicleFinderMath.accuracyQuality(80f, VehicleFinderMath.ACCURACY_UNKNOWN)
        );
        assertEquals(
                VehicleFinderMath.ACCURACY_MODERATE,
                VehicleFinderMath.accuracyQuality(90f, VehicleFinderMath.ACCURACY_MODERATE)
        );
        assertEquals(
                VehicleFinderMath.ACCURACY_POOR,
                VehicleFinderMath.accuracyQuality(91f, VehicleFinderMath.ACCURACY_MODERATE)
        );
        assertEquals(
                VehicleFinderMath.ACCURACY_POOR,
                VehicleFinderMath.accuracyQuality(70f, VehicleFinderMath.ACCURACY_POOR)
        );
        assertEquals(
                VehicleFinderMath.ACCURACY_MODERATE,
                VehicleFinderMath.accuracyQuality(69f, VehicleFinderMath.ACCURACY_POOR)
        );
        assertEquals(
                VehicleFinderMath.ACCURACY_GOOD,
                VehicleFinderMath.accuracyQuality(29f, VehicleFinderMath.ACCURACY_MODERATE)
        );
    }

    @Test
    public void bucketsAccuracyForStableDisplay() {
        assertEquals(40, VehicleFinderMath.bucketAccuracyMeters(
                36f,
                VehicleFinderMath.ACCURACY_MODERATE
        ));
        assertEquals(80, VehicleFinderMath.bucketAccuracyMeters(
                80f,
                VehicleFinderMath.ACCURACY_MODERATE
        ));
        assertEquals(100, VehicleFinderMath.bucketAccuracyMeters(
                81f,
                VehicleFinderMath.ACCURACY_POOR
        ));
        assertEquals(150, VehicleFinderMath.bucketAccuracyMeters(
                120f,
                VehicleFinderMath.ACCURACY_POOR
        ));
    }

    @Test
    public void roundsDistanceAccordingToLocationQuality() {
        assertEquals(5, VehicleFinderMath.approximateDistanceMeters(
                4f,
                VehicleFinderMath.ACCURACY_GOOD
        ));
        assertEquals(60, VehicleFinderMath.approximateDistanceMeters(
                63f,
                VehicleFinderMath.ACCURACY_MODERATE
        ));
        assertEquals(600, VehicleFinderMath.approximateDistanceMeters(
                597f,
                VehicleFinderMath.ACCURACY_POOR
        ));
    }

    @Test
    public void onlyReliableAccuracyCanConfirmNearTarget() {
        assertTrue(VehicleFinderMath.isReliableNearTarget(5f, 8f));
        assertTrue(VehicleFinderMath.isReliableNearTarget(15f, 20f));
        assertFalse(VehicleFinderMath.isReliableNearTarget(5f, 30f));
        assertFalse(VehicleFinderMath.isReliableNearTarget(20f, 8f));
        assertFalse(VehicleFinderMath.isReliableNearTarget(5f, 0f));
    }

    @Test
    public void directionRequiresFreshPreciseAvailableLocationAndHeading() {
        assertTrue(VehicleFinderMath.canShowDirectionArrow(
                VehicleFinderMath.DIRECTION_MAX_AGE_MS,
                VehicleFinderMath.DIRECTION_MAX_ACCURACY_METERS,
                true,
                true
        ));
        assertFalse(VehicleFinderMath.canShowDirectionArrow(
                VehicleFinderMath.DIRECTION_MAX_AGE_MS + 1L,
                20f,
                true,
                true
        ));
        assertFalse(VehicleFinderMath.canShowDirectionArrow(
                1_000L,
                VehicleFinderMath.DIRECTION_MAX_ACCURACY_METERS + 0.1f,
                true,
                true
        ));
        assertFalse(VehicleFinderMath.canShowDirectionArrow(
                1_000L,
                20f,
                false,
                true
        ));
        assertFalse(VehicleFinderMath.canShowDirectionArrow(
                1_000L,
                20f,
                true,
                false
        ));
    }

    @Test
    public void reacquireOnlyCommitsGoodGpsImmediately() {
        assertTrue(VehicleFinderMath.canCommitReacquiredLocation(true, 35f));
        assertFalse(VehicleFinderMath.canCommitReacquiredLocation(true, 35.1f));
        assertFalse(VehicleFinderMath.canCommitReacquiredLocation(false, 10f));
        assertFalse(VehicleFinderMath.canCommitReacquiredLocation(true, 0f));
    }

    @Test
    public void reacquireIgnoresCachedFixesFromBeforeRefresh() {
        assertFalse(VehicleFinderMath.isAfterReacquireStart(
                999_000_000L,
                1_000_000_000L,
                0L,
                0L
        ));
        assertTrue(VehicleFinderMath.isAfterReacquireStart(
                1_001_000_000L,
                1_000_000_000L,
                0L,
                0L
        ));
        assertFalse(VehicleFinderMath.isAfterReacquireStart(
                0L,
                0L,
                999L,
                1_000L
        ));
        assertTrue(VehicleFinderMath.isAfterReacquireStart(
                0L,
                0L,
                1_001L,
                1_000L
        ));
    }

    @Test
    public void classifiesVehicleUploadAge() {
        assertEquals(
                VehicleFinderMath.TARGET_UNKNOWN,
                VehicleFinderMath.targetFreshness(false, 0L)
        );
        assertEquals(
                VehicleFinderMath.TARGET_FRESH,
                VehicleFinderMath.targetFreshness(true, 10L * 60L * 1_000L)
        );
        assertEquals(
                VehicleFinderMath.TARGET_AGING,
                VehicleFinderMath.targetFreshness(true, 10L * 60L * 1_000L + 1L)
        );
        assertEquals(
                VehicleFinderMath.TARGET_STALE,
                VehicleFinderMath.targetFreshness(true, 60L * 60L * 1_000L + 1L)
        );
    }
}
