package com.kqcx.bikemap;

final class MapRecenterState {
    enum LocationAction {
        NONE,
        FIRST_FIX,
        PENDING_RECENTER,
        FOLLOW
    }

    private static final long FOLLOW_WINDOW_MS = 6000L;
    private static final long FRESH_FIX_TIMEOUT_MS = 60_000L;
    private static final long FALLBACK_FRESH_FIX_DELAY_MS = 2000L;

    private boolean initialLocationCentered;
    private boolean recenterInProgress;
    private boolean pendingRecenterOnFix;
    private boolean settlePending;
    private boolean userInterrupted;
    private boolean vehicleFocusActive;
    private boolean suppressCenterIndicator;
    private long followUntilElapsedMs;
    private long awaitFixSinceElapsedMs;
    private double recenterZoom;
    private int generation;

    void requestPendingRecenter() {
        pendingRecenterOnFix = true;
    }

    void beginRecenter(
            double targetZoom,
            boolean startFollowWindow,
            long nowElapsedMs
    ) {
        pendingRecenterOnFix = false;
        vehicleFocusActive = false;
        if (startFollowWindow) {
            followUntilElapsedMs = nowElapsedMs + FOLLOW_WINDOW_MS;
            awaitFixSinceElapsedMs = nowElapsedMs;
        }
        recenterZoom = targetZoom;
        recenterInProgress = true;
        suppressCenterIndicator = false;
        settlePending = true;
        userInterrupted = false;
        generation++;
    }

    void onUserGestureStart(long nowElapsedMs) {
        suppressCenterIndicator = false;
        if (settlePending
                || awaitFixSinceElapsedMs != 0L
                || nowElapsedMs < followUntilElapsedMs) {
            userInterrupted = true;
        }
        awaitFixSinceElapsedMs = 0L;
    }

    void enterVehicleFocus() {
        vehicleFocusActive = true;
        settlePending = false;
        recenterInProgress = false;
        followUntilElapsedMs = 0L;
        awaitFixSinceElapsedMs = 0L;
        suppressCenterIndicator = true;
    }

    boolean isVehicleFocusActive() {
        return vehicleFocusActive;
    }

    boolean shouldHideCenterIndicator(boolean hasLocation) {
        return !hasLocation || recenterInProgress || suppressCenterIndicator;
    }

    int generation() {
        return generation;
    }

    double recenterZoom() {
        return recenterZoom;
    }

    boolean takeSettle(int expectedGeneration) {
        if (expectedGeneration != generation || !settlePending) {
            return false;
        }
        settlePending = false;
        return true;
    }

    boolean shouldCorrectSettlePosition() {
        return !userInterrupted;
    }

    void finishRecenter() {
        recenterInProgress = false;
    }

    boolean resolveFreshFix(long fixElapsedMs, long nowElapsedMs) {
        if (awaitFixSinceElapsedMs == 0L) {
            return false;
        }
        boolean updated = fixElapsedMs > 0L
                ? fixElapsedMs >= awaitFixSinceElapsedMs
                : nowElapsedMs - awaitFixSinceElapsedMs
                        >= FALLBACK_FRESH_FIX_DELAY_MS;
        if (updated) {
            awaitFixSinceElapsedMs = 0L;
            return true;
        }
        if (nowElapsedMs - awaitFixSinceElapsedMs > FRESH_FIX_TIMEOUT_MS) {
            awaitFixSinceElapsedMs = 0L;
        }
        return false;
    }

    LocationAction onAcceptedLocation(
            boolean freshFixForRecenter,
            boolean distanceBeyondThreshold,
            long nowElapsedMs
    ) {
        if (!initialLocationCentered || pendingRecenterOnFix) {
            boolean firstFix = !initialLocationCentered && !pendingRecenterOnFix;
            initialLocationCentered = true;
            return firstFix
                    ? LocationAction.FIRST_FIX
                    : LocationAction.PENDING_RECENTER;
        }
        if (!vehicleFocusActive
                && !userInterrupted
                && (freshFixForRecenter
                        || awaitFixSinceElapsedMs != 0L
                        || nowElapsedMs < followUntilElapsedMs)
                && distanceBeyondThreshold) {
            return LocationAction.FOLLOW;
        }
        return LocationAction.NONE;
    }

    void onResume(
            boolean hasLocation,
            boolean nearCurrentLocation,
            long nowElapsedMs
    ) {
        if (hasLocation && !vehicleFocusActive && nearCurrentLocation) {
            awaitFixSinceElapsedMs = nowElapsedMs;
        }
    }
}
