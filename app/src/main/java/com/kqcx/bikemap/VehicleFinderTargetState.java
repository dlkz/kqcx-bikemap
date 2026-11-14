package com.kqcx.bikemap;

final class VehicleFinderTargetState {
    enum Source {
        ESTIMATED,
        REFRESHED,
        SNAPSHOT
    }

    private final String number;
    private double latitude;
    private double longitude;
    private long lastUploadAtMs;
    private Source source;
    private String bluetoothName;

    private VehicleFinderTargetState(
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs,
            Source source,
            String bluetoothName
    ) {
        this.number = number;
        this.latitude = latitude;
        this.longitude = longitude;
        this.lastUploadAtMs = lastUploadAtMs;
        this.source = source;
        this.bluetoothName = bluetoothName == null ? "" : bluetoothName;
    }

    static VehicleFinderTargetState createEstimated(
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs
    ) {
        return createEstimated(number, latitude, longitude, lastUploadAtMs, "");
    }

    static VehicleFinderTargetState createEstimated(
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs,
            String bluetoothName
    ) {
        if (number == null
                || number.trim().isEmpty()
                || !CoordinateUtils.isValidLatLng(latitude, longitude)) {
            return null;
        }
        return new VehicleFinderTargetState(
                number,
                latitude,
                longitude,
                lastUploadAtMs,
                Source.ESTIMATED,
                bluetoothName
        );
    }

    static VehicleFinderTargetState restore(
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs,
            Source source,
            String bluetoothName
    ) {
        if (source == null) {
            return null;
        }
        VehicleFinderTargetState restored = createEstimated(
                number,
                latitude,
                longitude,
                lastUploadAtMs,
                bluetoothName
        );
        if (restored != null) {
            restored.source = source;
        }
        return restored;
    }

    boolean applyRefresh(Bike bike) {
        if (bike == null || !number.equals(bike.number)) {
            return false;
        }
        if (!bike.bluetoothName.isEmpty()) {
            bluetoothName = bike.bluetoothName;
        }
        if (!bike.locationKnown
                || !CoordinateUtils.isValidLatLng(bike.latitude, bike.longitude)
                || !VehicleLocationFreshness.canReplace(
                        lastUploadAtMs,
                        true,
                        bike.lastUploadAtMs
                )) {
            return false;
        }
        latitude = bike.latitude;
        longitude = bike.longitude;
        lastUploadAtMs = bike.lastUploadAtMs;
        source = Source.REFRESHED;
        return true;
    }

    String number() {
        return number;
    }

    double latitude() {
        return latitude;
    }

    double longitude() {
        return longitude;
    }

    long lastUploadAtMs() {
        return lastUploadAtMs;
    }

    Source source() {
        return source;
    }

    String bluetoothName() {
        return bluetoothName;
    }

    boolean needsRefresh(long nowMs, long maxAgeMs) {
        if (lastUploadAtMs <= 0L) {
            return true;
        }
        long ageMs = nowMs - lastUploadAtMs;
        return ageMs > maxAgeMs;
    }

}
