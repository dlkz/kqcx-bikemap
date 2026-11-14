package com.kqcx.bikemap;

final class VehicleBleFinderState {
    enum Status {
        READY,
        SCANNING,
        FOUND,
        WEAK,
        LOST,
        TIMED_OUT,
        FAILED,
        STOPPED
    }

    private Status status = Status.READY;
    private int band = VehicleBleMath.BAND_NONE;
    private boolean bandStable;

    Status status() {
        return status;
    }

    int band() {
        return band;
    }

    boolean isScanning() {
        return status == Status.SCANNING
                || status == Status.FOUND
                || status == Status.WEAK
                || status == Status.LOST;
    }

    boolean isPrimary() {
        boolean usableStatus = status == Status.FOUND
                || status == Status.WEAK
                || status == Status.LOST;
        boolean nearBand = band == VehicleBleMath.BAND_NEAR
                || band == VehicleBleMath.BAND_VERY_NEAR;
        return usableStatus && bandStable && nearBand;
    }

    boolean hasTargetSignal() {
        return status == Status.FOUND;
    }

    boolean hasFoundTarget() {
        return status == Status.FOUND
                || status == Status.WEAK
                || status == Status.LOST
                || status == Status.STOPPED;
    }

    void start() {
        status = Status.SCANNING;
        band = VehicleBleMath.BAND_NONE;
        bandStable = false;
    }

    void signal(int rssi, boolean bandStable) {
        if (!isScanning()) {
            return;
        }
        status = Status.FOUND;
        band = VehicleBleMath.proximityBand(rssi);
        this.bandStable = bandStable;
    }

    void weak() {
        if (status == Status.FOUND) {
            status = Status.WEAK;
        }
    }

    void lost() {
        if (status == Status.FOUND || status == Status.WEAK) {
            status = Status.LOST;
        }
    }

    void fail() {
        status = Status.FAILED;
        band = VehicleBleMath.BAND_NONE;
        bandStable = false;
    }

    void timeout() {
        if (hasFoundTarget()) {
            stop();
        } else {
            status = Status.TIMED_OUT;
            band = VehicleBleMath.BAND_NONE;
            bandStable = false;
        }
    }

    void stop() {
        status = hasFoundTarget() ? Status.STOPPED : Status.READY;
        band = VehicleBleMath.BAND_NONE;
        bandStable = false;
    }
}
