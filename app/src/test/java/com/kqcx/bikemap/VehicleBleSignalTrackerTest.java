package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VehicleBleSignalTrackerTest {
    @Test
    public void reportsFirstSeenAndWhenBandBecomesStable() {
        VehicleBleSignalTracker tracker = new VehicleBleSignalTracker();

        assertEquals(
                VehicleBleSignalTracker.EVENT_TARGET_VISIBLE,
                tracker.accept(-70, 1_000L)
        );
        assertEquals(
                VehicleBleSignalTracker.EVENT_NONE,
                tracker.accept(-70, 1_100L)
        );
        assertEquals(
                VehicleBleSignalTracker.EVENT_BAND_STABLE,
                tracker.accept(-70, 1_200L)
        );
        assertTrue(tracker.isBandStable());
        assertEquals(
                VehicleBleSignalTracker.EVENT_NONE,
                tracker.accept(-70, 1_300L)
        );
        assertEquals(-70, tracker.lastRssi());
    }

    @Test
    public void reportsWeakAndLossAfterGracePeriods() {
        VehicleBleSignalTracker tracker = new VehicleBleSignalTracker();
        tracker.accept(-60, 10_000L);

        assertFalse(tracker.shouldReportWeak(12_999L));
        assertTrue(tracker.shouldReportWeak(13_000L));
        assertTrue(tracker.shouldReportWeak(17_999L));
        assertFalse(tracker.shouldReportWeak(18_000L));
        assertFalse(tracker.shouldReportLost(17_999L));
        assertTrue(tracker.shouldReportLost(18_000L));

        tracker.markLost();
        assertFalse(tracker.isSeen());
        assertEquals(Integer.MIN_VALUE, tracker.lastRssi());
        assertEquals(
                VehicleBleSignalTracker.EVENT_TARGET_VISIBLE,
                tracker.accept(-60, 18_100L)
        );
        assertTrue(tracker.isSeen());
    }

    @Test
    public void lostSignalClearsOldRssiSamplesBeforeReappearance() {
        VehicleBleSignalTracker tracker = new VehicleBleSignalTracker();
        tracker.accept(-50, 1_000L);
        tracker.accept(-50, 1_100L);
        tracker.accept(-50, 1_200L);
        assertTrue(tracker.isBandStable());

        tracker.markLost();

        assertEquals(
                VehicleBleSignalTracker.EVENT_TARGET_VISIBLE,
                tracker.accept(-80, 10_000L)
        );
        assertEquals(-80, tracker.lastRssi());
        assertFalse(tracker.isBandStable());
    }

    @Test
    public void weakSignalRequiresThreeFreshSamplesToBecomeStableAgain() {
        VehicleBleSignalTracker tracker = new VehicleBleSignalTracker();
        tracker.accept(-50, 1_000L);
        tracker.accept(-50, 1_100L);
        tracker.accept(-50, 1_200L);
        assertTrue(tracker.isBandStable());

        tracker.markWeak();

        assertTrue(tracker.isSeen());
        assertFalse(tracker.isBandStable());
        assertEquals(Integer.MIN_VALUE, tracker.lastRssi());
        assertEquals(
                VehicleBleSignalTracker.EVENT_TARGET_VISIBLE,
                tracker.accept(-50, 4_000L)
        );
        assertFalse(tracker.isBandStable());
        assertEquals(
                VehicleBleSignalTracker.EVENT_NONE,
                tracker.accept(-50, 4_100L)
        );
        assertEquals(
                VehicleBleSignalTracker.EVENT_BAND_STABLE,
                tracker.accept(-50, 4_200L)
        );
        assertTrue(tracker.isBandStable());
    }

    @Test
    public void resetClearsSeenAndSignalState() {
        VehicleBleSignalTracker tracker = new VehicleBleSignalTracker();
        tracker.accept(-50, 1_000L);
        tracker.reset();

        assertFalse(tracker.isSeen());
        assertFalse(tracker.isBandStable());
        assertEquals(Integer.MIN_VALUE, tracker.lastRssi());
        assertFalse(tracker.shouldReportWeak(10_000L));
        assertFalse(tracker.shouldReportLost(10_000L));
    }
}
