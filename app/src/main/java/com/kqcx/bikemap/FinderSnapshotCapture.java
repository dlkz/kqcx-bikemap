package com.kqcx.bikemap;

final class FinderSnapshotCapture {
    private final FinderSnapshotStore store;
    private final BikeRepository repository;

    FinderSnapshotCapture(FinderSnapshotStore store, BikeRepository repository) {
        this.store = store;
        this.repository = repository;
    }

    long capture(
            Bike bike,
            String siteName,
            double queryLatitude,
            double queryLongitude,
            boolean resolveBluetooth,
            long capturedAtMs
    ) {
        if (bike == null
                || bike.number == null
                || !bike.number.matches("\\d{9}")) {
            return 0L;
        }
        long clearGeneration = store.clearGeneration();
        boolean savedInitial = store.saveInitial(bike, siteName, capturedAtMs);
        if (!resolveBluetooth
                || repository == null
                || !CoordinateUtils.isValidLatLng(queryLatitude, queryLongitude)) {
            return capturedAtMs;
        }
        repository.queryOneCar(
                bike.number,
                queryLatitude,
                queryLongitude,
                new BikeRepository.BikeCallback() {
                    @Override
                    public void onSuccess(Bike resolved) {
                        if (resolved == null) {
                            return;
                        }
                        if (savedInitial) {
                            store.updateFromBike(
                                    bike.number,
                                    capturedAtMs,
                                    resolved
                            );
                        } else {
                            String resolvedSiteName = preferredSiteName(
                                    resolved,
                                    siteName
                            );
                            store.saveResolvedIfCurrent(
                                    clearGeneration,
                                    resolved,
                                    resolvedSiteName,
                                    capturedAtMs
                            );
                        }
                    }

                    @Override
                    public void onError(String message) {
                        // The map snapshot remains useful when BLE identity lookup fails.
                    }
                }
        );
        return capturedAtMs;
    }

    private static String preferredSiteName(Bike bike, String fallback) {
        String serviceSite = bike.serviceSiteName == null
                ? ""
                : bike.serviceSiteName.trim();
        if (!serviceSite.isEmpty()) {
            return BikeRepository.correctSiteName(serviceSite);
        }
        String pointSite = bike.siteName == null ? "" : bike.siteName.trim();
        if (!pointSite.isEmpty()) {
            return BikeRepository.correctSiteName(pointSite);
        }
        return fallback == null ? "" : fallback;
    }
}
