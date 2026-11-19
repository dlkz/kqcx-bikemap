package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public final class VehicleFinderBleLocationActionInstrumentedTest {
    @Test
    public void locationDisabledShowsOnlyPageLevelSettingsAction() throws Exception {
        assumeTrue(
                "The test changes a system location setting and runs on the emulator only",
                Build.HARDWARE.contains("ranchu")
                        || Build.FINGERPRINT.contains("generic")
        );

        Context context = ApplicationProvider.getApplicationContext();
        String packageName = context.getPackageName();
        grantPermission(packageName, "android.permission.ACCESS_FINE_LOCATION");
        grantPermission(packageName, "android.permission.ACCESS_COARSE_LOCATION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grantPermission(packageName, "android.permission.BLUETOOTH_SCAN");
            grantPermission(packageName, "android.permission.BLUETOOTH_CONNECT");
        }

        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        assertNotNull(bluetoothManager);
        assumeTrue(
                "A BLE adapter is required to reach the location prerequisite branch",
                bluetoothManager.getAdapter() != null
        );

        LocationManager locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        assertNotNull(locationManager);
        boolean originallyEnabled = locationManager.isLocationEnabled();

        try {
            shell("cmd location set-location-enabled false");
            waitForLocationState(locationManager, false);

            Intent intent = new Intent(context, VehicleFinderActivity.class)
                    .putExtra("finder_number", "990000300")
                    .putExtra("finder_latitude", 1.01d)
                    .putExtra("finder_longitude", 1.01d)
                    .putExtra("finder_last_upload", System.currentTimeMillis())
                    .putExtra("finder_bluetooth_name", "xlc-000000001")
                    .putExtra("finder_source", "ESTIMATED");

            try (ActivityScenario<VehicleFinderActivity> scenario =
                         ActivityScenario.launch(intent)) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> assertSingleLocationAction(activity));
            }
        } finally {
            shell("cmd location set-location-enabled " + originallyEnabled);
            waitForLocationState(locationManager, originallyEnabled);
        }
    }

    private static void assertSingleLocationAction(Activity activity) {
        Button bleAction = activity.findViewById(R.id.btnFinderBleAction);
        Button pageAction = activity.findViewById(R.id.btnFinderAction);
        TextView bleStatus = activity.findViewById(R.id.tvFinderBleStatus);

        assertEquals(View.GONE, bleAction.getVisibility());
        assertEquals(View.VISIBLE, pageAction.getVisibility());
        assertEquals(
                activity.getString(R.string.finder_open_location_settings),
                pageAction.getText().toString()
        );
        assertEquals(
                activity.getString(R.string.finder_ble_location_off),
                bleStatus.getText().toString()
        );
    }

    private static void grantPermission(String packageName, String permission)
            throws IOException {
        shell("pm grant " + packageName + " " + permission);
    }

    private static void waitForLocationState(
            LocationManager locationManager,
            boolean expected
    ) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (locationManager.isLocationEnabled() == expected) {
                return;
            }
            Thread.sleep(100L);
        }
        assertEquals(expected, locationManager.isLocationEnabled());
    }

    private static void shell(String command) throws IOException {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        try (ParcelFileDescriptor.AutoCloseInputStream ignored =
                     new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            while (ignored.read(buffer) >= 0) {
                // Drain the command output so it has completed before continuing.
            }
        }
    }
}
