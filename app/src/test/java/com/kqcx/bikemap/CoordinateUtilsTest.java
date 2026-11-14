package com.kqcx.bikemap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CoordinateUtilsTest {
    @Test
    public void acceptsFiniteNonZeroCoordinatesWithinWorldBounds() {
        assertTrue(CoordinateUtils.isValidLatLng(1.0, 1.0));
        assertTrue(CoordinateUtils.isValidLatLng(-90, -180));
        assertTrue(CoordinateUtils.isValidLatLng(90, 180));
        assertTrue(CoordinateUtils.isValidLatLng(0, 100));
    }

    @Test
    public void rejectsMissingZeroAndOutOfRangeCoordinates() {
        assertFalse(CoordinateUtils.isValidLatLng(0, 0));
        assertFalse(CoordinateUtils.isValidLatLng(Double.NaN, 1.0));
        assertFalse(CoordinateUtils.isValidLatLng(1.0, Double.NaN));
        assertFalse(CoordinateUtils.isValidLatLng(90.1, 1.0));
        assertFalse(CoordinateUtils.isValidLatLng(1.0, -180.1));
    }
}
