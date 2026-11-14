package com.kqcx.bikemap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LocationAcceptancePolicyTest {
    @Test
    public void comparesElapsedTimeAndFallsBackToWallTime() {
        assertTrue(LocationAcceptancePolicy.isOlder(
                1_000_000_000L,
                2_000_000_000L,
                0L,
                0L,
                100L
        ));
        assertFalse(LocationAcceptancePolicy.isOlder(
                0L,
                0L,
                2_000L,
                1_000L,
                100L
        ));
        assertTrue(LocationAcceptancePolicy.isOlder(
                0L,
                0L,
                1_000L,
                2_000L,
                100L
        ));
    }

    @Test
    public void detectsSignificantlyNewerFixWithoutMixingClocks() {
        assertTrue(LocationAcceptancePolicy.isSignificantlyNewer(
                12_000_000_000L,
                1_000_000_000L,
                0L,
                0L,
                10_000L
        ));
        assertFalse(LocationAcceptancePolicy.isSignificantlyNewer(
                0L,
                0L,
                11_000L,
                1_000L,
                10_000L
        ));
        assertFalse(LocationAcceptancePolicy.isSignificantlyNewer(
                12_000_000_000L,
                0L,
                0L,
                1_000L,
                10_000L
        ));
    }

    @Test
    public void networkFixCannotReplaceFreshGps() {
        assertFalse(LocationAcceptancePolicy.shouldAccept(
                true,
                true,
                true,
                true,
                5f,
                true,
                20f,
                true
        ));
        assertTrue(LocationAcceptancePolicy.shouldAccept(
                true,
                true,
                false,
                true,
                5f,
                true,
                20f,
                true
        ));
    }

    @Test
    public void materiallyWorseAccuracyNeedsAReasonToReplaceGoodFix() {
        assertFalse(LocationAcceptancePolicy.shouldAccept(
                false,
                true,
                false,
                true,
                30f,
                true,
                5f,
                false
        ));
        assertTrue(LocationAcceptancePolicy.shouldAccept(
                false,
                true,
                false,
                true,
                30f,
                true,
                5f,
                true
        ));
        assertFalse(LocationAcceptancePolicy.shouldAccept(
                false,
                true,
                false,
                true,
                97f,
                true,
                5f,
                true
        ));
    }

    @Test
    public void unknownAccuracyCannotReplaceKnownGoodAccuracy() {
        assertFalse(LocationAcceptancePolicy.shouldAccept(
                false,
                true,
                false,
                false,
                0f,
                true,
                10f,
                true
        ));
        assertTrue(LocationAcceptancePolicy.shouldAccept(
                false,
                true,
                false,
                true,
                10f,
                false,
                0f,
                false
        ));
    }
}
