package com.kqcx.bikemap;

public final class Bike {
    public final String number;
    public final double latitude;
    public final double longitude;
    public final double batteryPercent;
    /** 可用状态的字符串资源 id，由界面层解析。 */
    public final int statusRes;
    public final String model;
    /** Operator's service site, for example the campus name. */
    public final String serviceSiteName;
    /** Specific vehicle point, for example a building or gate name. */
    public final String siteName;
    public final float distanceMeters;
    public final boolean available;
    public final boolean locationKnown;
    /** Server timestamp in UTC milliseconds for the latest uploaded vehicle data. */
    public final long lastUploadAtMs;

    public Bike(
            String number,
            double latitude,
            double longitude,
            double batteryPercent,
            int statusRes,
            String model,
            String serviceSiteName,
            String siteName,
            float distanceMeters,
            boolean available,
            boolean locationKnown,
            long lastUploadAtMs
    ) {
        this.number = number;
        this.latitude = latitude;
        this.longitude = longitude;
        this.batteryPercent = batteryPercent;
        this.statusRes = statusRes;
        this.model = model;
        this.serviceSiteName = serviceSiteName;
        this.siteName = siteName;
        this.distanceMeters = distanceMeters;
        this.available = available;
        this.locationKnown = locationKnown;
        this.lastUploadAtMs = lastUploadAtMs;
    }

    public static Bike manual(String number, String serviceSiteName) {
        return new Bike(
                number,
                0,
                0,
                -1,
                R.string.bike_status_manual,
                serviceSiteName == null ? "" : serviceSiteName,
                "",
                "",
                0,
                false,
                false,
                0L
        );
    }

    public String qrUrl() {
        return "https://www.kvcoogo.com/ebike?id=" + number;
    }
}
