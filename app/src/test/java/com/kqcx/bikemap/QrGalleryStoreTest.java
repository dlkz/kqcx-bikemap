package com.kqcx.bikemap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class QrGalleryStoreTest {
    @Test
    public void ownerMustMatchCurrentPackage() {
        assertTrue(QrGalleryStore.isOwnedByPackage(
                "com.kqcx.bikemap.debug",
                "com.kqcx.bikemap.debug"
        ));
        assertFalse(QrGalleryStore.isOwnedByPackage(
                "com.example.other",
                "com.kqcx.bikemap.debug"
        ));
        assertFalse(QrGalleryStore.isOwnedByPackage(null, "com.kqcx.bikemap.debug"));
    }

    @Test
    public void sizeMustMatchSavedValue() {
        assertTrue(QrGalleryStore.matchesExpectedSize(123L, 123L));
        assertFalse(QrGalleryStore.matchesExpectedSize(123L, 124L));
        assertFalse(QrGalleryStore.matchesExpectedSize(123L, null));
        assertFalse(QrGalleryStore.matchesExpectedSize(-1L, -1L));
    }
}
