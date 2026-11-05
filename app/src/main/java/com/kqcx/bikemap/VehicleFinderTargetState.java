package com.kqcx.bikemap;

final class VehicleFinderTargetState {
    enum Source {
        ESTIMATED,
        REFRESHED
    }

    private final String number;
    private double latitude;
    private double longitude;
    private long lastUploadAtMs;
    private Source source;

    private VehicleFinderTargetState(
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs,
            Source source
    ) {
        this.number = number;
        this.latitude = latitude;
        this.longitude = longitude;
        this.lastUploadAtMs = lastUploadAtMs;
        this.source = source;
    }

    static VehicleFinderTargetState createEstimated(
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs
    ) {
        if (number == null
                || number.trim().isEmpty()
                || !isValidCoordinate(latitude, longitude)) {
            return null;
        }
        return new VehicleFinderTargetState(
                number,
                latitude,
                longitude,
                lastUploadAtMs,
                Source.ESTIMATED
        );
    }

    static VehicleFinderTargetState restore(
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs,
            Source source
    ) {
        if (source == null) {
            return null;
        }
        VehicleFinderTargetState restored = createEstimated(
                number,
                latitude,
                longitude,
                lastUploadAtMs
        );
        if (restored != null) {
            restored.source = source;
        }
        return restored;
    }

    boolean applyRefresh(Bike bike) {
        if (bike == null
                || !number.equals(bike.number)
                || !bike.locationKnown
                || !isValidCoordinate(bike.latitude, bike.longitude)
                || isOlderUpload(bike.lastUploadAtMs)) {
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

    boolean needsRefresh(long nowMs, long maxAgeMs) {
        if (lastUploadAtMs <= 0L) {
            return true;
        }
        long ageMs = nowMs - lastUploadAtMs;
        return ageMs > maxAgeMs;
    }

    private static boolean isValidCoordinate(double latitude, double longitude) {
        return !Double.isNaN(latitude)
                && !Double.isNaN(longitude)
                && latitude >= -90d
                && latitude <= 90d
                && longitude >= -180d
                && longitude <= 180d
                && (latitude != 0d || longitude != 0d);
    }

    private boolean isOlderUpload(long candidateLastUploadAtMs) {
        return lastUploadAtMs > 0L
                && candidateLastUploadAtMs > 0L
                && candidateLastUploadAtMs < lastUploadAtMs;
    }
}
