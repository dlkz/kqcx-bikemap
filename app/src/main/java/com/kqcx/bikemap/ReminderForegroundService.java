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
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * 提醒计时期间只负责显示通知栏倒计时。
 *
 * 到点投递由 {@link ReminderScheduler} 设置的精确闹钟
 * （{@link ReminderReceiver}）完成；即使本服务被系统回收，闹钟仍由系统触发。
 */
public final class ReminderForegroundService extends Service {
    private static final String CHANNEL_ID = "free_time_active_v1";
    private static final int NOTIFICATION_ID = 4202;
    private static final int OPEN_REQUEST_CODE = 4203;

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
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
