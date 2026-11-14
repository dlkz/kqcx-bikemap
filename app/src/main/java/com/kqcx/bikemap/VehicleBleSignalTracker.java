package com.kqcx.bikemap;

final class VehicleBleSignalTracker {
    static final int EVENT_NONE = 0;
    static final int EVENT_TARGET_VISIBLE = 1;
    static final int EVENT_BAND_STABLE = 2;
    static final long TARGET_WEAK_MS = 3_000L;
    static final long TARGET_LOST_MS = 8_000L;

    private static final int RSSI_SAMPLE_COUNT = 5;
    private static final int STABLE_SAMPLE_COUNT = 3;

    private final int[] rssiSamples = new int[RSSI_SAMPLE_COUNT];

    private boolean seen;
    private int sampleCount;
    private int sampleIndex;
    private int lastRssi = Integer.MIN_VALUE;
    private int lastBand = VehicleBleMath.BAND_NONE;
    private int stableSampleCount;
    private boolean bandStable;
    private long lastSeenElapsedMs;

    void reset() {
        seen = false;
        sampleCount = 0;
        sampleIndex = 0;
        lastRssi = Integer.MIN_VALUE;
        lastBand = VehicleBleMath.BAND_NONE;
        stableSampleCount = 0;
        bandStable = false;
        lastSeenElapsedMs = 0L;
    }

    int accept(int rssi, long nowElapsedMs) {
        boolean wasSeen = seen;
        rssiSamples[sampleIndex] = rssi;
        sampleIndex = (sampleIndex + 1) % RSSI_SAMPLE_COUNT;
        sampleCount = Math.min(RSSI_SAMPLE_COUNT, sampleCount + 1);
        int smoothed = VehicleBleMath.median(rssiSamples, sampleCount);
        int band = VehicleBleMath.proximityBand(smoothed);
        boolean bandChanged = !wasSeen || band != lastBand;
        int previousStableSampleCount = stableSampleCount;
        stableSampleCount = bandChanged
                ? 1
                : Math.min(STABLE_SAMPLE_COUNT, stableSampleCount + 1);
        boolean becameStable = !bandChanged
                && previousStableSampleCount < STABLE_SAMPLE_COUNT
                && stableSampleCount == STABLE_SAMPLE_COUNT;
        bandStable = stableSampleCount >= STABLE_SAMPLE_COUNT;
        lastRssi = smoothed;
        lastBand = band;
        lastSeenElapsedMs = nowElapsedMs;
        seen = true;
        if (!wasSeen || bandChanged) {
            return EVENT_TARGET_VISIBLE;
        }
        return becameStable ? EVENT_BAND_STABLE : EVENT_NONE;
    }

    boolean isSeen() {
        return seen;
    }

    int lastRssi() {
        return lastRssi;
    }

    boolean isBandStable() {
        return bandStable;
    }

    boolean shouldReportWeak(long nowElapsedMs) {
        long silentMs = nowElapsedMs - lastSeenElapsedMs;
        return seen
                && silentMs >= TARGET_WEAK_MS
                && silentMs < TARGET_LOST_MS;
    }

    boolean shouldReportLost(long nowElapsedMs) {
        return seen && nowElapsedMs - lastSeenElapsedMs >= TARGET_LOST_MS;
    }

    void markWeak() {
        if (!seen) {
            return;
        }
        sampleCount = 0;
        sampleIndex = 0;
        lastRssi = Integer.MIN_VALUE;
        lastBand = VehicleBleMath.BAND_NONE;
        stableSampleCount = 0;
        bandStable = false;
    }

    void markLost() {
        reset();
    }
}
