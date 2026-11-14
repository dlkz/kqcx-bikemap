package com.kqcx.bikemap;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

final class VehicleFinderBleController {
    interface Host {
        VehicleFinderTargetState target();

        void onBleStateChanged();

        void onBleLog(String state);
    }

    private static final long DISCOVERY_TIMEOUT_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VehicleBleScanner scanner;
    private final VehicleBleFinderState state = new VehicleBleFinderState();
    private final Host host;
    private final Runnable discoveryTimeout = () -> stop(true, true);

    private final VehicleBleScanner.Callback callback = new VehicleBleScanner.Callback() {
        @Override
        public void onTargetSignal(int rssi, boolean bandStable) {
            boolean firstSignal = !state.hasTargetSignal();
            state.signal(rssi, bandStable);
            handler.removeCallbacks(discoveryTimeout);
            host.onBleLog(firstSignal ? "found" : "band");
            host.onBleStateChanged();
        }

        @Override
        public void onTargetWeak() {
            state.weak();
            host.onBleLog("weak");
            host.onBleStateChanged();
        }

        @Override
        public void onTargetLost() {
            state.lost();
            host.onBleLog("lost");
            host.onBleStateChanged();
        }

        @Override
        public void onScanFailed() {
            handler.removeCallbacks(discoveryTimeout);
            state.fail();
            host.onBleLog("failed");
            host.onBleStateChanged();
        }
    };

    VehicleFinderBleController(Context context, Host host) {
        this.host = host;
        scanner = new VehicleBleScanner(context, callback);
    }

    boolean hasAdapter() {
        return scanner.hasAdapter();
    }

    boolean isEnabled() {
        return scanner.isEnabled();
    }

    boolean start() {
        VehicleFinderTargetState target = host.target();
        if (target == null
                || !VehicleBleMath.supportsDirectMatch(
                target.number(),
                target.bluetoothName()
        )) {
            state.fail();
            host.onBleLog("failed");
            host.onBleStateChanged();
            return false;
        }
        state.start();
        if (!scanner.start(target.bluetoothName())) {
            state.fail();
            host.onBleLog("failed");
            host.onBleStateChanged();
            return false;
        }
        handler.removeCallbacks(discoveryTimeout);
        handler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS);
        host.onBleLog("start");
        host.onBleStateChanged();
        return true;
    }

    void stop(boolean timedOut, boolean notifyHost) {
        handler.removeCallbacks(discoveryTimeout);
        scanner.stop();
        if (timedOut) {
            state.timeout();
        } else {
            state.stop();
        }
        if (notifyHost) {
            host.onBleLog(timedOut ? "timeout" : "stop");
            host.onBleStateChanged();
        }
    }

    void destroy() {
        handler.removeCallbacksAndMessages(null);
        scanner.stop();
    }

    boolean isScanning() {
        return state.isScanning();
    }

    boolean isPrimary() {
        return state.isPrimary();
    }

    boolean hasTargetSignal() {
        return state.hasTargetSignal();
    }

    VehicleBleFinderState.Status status() {
        return state.status();
    }

    int band() {
        return state.band();
    }
}
