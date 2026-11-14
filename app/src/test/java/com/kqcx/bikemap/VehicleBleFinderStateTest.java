package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VehicleBleFinderStateTest {
    @Test
    public void foundAndLostDrivePrimaryBleDisplay() {
        VehicleBleFinderState state = new VehicleBleFinderState();

        state.start();
        assertTrue(state.isScanning());
        assertFalse(state.isPrimary());

        state.signal(-50, false);
        assertEquals(VehicleBleFinderState.Status.FOUND, state.status());
        assertEquals(VehicleBleMath.BAND_VERY_NEAR, state.band());
        assertTrue(state.hasTargetSignal());
        assertFalse(state.isPrimary());

        state.signal(-50, true);
        assertTrue(state.isPrimary());

        state.weak();
        assertEquals(VehicleBleFinderState.Status.WEAK, state.status());
        assertFalse(state.hasTargetSignal());
        assertTrue(state.isPrimary());

        state.lost();
        assertEquals(VehicleBleFinderState.Status.LOST, state.status());
        assertFalse(state.hasTargetSignal());
        assertTrue(state.isPrimary());
    }

    @Test
    public void stopAfterDiscoveryStopsPrimaryDisplay() {
        VehicleBleFinderState state = new VehicleBleFinderState();
        state.start();
        state.signal(-60, true);

        state.stop();

        assertEquals(VehicleBleFinderState.Status.STOPPED, state.status());
        assertFalse(state.isScanning());
        assertFalse(state.isPrimary());
        assertEquals(VehicleBleMath.BAND_NONE, state.band());
    }

    @Test
    public void failureAndNoDiscoveryTimeoutDoNotBecomePrimary() {
        VehicleBleFinderState failed = new VehicleBleFinderState();
        failed.fail();
        assertEquals(VehicleBleFinderState.Status.FAILED, failed.status());
        assertFalse(failed.isPrimary());

        VehicleBleFinderState timedOut = new VehicleBleFinderState();
        timedOut.start();
        timedOut.timeout();
        assertEquals(VehicleBleFinderState.Status.TIMED_OUT, timedOut.status());
        assertFalse(timedOut.isPrimary());
    }

    @Test
    public void stopBeforeDiscoveryReturnsToReady() {
        VehicleBleFinderState state = new VehicleBleFinderState();
        state.start();

        state.stop();

        assertEquals(VehicleBleFinderState.Status.READY, state.status());
        assertFalse(state.isScanning());
    }

    @Test
    public void farSignalNeverBecomesPrimaryBleDistance() {
        VehicleBleFinderState state = new VehicleBleFinderState();
        state.start();
        state.signal(-80, true);

        assertEquals(VehicleBleFinderState.Status.FOUND, state.status());
        assertEquals(VehicleBleMath.BAND_FAR, state.band());
        assertFalse(state.isPrimary());
    }
}
