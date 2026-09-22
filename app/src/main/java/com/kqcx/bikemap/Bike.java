package com.kqcx.bikemap;

public final class Bike {
    public final long number;
    public final double latitude;
    public final double longitude;
    public final double batteryPercent;
    public final String status;
    public final String model;
    /** Operator's service site, for example the campus name. */
    public final String serviceSiteName;
    /** Specific vehicle point, for example a building or gate name. */
    public final String siteName;
    public final float distanceMeters;
    public final boolean available;
    public final boolean locationKnown;

    public Bike(
            long number,
            double latitude,
            double longitude,
            double batteryPercent,
            String status,
            String model,
            String serviceSiteName,
            String siteName,
            float distanceMeters,
            boolean available,
            boolean locationKnown
    ) {
        this.number = number;
        this.latitude = latitude;
        this.longitude = longitude;
        this.batteryPercent = batteryPercent;
        this.status = status;
        this.model = model;
        this.serviceSiteName = serviceSiteName;
        this.siteName = siteName;
        this.distanceMeters = distanceMeters;
        this.available = available;
        this.locationKnown = locationKnown;
    }

    public static Bike manual(long number) {
        return new Bike(
                number,
                0,
                0,
                -1,
                "仅生成二维码",
                "",
                "",
                "",
                0,
                false,
                false
        );
    }

    public String qrUrl() {
        return "https://www.kvcoogo.com/ebike?id=" + number;
    }
}
