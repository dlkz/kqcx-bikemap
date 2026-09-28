package com.kqcx.bikemap;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only index of the vehicle numbers bundled with the application. */
final class VehicleNumberCatalog {
    static final int NUMBER_LENGTH = 9;
    static final int TAIL_LENGTH = 3;

    static final class Site {
        final int id;
        final String name;
        final List<String> series;

        Site(int id, String name, List<String> series) {
            this.id = id;
            this.name = name;
            this.series = Collections.unmodifiableList(series);
        }
    }

    static final class NumberInfo {
        final String number;
        final int siteId;
        final String siteName;
        final String series;

        NumberInfo(String number, int siteId, String siteName, String series) {
            this.number = number;
            this.siteId = siteId;
            this.siteName = siteName;
            this.series = series;
        }

    }

    private final List<Site> sites;
    private final Map<Integer, Site> sitesById;
    private final Map<String, NumberInfo> byNumber;
    private final Map<String, List<NumberInfo>> bySiteSeries;

    private VehicleNumberCatalog(
            List<Site> sites,
            Map<String, NumberInfo> byNumber,
            Map<String, List<NumberInfo>> bySiteSeries
    ) {
        this.sites = Collections.unmodifiableList(sites);
        this.byNumber = Collections.unmodifiableMap(byNumber);
        this.bySiteSeries = Collections.unmodifiableMap(bySiteSeries);
        Map<Integer, Site> indexedSites = new HashMap<>();
        for (Site site : sites) {
            indexedSites.put(site.id, site);
        }
        this.sitesById = Collections.unmodifiableMap(indexedSites);
    }

    static VehicleNumberCatalog load(Context context) throws IOException, JSONException {
        String json;
        try (InputStream input = context.getAssets().open("vehicle_number_catalog.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     input,
                     StandardCharsets.UTF_8
             ))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            json = builder.toString();
        }

        JSONObject root = new JSONObject(json);
        JSONArray siteArray = root.getJSONArray("sites");
        List<Site> sites = new ArrayList<>();
        Map<Integer, String> siteNames = new HashMap<>();
        for (int index = 0; index < siteArray.length(); index++) {
            JSONObject item = siteArray.getJSONObject(index);
            int id = item.getInt("id");
            String name = item.getString("name");
            JSONArray seriesArray = item.getJSONArray("series");
            List<String> series = new ArrayList<>();
            for (int seriesIndex = 0; seriesIndex < seriesArray.length(); seriesIndex++) {
                series.add(seriesArray.getString(seriesIndex));
            }
            sites.add(new Site(id, name, series));
            siteNames.put(id, name);
        }

        Map<String, NumberInfo> byNumber = new HashMap<>();
        Map<String, List<NumberInfo>> bySiteSeries = new LinkedHashMap<>();
        JSONObject numberObject = root.getJSONObject("numbers");
        java.util.Iterator<String> keys = numberObject.keys();
        while (keys.hasNext()) {
            String number = keys.next();
            JSONArray details = numberObject.getJSONArray(number);
            int siteId = details.getInt(0);
            String series = details.getString(1);
            NumberInfo info = new NumberInfo(number, siteId, siteNames.get(siteId), series);
            byNumber.put(number, info);
            bySiteSeries.computeIfAbsent(key(siteId, series), ignored -> new ArrayList<>())
                    .add(info);
        }
        for (List<NumberInfo> values : bySiteSeries.values()) {
            values.sort((left, right) -> left.number.compareTo(right.number));
        }
        sites.sort((left, right) -> Integer.compare(left.id, right.id));
        return new VehicleNumberCatalog(sites, byNumber, bySiteSeries);
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isDigit(character)) {
                digits.append(character);
            }
        }
        while (digits.length() < NUMBER_LENGTH) {
            digits.insert(0, '0');
        }
        return digits.toString();
    }

    List<Site> sites() {
        return sites;
    }

    Site site(int siteId) {
        return sitesById.get(siteId);
    }

    NumberInfo find(String number) {
        return byNumber.get(normalize(number));
    }

    List<NumberInfo> matchesSuffix(int siteId, String series, String suffix) {
        if (suffix == null
                || suffix.length() < TAIL_LENGTH
                || suffix.length() > 4) {
            return Collections.emptyList();
        }
        List<NumberInfo> matches = new ArrayList<>();
        for (Map.Entry<String, List<NumberInfo>> entry : bySiteSeries.entrySet()) {
            if (!entry.getKey().startsWith(siteId + "|")) {
                continue;
            }
            if (series != null && !entry.getKey().equals(key(siteId, series))) {
                continue;
            }
            for (NumberInfo value : entry.getValue()) {
                if (value.number.endsWith(suffix)) {
                    matches.add(value);
                }
            }
        }
        matches.sort((left, right) -> left.number.compareTo(right.number));
        return matches;
    }

    private static String key(int siteId, String series) {
        return siteId + "|" + series;
    }
}
