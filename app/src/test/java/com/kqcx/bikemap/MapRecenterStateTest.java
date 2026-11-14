package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MapRecenterStateTest {
    @Test
    public void firstFixStartsRecenterAndUserGestureStopsCorrection() {
        MapRecenterState state = new MapRecenterState();

        assertEquals(
                MapRecenterState.LocationAction.FIRST_FIX,
                state.onAcceptedLocation(false, false, 1_000L)
        );
        state.beginRecenter(16.5, true, 1_000L);
        assertTrue(state.shouldHideCenterIndicator(true));
        assertEquals(1, state.generation());

        state.onUserGestureStart(1_500L);
        assertTrue(state.takeSettle(1));
        assertFalse(state.shouldCorrectSettlePosition());
        state.finishRecenter();
        assertFalse(state.shouldHideCenterIndicator(true));
    }

    @Test
    public void pendingFixIsConsumedByRecenter() {
        MapRecenterState state = new MapRecenterState();
        state.requestPendingRecenter();

        assertEquals(
                MapRecenterState.LocationAction.PENDING_RECENTER,
                state.onAcceptedLocation(false, false, 2_000L)
        );
        state.beginRecenter(17d, true, 2_000L);
        state.onUserGestureStart(2_001L);
        assertEquals(
                MapRecenterState.LocationAction.NONE,
                state.onAcceptedLocation(false, true, 2_100L)
        );
    }

    @Test
    public void followWindowAndFreshFixRequestFollowUntilUserInterrupts() {
        MapRecenterState state = new MapRecenterState();
        state.onAcceptedLocation(false, false, 1_000L);
        state.beginRecenter(17d, true, 1_000L);
        assertTrue(state.takeSettle(1));
        state.finishRecenter();

        assertEquals(
                MapRecenterState.LocationAction.FOLLOW,
                state.onAcceptedLocation(false, true, 2_000L)
        );
        state.onUserGestureStart(2_500L);
        assertEquals(
                MapRecenterState.LocationAction.NONE,
                state.onAcceptedLocation(true, true, 2_600L)
        );
    }

    @Test
    public void freshFixFallbackAndTimeoutAreBounded() {
        MapRecenterState state = new MapRecenterState();
        state.onAcceptedLocation(false, false, 1_000L);
        state.beginRecenter(17d, true, 1_000L);

        assertFalse(state.resolveFreshFix(0L, 2_999L));
        assertTrue(state.resolveFreshFix(0L, 3_000L));
        assertFalse(state.resolveFreshFix(0L, 4_000L));

        state.beginRecenter(17d, true, 10_000L);
        assertFalse(state.resolveFreshFix(9_500L, 11_000L));
        assertFalse(state.resolveFreshFix(9_000L, 70_001L));
    }

    @Test
    public void vehicleFocusAndResumeNearLocationSuppressAutomaticFollow() {
        MapRecenterState state = new MapRecenterState();
        state.onAcceptedLocation(false, false, 1_000L);
        state.beginRecenter(17d, true, 1_000L);
        state.enterVehicleFocus();

        assertTrue(state.isVehicleFocusActive());
        assertTrue(state.shouldHideCenterIndicator(true));
        assertFalse(state.takeSettle(1));
        state.onResume(true, true, 2_000L);
        assertEquals(
                MapRecenterState.LocationAction.NONE,
                state.onAcceptedLocation(true, true, 2_100L)
        );
    }
}
