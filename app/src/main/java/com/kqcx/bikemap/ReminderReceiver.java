package com.kqcx.bikemap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public final class ReminderReceiver extends BroadcastReceiver {
    private static final String TAG = "ReminderReceiver";

    static final String EXTRA_LEAD_MINUTES = "lead_minutes";
    static final String EXTRA_ALERT_MODE = "alert_mode";

    @Override
    public void onReceive(Context context, Intent intent) {
        DiagnosticLog.event(TAG, "alarm fired");
        holdCpuBriefly(context);

        int leadMinutes = intent.getIntExtra(
                EXTRA_LEAD_MINUTES,
                ReminderScheduler.DEFAULT_LEAD_MINUTES
        );
        String alertMode = intent.getStringExtra(EXTRA_ALERT_MODE);
        try {
            ReminderScheduler.deliverAlert(context, leadMinutes, alertMode);
        } catch (RuntimeException exception) {
            DiagnosticLog.error(TAG, "alert failed", exception);
        }
    }

    /**
     * Keeps the CPU awake for a few seconds so the alert can be delivered even
     * when the device is dozing. Released automatically.
     */
    private static void holdCpuBriefly(Context context) {
        PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        try {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "bikemap:reminder-receiver"
            );
            wakeLock.acquire(15_000L);
        } catch (RuntimeException exception) {
            DiagnosticLog.warn(TAG, "wake lock unavailable", exception);
        }
    }
}
