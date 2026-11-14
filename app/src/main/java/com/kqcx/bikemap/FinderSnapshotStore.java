package com.kqcx.bikemap;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class FinderSnapshotStore {
    static final int MAX_SNAPSHOTS = 3;

    static final class Snapshot {
        final String number;
        final double latitude;
        final double longitude;
        final long lastUploadAtMs;
        final long capturedAtMs;
        final String bluetoothName;
        final String siteName;

        Snapshot(
                String number,
                double latitude,
                double longitude,
                long lastUploadAtMs,
                long capturedAtMs,
                String bluetoothName,
                String siteName
        ) {
            this.number = number;
            this.latitude = latitude;
            this.longitude = longitude;
            this.lastUploadAtMs = lastUploadAtMs;
            this.capturedAtMs = capturedAtMs;
            this.bluetoothName = bluetoothName == null ? "" : bluetoothName.trim();
            this.siteName = siteName == null ? "" : siteName.trim();
        }

        boolean hasLocation() {
            return CoordinateUtils.isValidLatLng(latitude, longitude);
        }

    }

    private static final String PREFERENCES = "finder_snapshots";
    private static final String KEY_COUNT = "count";
    private static final String KEY_CLEAR_GENERATION = "clear_generation";
    private static final String SLOT_PREFIX = "slot_";

    private final SharedPreferences preferences;

    FinderSnapshotStore(Context context) {
        this(context, PREFERENCES);
    }

    FinderSnapshotStore(Context context, String preferencesName) {
        preferences = context.getApplicationContext().getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
        );
    }

    List<Snapshot> load() {
        int count = Math.min(
                MAX_SNAPSHOTS,
                Math.max(0, preferences.getInt(KEY_COUNT, 0))
        );
        List<Snapshot> snapshots = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Snapshot snapshot = readSlot(index);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    boolean saveInitial(Bike bike, String siteName, long capturedAtMs) {
        if (bike == null || !bike.locationKnown) {
            return false;
        }
        Snapshot snapshot = snapshotFromBike(
                bike,
                siteName,
                capturedAtMs
        );
        if (snapshot == null) {
            return false;
        }
        insertOrReplace(snapshot);
        return true;
    }

    void updateFromBike(String number, long capturedAtMs, Bike bike) {
        List<Snapshot> updated = updateMatchingCapture(
                load(),
                number,
                capturedAtMs,
                bike
        );
        if (updated != null) {
            write(updated);
        }
    }

    static List<Snapshot> updateMatchingCapture(
            List<Snapshot> existingSnapshots,
            String number,
            long capturedAtMs,
            Bike bike
    ) {
        if (bike == null || bike.number == null || !bike.number.equals(number)) {
            return null;
        }
        List<Snapshot> snapshots = new ArrayList<>(existingSnapshots);
        for (int index = 0; index < snapshots.size(); index++) {
            Snapshot existing = snapshots.get(index);
            if (!existing.number.equals(number)
                    || existing.capturedAtMs != capturedAtMs) {
                continue;
            }
            String bluetoothName = bike.bluetoothName == null
                    ? ""
                    : bike.bluetoothName.trim();
            String siteName = bike.serviceSiteName == null
                    ? ""
                    : bike.serviceSiteName.trim();
            boolean useQueriedLocation = VehicleLocationFreshness.canReplace(
                    existing.lastUploadAtMs,
                    bike.locationKnown,
                    bike.lastUploadAtMs
            );
            Snapshot updated = new Snapshot(
                    existing.number,
                    useQueriedLocation ? bike.latitude : existing.latitude,
                    useQueriedLocation ? bike.longitude : existing.longitude,
                    useQueriedLocation ? bike.lastUploadAtMs : existing.lastUploadAtMs,
                    existing.capturedAtMs,
                    bluetoothName.isEmpty() ? existing.bluetoothName : bluetoothName,
                    siteName.isEmpty() ? existing.siteName : siteName
            );
            snapshots.set(index, updated);
            return snapshots;
        }
        return null;
    }

    void clear() {
        long nextGeneration = clearGeneration() + 1L;
        preferences.edit()
                .clear()
                .putLong(KEY_CLEAR_GENERATION, nextGeneration)
                .apply();
    }

    long clearGeneration() {
        return preferences.getLong(KEY_CLEAR_GENERATION, 0L);
    }

    void saveResolvedIfCurrent(
            long expectedClearGeneration,
            Bike bike,
            String siteName,
            long capturedAtMs
    ) {
        if (expectedClearGeneration == clearGeneration()) {
            saveInitial(bike, siteName, capturedAtMs);
        }
    }

    private void insertOrReplace(Snapshot candidate) {
        write(mergeForStorage(load(), candidate));
    }

    static List<Snapshot> mergeForStorage(
            List<Snapshot> existing,
            Snapshot candidate
    ) {
        List<Snapshot> snapshots = new ArrayList<>(existing);
        Snapshot mergedCandidate = candidate;
        for (int index = snapshots.size() - 1; index >= 0; index--) {
            Snapshot current = snapshots.get(index);
            if (current.number.equals(candidate.number)) {
                boolean useCandidateLocation = VehicleLocationFreshness.canReplace(
                        current.lastUploadAtMs,
                        candidate.hasLocation(),
                        candidate.lastUploadAtMs
                );
                boolean candidateIsNewerCapture =
                        candidate.capturedAtMs >= current.capturedAtMs;
                mergedCandidate = new Snapshot(
                        candidate.number,
                        useCandidateLocation ? candidate.latitude : current.latitude,
                        useCandidateLocation ? candidate.longitude : current.longitude,
                        useCandidateLocation
                                ? candidate.lastUploadAtMs
                                : current.lastUploadAtMs,
                        Math.max(candidate.capturedAtMs, current.capturedAtMs),
                        candidateIsNewerCapture
                                ? preferNonEmpty(
                                        candidate.bluetoothName,
                                        current.bluetoothName
                                )
                                : preferNonEmpty(
                                        current.bluetoothName,
                                        candidate.bluetoothName
                                ),
                        candidateIsNewerCapture
                                ? preferNonEmpty(candidate.siteName, current.siteName)
                                : preferNonEmpty(current.siteName, candidate.siteName)
                );
                snapshots.remove(index);
            }
        }
        snapshots.add(0, mergedCandidate);
        snapshots.sort((left, right) ->
                Long.compare(right.capturedAtMs, left.capturedAtMs));
        while (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.remove(snapshots.size() - 1);
        }
        return snapshots;
    }

    private static String preferNonEmpty(String preferred, String fallback) {
        return preferred == null || preferred.isEmpty() ? fallback : preferred;
    }

    private Snapshot snapshotFromBike(
            Bike bike,
            String siteName,
            long capturedAtMs
    ) {
        if (bike == null
                || bike.number == null
                || !bike.number.matches("\\d{9}")
                || !CoordinateUtils.isValidLatLng(bike.latitude, bike.longitude)) {
            return null;
        }
        String resolvedSiteName = siteName == null ? "" : siteName.trim();
        if (resolvedSiteName.isEmpty()) {
            resolvedSiteName = bike.serviceSiteName == null
                    ? ""
                    : bike.serviceSiteName.trim();
        }
        String resolvedBluetoothName = bike.bluetoothName == null
                ? ""
                : bike.bluetoothName.trim();
        return new Snapshot(
                bike.number,
                bike.latitude,
                bike.longitude,
                bike.lastUploadAtMs,
                capturedAtMs,
                resolvedBluetoothName,
                resolvedSiteName
        );
    }

    private Snapshot readSlot(int index) {
        String prefix = SLOT_PREFIX + index + "_";
        String number = VehicleNumberCatalog.normalize(
                preferences.getString(prefix + "number", "")
        );
        if (!number.matches("\\d{9}")) {
            return null;
        }
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(
                    preferences.getString(prefix + "latitude", "NaN")
            );
            longitude = Double.parseDouble(
                    preferences.getString(prefix + "longitude", "NaN")
            );
        } catch (NumberFormatException exception) {
            return null;
        }
        if (!CoordinateUtils.isValidLatLng(latitude, longitude)) {
            return null;
        }
        long capturedAtMs = preferences.getLong(prefix + "captured_at", 0L);
        if (capturedAtMs <= 0L) {
            return null;
        }
        return new Snapshot(
                number,
                latitude,
                longitude,
                preferences.getLong(prefix + "last_upload", 0L),
                capturedAtMs,
                preferences.getString(prefix + "bluetooth_name", ""),
                preferences.getString(prefix + "site_name", "")
        );
    }

    private void write(List<Snapshot> snapshots) {
        SharedPreferences.Editor editor = preferences.edit();
        long clearGeneration = clearGeneration();
        editor.clear();
        editor.putLong(KEY_CLEAR_GENERATION, clearGeneration);
        int count = Math.min(MAX_SNAPSHOTS, snapshots.size());
        editor.putInt(KEY_COUNT, count);
        for (int index = 0; index < count; index++) {
            Snapshot snapshot = snapshots.get(index);
            String prefix = SLOT_PREFIX + index + "_";
            editor.putString(prefix + "number", snapshot.number);
            editor.putString(prefix + "latitude", Double.toString(snapshot.latitude));
            editor.putString(prefix + "longitude", Double.toString(snapshot.longitude));
            editor.putLong(prefix + "last_upload", snapshot.lastUploadAtMs);
            editor.putLong(prefix + "captured_at", snapshot.capturedAtMs);
            editor.putString(prefix + "bluetooth_name", snapshot.bluetoothName);
            editor.putString(prefix + "site_name", snapshot.siteName);
        }
        editor.apply();
    }

}
