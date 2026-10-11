package com.kqcx.bikemap;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vehicle-number to campus hints the app picked up while the user looked up a
 * bike by number.  It only ever supplements the catalog bundled in
 * {@code assets/vehicle_number_catalog.json}; it never replaces it.
 *
 * <p>This is local user data: it records which numbers were looked up.  It is
 * kept in the same preferences file as the rest of the app settings, is capped
 * at {@link #MAX_ENTRIES} entries (oldest evicted first) and is cleared when
 * the user turns off "保留最近车辆编号".  Nothing here is uploaded.
 */
final class LearnedVehicleNumberStore {
    static final String PREF_KEY = "learned_vehicle_numbers";
    static final int MAX_ENTRIES = 500;

    static final class Entry {
        final String number;
        final String series;
        final String siteName;
        final long learnedAt;

        Entry(String number, String series, String siteName, long learnedAt) {
            this.number = number;
            this.series = series;
            this.siteName = siteName;
            this.learnedAt = learnedAt;
        }
    }

    private static LearnedVehicleNumberStore instance;

    private final SharedPreferences preferences;

    private LearnedVehicleNumberStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    static synchronized LearnedVehicleNumberStore get(Context context) {
        if (instance == null) {
            instance = new LearnedVehicleNumberStore(
                    context.getApplicationContext().getSharedPreferences(
                            BaseActivity.PREFS_APP,
                            Context.MODE_PRIVATE
                    )
            );
        }
        return instance;
    }

    /** All learned entries, oldest first.  Callers must not rely on the order. */
    synchronized List<Entry> entries() {
        return new ArrayList<>(index().values());
    }

    /**
     * Records {@code number -> siteName}.  Re-learning an existing number
     * refreshes it and moves it to the front; the table never grows past
     * {@link #MAX_ENTRIES}.
     */
    synchronized void remember(String number, String siteName) {
        String normalized = VehicleNumberCatalog.normalize(number);
        String name = siteName == null ? "" : siteName.trim();
        if (normalized.length() != VehicleNumberCatalog.NUMBER_LENGTH
                || !normalized.matches("\\d{9}")
                || name.isEmpty()) {
            return;
        }
        String normalizedSeries = normalized.substring(
                0,
                VehicleNumberCatalog.SERIES_LENGTH
        );
        Map<String, Entry> learned = index();
        learned.remove(normalized);
        learned.put(
                normalized,
                new Entry(normalized, normalizedSeries, name, System.currentTimeMillis())
        );
        while (learned.size() > MAX_ENTRIES) {
            String oldest = learned.keySet().iterator().next();
            learned.remove(oldest);
        }
        persist(learned.values());
    }

    synchronized void clear() {
        preferences.edit().remove(PREF_KEY).apply();
    }

    /**
     * Drops the given numbers from the learned table.  Used to prune entries
     * that the bundled catalog already covers, so a later release that removes
     * such a number cannot resurrect it from stale learning data.  Numbers that
     * are not present are ignored.
     */
    synchronized void removeAll(Collection<String> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return;
        }
        Map<String, Entry> learned = index();
        boolean changed = false;
        for (String number : numbers) {
            if (number != null && learned.remove(number) != null) {
                changed = true;
            }
        }
        if (changed) {
            persist(learned.values());
        }
    }

    /** Numbers keyed by number, ordered oldest first. */
    private Map<String, Entry> index() {
        Map<String, Entry> learned = new LinkedHashMap<>();
        String raw = preferences.getString(PREF_KEY, "");
        if (raw == null || raw.isEmpty()) {
            return learned;
        }
        JSONArray array;
        try {
            array = new JSONArray(raw);
        } catch (Exception exception) {
            return learned;
        }
        List<Entry> parsed = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONArray item = array.optJSONArray(index);
            if (item == null || item.length() < 3) {
                continue;
            }
            String number = VehicleNumberCatalog.normalize(item.optString(0, ""));
            String series = item.optString(1, "");
            String siteName = item.optString(2, "").trim();
            long learnedAt = item.optLong(3, 0L);
            if (number.length() != VehicleNumberCatalog.NUMBER_LENGTH
                    || !number.matches("\\d{9}")
                    || siteName.isEmpty()) {
                continue;
            }
            if (series.length() != VehicleNumberCatalog.SERIES_LENGTH) {
                series = number.substring(0, VehicleNumberCatalog.SERIES_LENGTH);
            }
            parsed.add(new Entry(number, series, siteName, learnedAt));
        }
        Collections.sort(parsed, (left, right) -> Long.compare(left.learnedAt, right.learnedAt));
        for (Entry entry : parsed) {
            learned.remove(entry.number);
            learned.put(entry.number, entry);
        }
        return learned;
    }

    private void persist(Iterable<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONArray item = new JSONArray();
            item.put(entry.number);
            item.put(entry.series);
            item.put(entry.siteName);
            item.put(entry.learnedAt);
            array.put(item);
        }
        preferences.edit().putString(PREF_KEY, array.toString()).apply();
    }
}
