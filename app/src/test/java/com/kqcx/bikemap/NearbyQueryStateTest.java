package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NearbyQueryStateTest {
    @Test
    public void whileInFlightKeepsLatestCenterAndForcesQueuedRequest() {
        NearbyQueryState state = new NearbyQueryState();
        assertEquals(
                NearbyQueryState.Action.START,
                state.submit(30d, 120d, false, 1_000L, Double.POSITIVE_INFINITY)
        );
        assertTrue(state.isInFlight());
        assertTrue(state.isCurrent(state.generation()));

        assertEquals(
                NearbyQueryState.Action.QUEUED,
                state.submit(30.1d, 120.1d, false, 1_100L, 10d)
        );
        assertEquals(
                NearbyQueryState.Action.QUEUED,
                state.submit(30.2d, 120.2d, true, 1_200L, 10d)
        );

        int requestGeneration = state.generation();
        NearbyQueryState.Pending pending = state.finish(requestGeneration);
        assertFalse(state.isInFlight());
        assertNotNull(pending);
        assertEquals(30.2d, pending.latitude, 0d);
        assertEquals(120.2d, pending.longitude, 0d);
        assertTrue(pending.force);
        assertNull(state.finish(requestGeneration));
    }

    @Test
    public void nearbyRequestsInsideIntervalAreIgnoredUntilThreshold() {
        NearbyQueryState state = new NearbyQueryState();
        state.submit(30d, 120d, false, 1_000L, Double.POSITIVE_INFINITY);
        int firstGeneration = state.generation();
        state.finish(firstGeneration);

        assertEquals(
                NearbyQueryState.Action.IGNORE,
                state.submit(30.0001d, 120.0001d, false, 5_000L, 79.9d)
        );
        assertEquals(
                NearbyQueryState.Action.START,
                state.submit(30.0001d, 120.0001d, false, 11_000L, 79.9d)
        );
        state.finish(state.generation());

        assertEquals(
                NearbyQueryState.Action.START,
                state.submit(30.001d, 120.001d, false, 12_000L, 80d)
        );
    }

    @Test
    public void forceBypassesIntervalAndDistanceThreshold() {
        NearbyQueryState state = new NearbyQueryState();
        state.submit(30d, 120d, false, 1_000L, Double.POSITIVE_INFINITY);
        state.finish(state.generation());

        assertEquals(
                NearbyQueryState.Action.START,
                state.submit(30d, 120d, true, 1_001L, 0d)
        );
    }

    @Test
    public void staleGenerationCannotFinishOrApplyANewerRequest() {
        NearbyQueryState state = new NearbyQueryState();
        state.submit(30d, 120d, false, 1_000L, Double.POSITIVE_INFINITY);
        int firstGeneration = state.generation();
        state.finish(firstGeneration);

        state.submit(31d, 121d, true, 2_000L, 100d);
        int secondGeneration = state.generation();
        assertFalse(state.isCurrent(firstGeneration));
        assertTrue(state.isCurrent(secondGeneration));
        assertNull(state.finish(firstGeneration));
        assertTrue(state.isInFlight());
    }
}
