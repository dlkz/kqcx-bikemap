package com.kqcx.bikemap;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

final class VehicleBleScanner {
    interface Callback {
        void onTargetSignal(int rssi, boolean bandStable);

        void onTargetWeak();

        void onTargetLost();

        void onScanFailed();
    }

    private static final long SIGNAL_CHECK_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final boolean supportsLe;
    private final Callback callback;
    private final VehicleBleSignalTracker signalTracker = new VehicleBleSignalTracker();

    private BluetoothLeScanner scanner;
    private String expectedName = "";
    private boolean scanning;
    private boolean weakReported;
    private boolean signalCheckRunning;

    private final Runnable signalCheck = new Runnable() {
        @Override
        public void run() {
            if (!scanning || !signalTracker.isSeen()) {
                signalCheckRunning = false;
                return;
            }
            long nowElapsedMs = SystemClock.elapsedRealtime();
            if (signalTracker.shouldReportLost(nowElapsedMs)) {
                signalTracker.markLost();
                weakReported = false;
                signalCheckRunning = false;
                callback.onTargetLost();
                return;
            }
            if (!weakReported && signalTracker.shouldReportWeak(nowElapsedMs)) {
                signalTracker.markWeak();
                weakReported = true;
                callback.onTargetWeak();
            }
            handler.postDelayed(this, SIGNAL_CHECK_MS);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getScanRecord() == null) {
                return;
            }
            String advertisedName = result.getScanRecord().getDeviceName();
            if (advertisedName == null || advertisedName.trim().isEmpty()) {
                try {
                    advertisedName = result.getDevice().getName();
                } catch (SecurityException ignored) {
                    return;
                }
            }
            if (!VehicleBleMath.namesMatch(expectedName, advertisedName)) {
                return;
            }
            handler.post(() -> acceptReading(result.getRssi()));
        }

        @Override
        public void onScanFailed(int errorCode) {
            handler.post(() -> {
                stop();
                callback.onScanFailed();
            });
        }
    };

    VehicleBleScanner(Context context, Callback callback) {
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        supportsLe = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE
        );
        this.callback = callback;
    }

    boolean hasAdapter() {
        return adapter != null && supportsLe;
    }

    boolean isEnabled() {
        if (adapter == null) {
            return false;
        }
        try {
            return adapter.isEnabled();
        } catch (SecurityException exception) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    boolean start(String targetName) {
        stop();
        expectedName = targetName == null ? "" : targetName.trim();
        if (expectedName.isEmpty() || adapter == null) {
            return false;
        }
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null || !adapter.isEnabled()) {
                return false;
            }
            signalTracker.reset();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0L)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            return true;
        } catch (SecurityException | IllegalStateException exception) {
            scanner = null;
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    void stop() {
        handler.removeCallbacks(signalCheck);
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException ignored) {
                // The Bluetooth registration is gone either way.
            }
        }
        scanner = null;
        scanning = false;
        weakReported = false;
        signalCheckRunning = false;
        signalTracker.reset();
        expectedName = "";
    }

    private void acceptReading(int rssi) {
        if (!scanning) {
            return;
        }
        int event = signalTracker.accept(rssi, SystemClock.elapsedRealtime());
        boolean recoveredFromWeak = weakReported;
        if (event == VehicleBleSignalTracker.EVENT_NONE && !recoveredFromWeak) {
            return;
        }
        weakReported = false;
        if (!signalCheckRunning) {
            signalCheckRunning = true;
            handler.postDelayed(signalCheck, SIGNAL_CHECK_MS);
        }
        callback.onTargetSignal(
                signalTracker.lastRssi(),
                signalTracker.isBandStable()
        );
    }
}
