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
                1.0,
                1.0,
                1_000L
        );

        assertNotNull(target);
        assertEquals(VehicleFinderTargetState.Source.ESTIMATED, target.source());
    }

    @Test
    public void snapshotKeepsSavedSourceAndBluetoothIdentity() {
        VehicleFinderTargetState target = VehicleFinderTargetState.restore(
                "123456789",
                1.0,
                1.0,
                1_000L,
                VehicleFinderTargetState.Source.SNAPSHOT,
                "xlc-000000001"
        );

        assertNotNull(target);
        assertEquals(VehicleFinderTargetState.Source.SNAPSHOT, target.source());
        assertEquals("xlc-000000001", target.bluetoothName());
    }

    @Test
    public void successfulRefreshUpdatesPositionAndSource() {
        VehicleFinderTargetState target = VehicleFinderTargetState.createEstimated(
                "123456789",
                1.0,
                1.0,
                1_000L
        );
        Bike refreshed = bike("123456789", 1.01, 1.01, 2_000L);

        assertTrue(target.applyRefresh(refreshed));
        assertEquals(VehicleFinderTargetState.Source.REFRESHED, target.source());
        assertEquals(1.01, target.latitude(), 0.000001);
        assertEquals(1.01, target.longitude(), 0.000001);
        assertEquals(2_000L, target.lastUploadAtMs());
    }

    @Test
    public void mismatchedRefreshLeavesEstimatedStateUntouched() {
        VehicleFinderTargetState target = VehicleFinderTargetState.createEstimated(
                "123456789",
                1.0,
                1.0,
                1_000L
        );

        assertFalse(target.applyRefresh(bike("987654321", 1.01, 1.01, 2_000L)));
        assertEquals(VehicleFinderTargetState.Source.ESTIMATED, target.source());
        assertEquals(1.0, target.latitude(), 0.000001);
        assertEquals(1_000L, target.lastUploadAtMs());
    }

    @Test
    public void olderRefreshDoesNotOverrideNewerTarget() {
        VehicleFinderTargetState target = VehicleFinderTargetState.createEstimated(
                "123456789",
                1.0,
                1.0,
                2_000L
        );

        assertFalse(target.applyRefresh(bike("123456789", 1.01, 1.01, 1_000L)));
        assertEquals(VehicleFinderTargetState.Source.ESTIMATED, target.source());
        assertEquals(1.0, target.latitude(), 0.000001);
        assertEquals(2_000L, target.lastUploadAtMs());
    }

    @Test
    public void unknownRefreshTimeCannotReplaceKnownLocation() {
        VehicleFinderTargetState target = VehicleFinderTargetState.createEstimated(
                "123456789",
                1.0,
                1.0,
                2_000L
        );

        assertFalse(target.applyRefresh(bikeWithBluetooth(
                "123456789",
                1.01,
                1.01,
                0L,
                "xlc-000000001"
        )));
        assertEquals(1.0, target.latitude(), 0.000001);
        assertEquals(1.0, target.longitude(), 0.000001);
        assertEquals(2_000L, target.lastUploadAtMs());
        assertEquals(VehicleFinderTargetState.Source.ESTIMATED, target.source());
        assertEquals("xlc-000000001", target.bluetoothName());
    }

    @Test
    public void restoredSourceAndPositionArePreserved() {
        VehicleFinderTargetState restored = VehicleFinderTargetState.restore(
                "123456789",
                1.01,
                1.01,
                2_000L,
                VehicleFinderTargetState.Source.REFRESHED,
                "xlc-000000001"
        );

        assertNotNull(restored);
        assertEquals(VehicleFinderTargetState.Source.REFRESHED, restored.source());
        assertEquals(1.01, restored.latitude(), 0.000001);
        assertEquals(1.01, restored.longitude(), 0.000001);
        assertEquals(2_000L, restored.lastUploadAtMs());
        assertEquals("xlc-000000001", restored.bluetoothName());
    }

    @Test
    public void refreshAddsBluetoothIdentityWithoutReplacingExistingValueWithEmpty() {
        VehicleFinderTargetState target = VehicleFinderTargetState.createEstimated(
                "123456789",
                1.0,
                1.0,
                1_000L
        );

        assertTrue(target.applyRefresh(bikeWithBluetooth(
                "123456789",
                1.01,
                1.01,
                2_000L,
                "xlc-000000001"
        )));
        assertEquals("xlc-000000001", target.bluetoothName());

        assertTrue(target.applyRefresh(bikeWithBluetooth(
                "123456789",
                1.02,
                1.02,
                3_000L,
                ""
        )));
        assertEquals("xlc-000000001", target.bluetoothName());
    }

    @Test
    public void refreshIsNeededWhenUploadIsUnknownOrTooOld() {
        VehicleFinderTargetState unknown = VehicleFinderTargetState.createEstimated(
                "123456789",
                1.0,
                1.0,
                0L
        );
        VehicleFinderTargetState old = VehicleFinderTargetState.createEstimated(
                "123456789",
                1.0,
                1.0,
                1_000L
        );
        VehicleFinderTargetState fresh = VehicleFinderTargetState.createEstimated(
                "123456789",
                1.0,
                1.0,
                118_000L
        );

        assertTrue(unknown.needsRefresh(120_000L, 120_000L));
        assertTrue(old.needsRefresh(121_001L, 120_000L));
        assertFalse(fresh.needsRefresh(120_000L, 120_000L));
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
        return bikeWithBluetooth(number, latitude, longitude, lastUploadAtMs, "");
    }

    private static Bike bikeWithBluetooth(
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs,
            String bluetoothName
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
                lastUploadAtMs,
                bluetoothName
        );
    }
}
