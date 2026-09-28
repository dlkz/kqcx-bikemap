package com.kqcx.bikemap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public final class ReminderForegroundService extends Service {
    private static final String TAG = "ReminderService";
    private static final String CHANNEL_ID = "free_time_active_v1";
    private static final int NOTIFICATION_ID = 4202;
    private static final int OPEN_REQUEST_CODE = 4203;

    private final Handler alertHandler = new Handler(Looper.getMainLooper());
    private final Runnable alertRunnable = this::fireAlert;
    private PowerManager.WakeLock wakeLock;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, ReminderForegroundService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ReminderForegroundService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ReminderScheduler.isActive(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        ensureChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        acquireWakeLock();
        scheduleAlert();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alertHandler.removeCallbacks(alertRunnable);
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Delivers the reminder from inside the already-running foreground service.
     * Some vendor builds defer background alarms until the app is opened again,
     * so this timer is the primary trigger and the exact alarm stays as a
     * backstop.
     */
    private void scheduleAlert() {
        alertHandler.removeCallbacks(alertRunnable);
        long triggerAt = ReminderScheduler.alertTriggerAt(this);
        if (triggerAt <= 0L) {
            stopSelf();
            return;
        }
        long delay = triggerAt - System.currentTimeMillis();
        if (delay <= 0L) {
            alertHandler.post(alertRunnable);
        } else {
            alertHandler.postDelayed(alertRunnable, delay);
        }
    }

    private void fireAlert() {
        try {
            ReminderScheduler.deliverAlert(
                    this,
                    ReminderScheduler.scheduledLeadMinutes(this),
                    ReminderScheduler.scheduledAlertMode(this)
            );
        } catch (RuntimeException exception) {
            Log.e(TAG, "Reminder timer could not deliver the alert", exception);
        }
        stopSelf();
    }

    private void acquireWakeLock() {
        PowerManager powerManager =
                (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "bikemap:reminder-timer"
            );
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            // Held only for the life of the user-started timer so the countdown
            // can be delivered while the device is dozing.
            wakeLock.acquire(ReminderScheduler.DURATION_MS + 60_000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                OPEN_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reminder_notification)
                .setContentTitle(getString(R.string.reminder_button_active))
                .setContentText(getString(
                        R.string.reminder_active_status,
                        ReminderScheduler.scheduledLeadMinutes(this)
                ))
                .setContentIntent(contentIntent)
                .setWhen(ReminderScheduler.scheduledEndAt(this))
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void ensureChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.reminder_foreground_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(
                R.string.reminder_foreground_channel_description
        ));
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }
}
