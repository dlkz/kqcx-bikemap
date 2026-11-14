package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class BikeRepositoryTest {
    @Test
    public void requestedNumberMustMatchParsedBike() {
        Bike bike = Bike.manual("123456789", "");

        assertTrue(BikeRepository.matchesRequestedNumber("123456789", bike));
        assertFalse(BikeRepository.matchesRequestedNumber("987654321", bike));
        assertFalse(BikeRepository.matchesRequestedNumber("123456789", null));
    }

    @Test
    public void vehicleUnavailableCodeIsDetected() {
        assertTrue(BikeRepository.isVehicleUnavailableErrorCode(50006));
        assertFalse(BikeRepository.isVehicleUnavailableErrorCode(50005));
        assertFalse(BikeRepository.isVehicleUnavailableErrorCode(0));
    }

    @Test
    public void unavailableCodeIsReadFromJsonEnvelope() throws Exception {
        JSONObject response = new JSONObject(
                "{\"errorCode\":50006,\"resultMsg\":\"车辆已出租\"}"
        );

        assertEquals(50006, BikeRepository.errorCodeFromEnvelope(response));
    }

    @Test
    public void unavailableCodeUsesUnavailableCallback() {
        int[] receivedCode = {-1};
        String[] receivedMessage = {""};
        boolean[] errorCalled = {false};

        BikeRepository.dispatchBikeCallbackError(
                50006,
                "车辆已出租",
                new BikeRepository.BikeCallback() {
                    @Override
                    public void onSuccess(Bike bike) {
                    }

                    @Override
                    public void onError(String message) {
                        errorCalled[0] = true;
                    }

                    @Override
                    public void onUnavailable(int errorCode, String message) {
                        receivedCode[0] = errorCode;
                        receivedMessage[0] = message;
                    }
                }
        );

        assertEquals(50006, receivedCode[0]);
        assertEquals("车辆已出租", receivedMessage[0]);
        assertFalse(errorCalled[0]);
    }
}
