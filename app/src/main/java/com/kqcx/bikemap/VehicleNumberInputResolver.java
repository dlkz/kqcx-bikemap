package com.kqcx.bikemap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VehicleNumberInputResolver {
    enum Status {
        EMPTY,
        CATALOG_LOADING,
        DIRECT,
        RESOLVED,
        CANDIDATES,
        INCOMPLETE,
        NOT_FOUND
    }

    static final class Result {
        final Status status;
        final String number;
        final VehicleNumberCatalog.NumberInfo info;
        final List<VehicleNumberCatalog.NumberInfo> candidates;

        private Result(
                Status status,
                String number,
                VehicleNumberCatalog.NumberInfo info,
                List<VehicleNumberCatalog.NumberInfo> candidates
        ) {
            this.status = status;
            this.number = number == null ? "" : number;
            this.info = info;
            this.candidates = candidates == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(candidates));
        }

        static Result simple(Status status) {
            return new Result(status, "", null, null);
        }

        static Result direct(String number) {
            return new Result(Status.DIRECT, number, null, null);
        }

        static Result resolved(VehicleNumberCatalog.NumberInfo info) {
            return new Result(Status.RESOLVED, info.number, info, null);
        }

        static Result candidates(List<VehicleNumberCatalog.NumberInfo> candidates) {
            return new Result(Status.CANDIDATES, "", null, candidates);
        }
    }

    private final VehicleNumberCatalog catalog;
    private final boolean catalogLoaded;
    private int siteId;
    private String series;

    VehicleNumberInputResolver(
            VehicleNumberCatalog catalog,
            boolean catalogLoaded,
            int siteId,
            String series
    ) {
        this.catalog = catalog == null ? VehicleNumberCatalog.empty() : catalog;
        this.catalogLoaded = catalogLoaded;
        select(siteId, series);
    }

    int siteId() {
        return siteId;
    }

    String series() {
        return series;
    }

    void select(int siteId, String series) {
        this.siteId = siteId;
        VehicleNumberCatalog.Site site = catalog.site(siteId);
        if (site == null || site.series.isEmpty()) {
            this.series = "";
        } else if (series != null && site.series.contains(series)) {
            this.series = series;
        } else {
            this.series = site.series.get(0);
        }
    }

    Result resolve(String rawValue) {
        String raw = rawValue == null ? "" : rawValue.trim();
        if (raw.isEmpty()) {
            return Result.simple(Status.EMPTY);
        }
        if (!catalogLoaded && raw.length() != VehicleNumberCatalog.NUMBER_LENGTH) {
            return Result.simple(Status.CATALOG_LOADING);
        }
        if (raw.length() == VehicleNumberCatalog.NUMBER_LENGTH) {
            String normalized = VehicleNumberCatalog.normalize(raw);
            VehicleNumberCatalog.NumberInfo info = catalog.find(normalized);
            if (info == null) {
                return Result.direct(normalized);
            }
            select(info.siteId, info.series);
            return Result.resolved(info);
        }
        if (raw.length() == VehicleNumberCatalog.TAIL_LENGTH || raw.length() == 4) {
            normalizeSeries();
            List<VehicleNumberCatalog.NumberInfo> seriesMatches =
                    catalog.matchesSuffix(siteId, series, raw);
            List<VehicleNumberCatalog.NumberInfo> matches = seriesMatches.isEmpty()
                    ? catalog.matchesSuffix(siteId, null, raw)
                    : seriesMatches;
            if (matches.size() == 1) {
                VehicleNumberCatalog.NumberInfo info = matches.get(0);
                select(info.siteId, info.series);
                return Result.resolved(info);
            }
            if (matches.size() > 1) {
                return Result.candidates(matches);
            }
            return Result.simple(Status.NOT_FOUND);
        }
        return Result.simple(Status.INCOMPLETE);
    }

    private void normalizeSeries() {
        VehicleNumberCatalog.Site site = catalog.site(siteId);
        if (site == null || site.series.isEmpty()) {
            series = "";
        } else if (!site.series.contains(series)) {
            series = site.series.get(0);
        }
    }
}
