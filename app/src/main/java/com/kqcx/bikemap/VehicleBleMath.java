package com.kqcx.bikemap;

final class VehicleBleMath {
    static final int BAND_NONE = 0;
    static final int BAND_VERY_NEAR = 1;
    static final int BAND_NEAR = 2;
    static final int BAND_FAR = 3;

    private static final String TWO_SERIES_PREFIX = "200000";

    private VehicleBleMath() {
    }

    static boolean isTwoSeries(String number) {
        return number != null && number.startsWith(TWO_SERIES_PREFIX);
    }

    static boolean supportsDirectMatch(String number, String bluetoothName) {
        String name = normalizedName(bluetoothName);
        return number != null
                && number.matches("\\d{9}")
                && !isTwoSeries(number)
                && name.matches("xlc-\\d{6,12}");
    }

    static boolean namesMatch(String expectedName, String advertisedName) {
        String expected = normalizedName(expectedName);
        return !expected.isEmpty() && expected.equals(normalizedName(advertisedName));
    }

    static int proximityBand(int rssi) {
        if (rssi >= -55) {
            return BAND_VERY_NEAR;
        }
        if (rssi >= -67) {
            return BAND_NEAR;
        }
        return BAND_FAR;
    }

    static int median(int[] samples, int count) {
        if (samples == null || count <= 0) {
            return Integer.MIN_VALUE;
        }
        int safeCount = Math.min(count, samples.length);
        int[] copy = new int[safeCount];
        System.arraycopy(samples, 0, copy, 0, safeCount);
        java.util.Arrays.sort(copy);
        return copy[safeCount / 2];
    }

    private static String normalizedName(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
