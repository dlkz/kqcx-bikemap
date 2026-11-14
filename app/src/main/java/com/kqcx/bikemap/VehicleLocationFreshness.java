package com.kqcx.bikemap;

final class VehicleLocationFreshness {
    private VehicleLocationFreshness() {
    }

    static boolean canReplace(
            long existingLastUploadAtMs,
            boolean candidateLocationKnown,
            long candidateLastUploadAtMs
    ) {
        if (!candidateLocationKnown) {
            return false;
        }
        if (existingLastUploadAtMs <= 0L) {
            return true;
        }
        return candidateLastUploadAtMs > 0L
                && candidateLastUploadAtMs >= existingLastUploadAtMs;
    }
}
