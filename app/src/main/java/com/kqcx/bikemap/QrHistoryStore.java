package com.kqcx.bikemap;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class QrHistoryStore {
    static final int MAX_ENTRIES = 3;

    static final class Entry {
        final String number;
        final String tail;
        final String siteName;

        Entry(String number, String siteName) {
            this(number, "", siteName);
        }

        Entry(String number, String tail, String siteName) {
            this.number = number == null ? "" : number;
            this.tail = tail == null ? "" : tail;
            this.siteName = siteName == null ? "" : siteName;
        }

        String displayNumber() {
            return number.isEmpty() ? tail : number;
        }
    }

    private static final String KEY_HISTORY = "qr_history";
    private static final String KEY_KEEP_HISTORY = "keep_qr_history";

    private final SharedPreferences preferences;

    QrHistoryStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    boolean retentionEnabled() {
        return preferences.getBoolean(KEY_KEEP_HISTORY, true);
    }

    void setRetentionEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_KEEP_HISTORY, enabled).apply();
    }

    List<Entry> load(VehicleNumberCatalog catalog) {
        List<Entry> history = new ArrayList<>();
        if (!retentionEnabled()) {
            return history;
        }
        String raw = preferences.getString(KEY_HISTORY, "");
        if (raw == null || raw.isEmpty()) {
            return history;
        }
        VehicleNumberCatalog resolvedCatalog = catalog == null
                ? VehicleNumberCatalog.empty()
                : catalog;
        for (String value : raw.split(",")) {
            String stored = value.trim();
            String storedSite = "";
            int separator = stored.indexOf('|');
            if (separator >= 0) {
                storedSite = stored.substring(separator + 1).trim();
                stored = stored.substring(0, separator).trim();
            }
            if (stored.isEmpty()) {
                continue;
            }
            String number = "";
            String tail = "";
            VehicleNumberCatalog.NumberInfo info = null;
            if (stored.matches("\\d{1,4}")) {
                tail = stored;
                if (tail.length() >= VehicleNumberCatalog.TAIL_LENGTH) {
                    info = resolveUniqueHistoryTail(
                            resolvedCatalog,
                            storedSite,
                            tail
                    );
                }
                if (info != null) {
                    number = info.number;
                    storedSite = info.siteName == null ? storedSite : info.siteName;
                }
            } else {
                number = VehicleNumberCatalog.normalize(stored);
                if (!number.matches("\\d{9}")) {
                    continue;
                }
                info = resolvedCatalog.find(number);
            }
            if (storedSite.isEmpty()
                    && info != null
                    && info.siteName != null) {
                storedSite = info.siteName;
            }
            storedSite = BikeRepository.correctSiteName(storedSite);
            Entry entry = new Entry(number, tail, storedSite);
            if (contains(history, entry)) {
                continue;
            }
            history.add(entry);
        }
        while (history.size() > MAX_ENTRIES) {
            history.remove(history.size() - 1);
        }
        String normalized = serialize(history);
        if (!normalized.equals(raw)) {
            write(normalized);
        }
        return history;
    }

    void remember(
            String number,
            String siteName,
            VehicleNumberCatalog catalog
    ) {
        if (!retentionEnabled()) {
            clear();
            return;
        }
        String normalized = VehicleNumberCatalog.normalize(number);
        String resolvedSite = siteName == null ? "" : siteName.trim();
        VehicleNumberCatalog resolvedCatalog = catalog == null
                ? VehicleNumberCatalog.empty()
                : catalog;
        if (resolvedSite.isEmpty()) {
            VehicleNumberCatalog.NumberInfo info = resolvedCatalog.find(normalized);
            resolvedSite = info == null || info.siteName == null ? "" : info.siteName;
        }
        resolvedSite = BikeRepository.correctSiteName(resolvedSite);
        List<Entry> history = load(resolvedCatalog);
        for (int index = history.size() - 1; index >= 0; index--) {
            if (history.get(index).number.equals(normalized)) {
                history.remove(index);
            }
        }
        history.add(0, new Entry(normalized, "", resolvedSite));
        while (history.size() > MAX_ENTRIES) {
            history.remove(history.size() - 1);
        }
        write(serialize(history));
    }

    void clear() {
        preferences.edit().remove(KEY_HISTORY).apply();
    }

    private String serialize(List<Entry> history) {
        StringBuilder raw = new StringBuilder();
        for (int index = 0; index < history.size(); index++) {
            if (index > 0) {
                raw.append(',');
            }
            Entry entry = history.get(index);
            raw.append(entry.displayNumber());
            if (!entry.siteName.isEmpty()) {
                raw.append('|').append(entry.siteName);
            }
        }
        return raw.toString();
    }

    private void write(String value) {
        preferences.edit().putString(KEY_HISTORY, value).apply();
    }

    private static boolean contains(List<Entry> history, Entry candidate) {
        for (Entry entry : history) {
            if (entry.displayNumber().equals(candidate.displayNumber())) {
                return true;
            }
        }
        return false;
    }

    private static VehicleNumberCatalog.NumberInfo resolveUniqueHistoryTail(
            VehicleNumberCatalog catalog,
            String siteName,
            String tail
    ) {
        VehicleNumberCatalog.NumberInfo unique = null;
        String expectedSite = BikeRepository.correctSiteName(siteName);
        for (VehicleNumberCatalog.Site site : catalog.sites()) {
            for (VehicleNumberCatalog.NumberInfo candidate
                    : catalog.matchesSuffix(site.id, null, tail)) {
                if (!expectedSite.isEmpty()
                        && !expectedSite.equals(BikeRepository.correctSiteName(
                        candidate.siteName
                ))) {
                    continue;
                }
                if (unique != null && !unique.number.equals(candidate.number)) {
                    return null;
                }
                unique = candidate;
            }
        }
        return unique;
    }
}
