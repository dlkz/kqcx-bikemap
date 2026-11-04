package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VehicleFinderTargetStateTest {
    @Test
    public void startsAsEstimated() {
        VehicleFinderTargetState target = VehicleFinderTargetState.createEstimated(
                "123456789",
                28.68,
                115.86,
                1_000L
        );

        assertNotNull(target);
        assertEquals(VehicleFinderTargetState.Source.ESTIMATED, target.source());
    }

    @Test
    public void successfulRefreshUpdatesPositionAndSource() {
        VehicleFinderTargetState target = VehicleFinderTargetState.createEstimated(
                "123456789",
                28.68,
                115.86,
                1_000L
        );
        Bike refreshed = bike("123456789", 28.69, 115.87, 2_000L);

        assertTrue(target.applyRefresh(refreshed));
        assertEquals(VehicleFinderTargetState.Source.REFRESHED, target.source());
        assertEquals(28.69, target.latitude(), 0.000001);
        assertEquals(115.87, target.longitude(), 0.000001);
        assertEquals(2_000L, target.lastUploadAtMs());
    }

    @Test
    public void mismatchedRefreshLeavesEstimatedStateUntouched() {
        VehicleFinderTargetState target = VehicleFinderTargetState.createEstimated(
                "123456789",
                28.68,
                115.86,
                1_000L
        );

        assertFalse(target.applyRefresh(bike("987654321", 28.69, 115.87, 2_000L)));
        assertEquals(VehicleFinderTargetState.Source.ESTIMATED, target.source());
        assertEquals(28.68, target.latitude(), 0.000001);
        assertEquals(1_000L, target.lastUploadAtMs());
    }

    @Test
    public void olderRefreshDoesNotOverrideNewerTarget() {
        VehicleFinderTargetState target = VehicleFinderTargetState.createEstimated(
                "123456789",
                28.68,
                115.86,
                2_000L
        );

        assertFalse(target.applyRefresh(bike("123456789", 28.69, 115.87, 1_000L)));
        assertEquals(VehicleFinderTargetState.Source.ESTIMATED, target.source());
        assertEquals(28.68, target.latitude(), 0.000001);
        assertEquals(2_000L, target.lastUploadAtMs());
    }

    @Test
    public void invalidTargetIsRejected() {
        assertNull(VehicleFinderTargetState.createEstimated(
                "123456789",
                0d,
                0d,
                1_000L
        ));
    }

    private static Bike bike(
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs
    ) {
        return new Bike(
                number,
                latitude,
                longitude,
                80d,
                R.string.bike_status_available,
                "",
                "江西师范大学",
                "",
                0f,
                true,
                true,
                lastUploadAtMs
        );
    }
}
