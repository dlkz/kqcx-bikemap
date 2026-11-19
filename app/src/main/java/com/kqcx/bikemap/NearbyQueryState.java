package com.kqcx.bikemap;

final class NearbyQueryState {
    enum Action {
        IGNORE,
        START,
        QUEUED
    }

    static final class Center {
        final double latitude;
        final double longitude;

        Center(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class Pending {
        final double latitude;
        final double longitude;
        final boolean force;

        Pending(Center center, boolean force) {
            latitude = center.latitude;
            longitude = center.longitude;
            this.force = force;
        }
    }

    static boolean isLookupPendingForSelection(
            String selectedNumber,
            String queryingBikeNumber
    ) {
        return selectedNumber != null && selectedNumber.equals(queryingBikeNumber);
    }

    private static final long MIN_INTERVAL_MS = 10_000L;
    private static final double MIN_DISTANCE_METERS = 80d;

    private boolean inFlight;
    private Center lastRequestedCenter;
    private long lastStartedAtMs;
    private Pending pending;
    private int generation;

    boolean isInFlight() {
        return inFlight;
    }

    Center lastRequestedCenter() {
        return lastRequestedCenter;
    }

    int generation() {
        return generation;
    }

    boolean isCurrent(int requestGeneration) {
        return inFlight && requestGeneration == generation;
    }

    Action submit(
            double latitude,
            double longitude,
            boolean force,
            long nowMs,
            double distanceFromLastRequestMeters
    ) {
        Center candidate = new Center(latitude, longitude);
        if (inFlight) {
            pending = new Pending(
                    candidate,
                    force || pending != null && pending.force
            );
            return Action.QUEUED;
        }
        if (!force
                && lastRequestedCenter != null
                && distanceFromLastRequestMeters < MIN_DISTANCE_METERS
                && nowMs - lastStartedAtMs < MIN_INTERVAL_MS) {
            return Action.IGNORE;
        }
        inFlight = true;
        lastRequestedCenter = candidate;
        lastStartedAtMs = nowMs;
        generation++;
        return Action.START;
    }

    Pending finish(int requestGeneration) {
        if (!isCurrent(requestGeneration)) {
            return null;
        }
        inFlight = false;
        Pending next = pending;
        pending = null;
        return next;
    }
}
