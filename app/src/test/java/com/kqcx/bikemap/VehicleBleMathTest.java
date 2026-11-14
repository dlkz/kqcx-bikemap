package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VehicleBleMathTest {
    @Test
    public void supportsOnlyExactLegacyXlcIdentityOutsideTwoSeries() {
        assertTrue(VehicleBleMath.supportsDirectMatch(
                "123456789",
                "xlc-000000001"
        ));
        assertTrue(VehicleBleMath.supportsDirectMatch(
                "123456788",
                "XLC-000000002"
        ));
        assertFalse(VehicleBleMath.supportsDirectMatch(
                "200000999",
                "xlc-000000003"
        ));
        assertFalse(VehicleBleMath.supportsDirectMatch(
                "123456789",
                "OTHER-DEVICE"
        ));
        assertFalse(VehicleBleMath.supportsDirectMatch(
                "123456789",
                ""
        ));
    }

    @Test
    public void broadcastNamesMustMatchExactlyIgnoringCaseAndWhitespace() {
        assertTrue(VehicleBleMath.namesMatch("xlc-000000001", " XLC-000000001 "));
        assertFalse(VehicleBleMath.namesMatch("xlc-000000001", "xlc-0000000010"));
        assertFalse(VehicleBleMath.namesMatch("xlc-000000001", "OTHER-DEVICE"));
        assertFalse(VehicleBleMath.namesMatch("", ""));
    }

    @Test
    public void combinesRssiIntoStableProximityBands() {
        assertEquals(VehicleBleMath.BAND_VERY_NEAR, VehicleBleMath.proximityBand(-50));
        assertEquals(VehicleBleMath.BAND_NEAR, VehicleBleMath.proximityBand(-60));
        assertEquals(VehicleBleMath.BAND_FAR, VehicleBleMath.proximityBand(-80));
        assertEquals(-65, VehicleBleMath.median(new int[]{-70, -65, -60, 0, 0}, 3));
    }
}
