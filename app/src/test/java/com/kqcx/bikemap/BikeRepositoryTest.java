package com.kqcx.bikemap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BikeRepositoryTest {
    @Test
    public void requestedNumberMustMatchParsedBike() {
        Bike bike = Bike.manual("123456789", "");

        assertTrue(BikeRepository.matchesRequestedNumber("123456789", bike));
        assertFalse(BikeRepository.matchesRequestedNumber("987654321", bike));
        assertFalse(BikeRepository.matchesRequestedNumber("123456789", null));
    }
}
