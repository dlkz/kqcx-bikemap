package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BikeTest {
    @Test
    public void manualBikeKeepsCampusInServiceSiteName() {
        Bike bike = Bike.manual("123456789", "江西师范大学");

        assertEquals("", bike.model);
        assertEquals("江西师范大学", bike.serviceSiteName);
        assertEquals("", bike.siteName);
    }

    @Test
    public void manualBikeNormalizesNullCampusToEmptyString() {
        Bike bike = Bike.manual("123456789", null);

        assertEquals("", bike.serviceSiteName);
    }
}
