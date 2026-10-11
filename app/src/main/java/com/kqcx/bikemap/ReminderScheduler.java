package com.kqcx.bikemap;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

final class ReminderScheduler {
    private static final String TAG = "ReminderScheduler";
    static final long DURATION_MS = 15L * 60L * 1000L;
    static final int DEFAULT_LEAD_MINUTES = 3;
    static final String MODE_SOUND_VIBRATE = "sound_vibrate";
    static final String MODE_VIBRATE = "vibrate";
    static final String MODE_SILENT = "silent";

    private static final String ACTION_REMINDER =
            "com.kqcx.bikemap.action.FREE_TIME_REMINDER";
    private static final String PREFS_REMINDER = "reminder_state";
    private static final String PREFS_ALERT_STATE = "reminder_alert_state";
    private static final String PREF_ALERTED_END_AT = "alerted_end_at";
    private static final String PREF_START_AT = "start_at";
    private static final String PREF_END_AT = "end_at";
    private static final String PREF_LEAD_MINUTES = "lead_minutes";
    private static final String PREF_ALERT_MODE = "alert_mode";
    private static final int REQUEST_CODE = 4201;
    private static final int NOTIFICATION_ID = 4201;

    private static final String CHANNEL_SOUND_VIBRATE = "free_time_sound_vibrate_v2";
    private static final String CHANNEL_VIBRATE = "free_time_vibrate_v2";
    private static final String CHANNEL_SILENT = "free_time_silent_v2";

    private ReminderScheduler() {
    }

    static boolean schedule(
            Context context,
            long startAt,
            long endAt,
            int leadMinutes,
            String alertMode
    ) {
        long now = System.currentTimeMillis();
        if (endAt <= now) {
            return false;
        }
        if (!canScheduleExactAlarms(context)) {
            DiagnosticLog.warn(TAG, "Exact alarm permission unavailable; reminder not scheduled");
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLog.warn(TAG, "Notification permission unavailable; reminder not scheduled");
            return false;
        }

        ensureNotificationChannels(context);
        cancelAlarmOnly(context);

        long triggerAt = Math.max(
                now + 1_000L,
                endAt - leadMinutes * 60_000L
        );
        PendingIntent operation = buildPendingIntent(
                context,
                leadMinutes,
                alertMode,
                PendingIntent.FLAG_UPDATE_CURRENT
        );
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return false;
        }

        PendingIntent showIntent = buildAlarmClockShowIntent(context);
        try {
            alarmManager.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                    operation
            );
        } catch (SecurityException exception) {
            DiagnosticLog.error(TAG, "Exact alarm permission was revoked", exception);
            return false;
        }
        DiagnosticLog.event(TAG, "Reminder alarm clock scheduled at " + triggerAt);

        preferences(context)
                .edit()
                .putLong(PREF_START_AT, startAt)
                .putLong(PREF_END_AT, endAt)
                .putInt(PREF_LEAD_MINUTES, leadMinutes)
                .putString(PREF_ALERT_MODE, normalizeAlertMode(alertMode))
                .apply();
        try {
            ReminderForegroundService.start(context);
        } catch (Exception exception) {
            DiagnosticLog.warn(TAG, "Reminder foreground service could not start", exception);
        }
        return true;
    }

    static void cancel(Context context) {
        cancelAlarmOnly(context);
        preferences(context).edit().clear().apply();
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        ReminderForegroundService.stop(context);
    }

    static boolean isActive(Context context) {
        return preferences(context).getLong(PREF_END_AT, 0L)
                > System.currentTimeMillis();
    }

    static void clearTriggeredState(Context context) {
        preferences(context).edit().clear().apply();
        ReminderForegroundService.stop(context);
    }

    /** Allows the alert to fire again for the same schedule after an update. */
    static void resetAlertDelivered(Context context) {
        context.getSharedPreferences(PREFS_ALERT_STATE, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_ALERTED_END_AT)
                .apply();
    }

    /**
     * Single entry point for firing a reminder. The exact alarm is the only
     * trigger; the delivery guard still protects against a replayed broadcast
     * or a manual retry for the same schedule.
     */
    static synchronized void deliverAlert(
            Context context,
            int leadMinutes,
            String alertMode
    ) {
        long endAt = scheduledEndAt(context);
        if (endAt <= 0L) {
            return;
        }
        SharedPreferences alertState = context.getSharedPreferences(
                PREFS_ALERT_STATE,
                Context.MODE_PRIVATE
        );
        if (alertState.getLong(PREF_ALERTED_END_AT, -1L) == endAt) {
            DiagnosticLog.detail(TAG, "alert already delivered for this schedule");
            return;
        }
        DiagnosticLog.event(TAG, "deliver lead=" + leadMinutes
                + " mode=" + normalizeAlertMode(alertMode));
        if (!showNotification(context, leadMinutes, alertMode)) {
            // 通知不可用时保留提醒状态，用户下次打开应用后仍可重新投递，
            // 避免直接把本次提醒标记为已送达。
            DiagnosticLog.warn(TAG, "Reminder alert not delivered; keeping schedule for retry");
            return;
        }
        alertState.edit().putLong(PREF_ALERTED_END_AT, endAt).apply();
        DiagnosticLog.event(TAG, "delivered");
        showAlertScreen(context, leadMinutes, alertMode);
        clearTriggeredState(context);
    }

    static boolean rescheduleActive(Context context) {
        long startAt = scheduledStartAt(context);
        long endAt = scheduledEndAt(context);
        if (startAt <= 0L || endAt <= System.currentTimeMillis()) {
            cancel(context);
            return false;
        }
        boolean scheduled = schedule(
                context,
                startAt,
                endAt,
                scheduledLeadMinutes(context),
                scheduledAlertMode(context)
        );
        if (!scheduled) {
            // 权限被撤销时保留活动提醒状态，用户重新进入提醒页后
            // 会再次检测并引导授权，而不是静默清掉已设置的提醒。
            DiagnosticLog.warn(TAG, "Active reminder kept for permission recovery");
        }
        return scheduled;
    }

    static long remainingMs(Context context) {
        return Math.max(
                0L,
                preferences(context).getLong(PREF_END_AT, 0L)
                        - System.currentTimeMillis()
        );
    }

    static long scheduledEndAt(Context context) {
        return preferences(context).getLong(PREF_END_AT, 0L);
    }

    static long scheduledStartAt(Context context) {
        return preferences(context).getLong(PREF_START_AT, 0L);
    }

    static int scheduledLeadMinutes(Context context) {
        return preferences(context).getInt(
                PREF_LEAD_MINUTES,
                DEFAULT_LEAD_MINUTES
        );
    }

    static String scheduledAlertMode(Context context) {
        return normalizeAlertMode(
                preferences(context).getString(
                        PREF_ALERT_MODE,
                        MODE_SOUND_VIBRATE
                )
        );
    }

    static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarmManager != null && alarmManager.canScheduleExactAlarms();
    }

    static boolean showNotification(
            Context context,
            int leadMinutes,
            String alertMode
    ) {
        boolean notificationsGranted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        boolean notificationsEnabled = manager.areNotificationsEnabled();
        if (!notificationsGranted) {
            DiagnosticLog.warn(TAG, "Reminder alert skipped: notification permission missing");
            return false;
        }
        if (!notificationsEnabled) {
            DiagnosticLog.warn(TAG, "Reminder alert skipped: notifications disabled for this app");
            return false;
        }
        ensureNotificationChannels(context);
        String mode = normalizeAlertMode(alertMode);
        NotificationChannel channel = alertChannel(context, mode);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            DiagnosticLog.warn(TAG, "Reminder alert skipped: alert channel disabled");
            return false;
        }

        PendingIntent contentIntent = buildAlertActivityIntent(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context,
                channelIdForMode(mode)
        )
                .setSmallIcon(R.drawable.ic_reminder_notification)
                .setContentTitle(context.getString(R.string.reminder_notification_title))
                .setContentText(context.getString(
                        R.string.reminder_notification_content,
                        leadMinutes
                ))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        context.getString(
                                R.string.reminder_notification_content,
                                leadMinutes
                        )
                ))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
            DiagnosticLog.event(TAG, "Reminder notification posted, mode=" + mode);
            return true;
        } catch (RuntimeException exception) {
            DiagnosticLog.error(TAG, "Reminder notification failed", exception);
            return false;
        }
    }

    static void showAlertScreen(Context context, int leadMinutes, String alertMode) {
        if (MODE_SILENT.equals(normalizeAlertMode(alertMode))) {
            return;
        }
        if (isLockedOrScreenOff(context)) {
            // Locked or screen off: the notification alone is the alert, so no
            // prompt pops up abruptly once the user unlocks.
            DiagnosticLog.event(TAG, "Reminder alert screen skipped: locked or screen off");
            return;
        }
        // 只在应用前台时用应用内小方块提示；后台/其它界面一律只留系统通知，
        // 不再从后台拉起任何 Activity。
        MainActivity activity = MainActivity.foregroundInstance();

        if (activity == null) {
            DiagnosticLog.event(TAG, "Reminder alert square skipped: app not in foreground");
            return;
        }
        activity.showReminderAlertSquare(leadMinutes);
        DiagnosticLog.event(TAG, "Reminder alert square requested in app");
    }


    private static boolean isLockedOrScreenOff(Context context) {
        KeyguardManager keyguardManager =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            return true;
        }
        PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && !powerManager.isInteractive();
    }

    /** 点通知时打开应用主页（提醒小方块只在前台显示，这里不再拉起单独界面）。 */
    private static PendingIntent buildAlertActivityIntent(Context context) {
        Intent alertIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                context,
                REQUEST_CODE + 2,
                alertIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }


    private static PendingIntent buildAlarmClockShowIntent(Context context) {
        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                context,
                REQUEST_CODE + 1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent buildPendingIntent(
            Context context,
            int leadMinutes,
            String alertMode,
            int baseFlags
    ) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_REMINDER)
                .putExtra(ReminderReceiver.EXTRA_LEAD_MINUTES, leadMinutes)
                .putExtra(
                        ReminderReceiver.EXTRA_ALERT_MODE,
                        normalizeAlertMode(alertMode)
                );
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                baseFlags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void cancelAlarmOnly(Context context) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_REMINDER);
        PendingIntent operation = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (operation != null) {
            alarmManager.cancel(operation);
            operation.cancel();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_REMINDER, Context.MODE_PRIVATE);
    }

    private static String normalizeAlertMode(String alertMode) {
        if (MODE_VIBRATE.equals(alertMode) || MODE_SILENT.equals(alertMode)) {
            return alertMode;
        }
        return MODE_SOUND_VIBRATE;
    }

    private static String channelIdForMode(String alertMode) {
        if (MODE_VIBRATE.equals(alertMode)) {
            return CHANNEL_VIBRATE;
        }
        if (MODE_SILENT.equals(alertMode)) {
            return CHANNEL_SILENT;
        }
        return CHANNEL_SOUND_VIBRATE;
    }

    /**
     * True when the alert category has been weakened in system settings: the
     * category is off, its sound was removed, or its vibration was switched off.
     * Lockscreen and banner preferences cannot be read back reliably, so the UI
     * points the user at the whole notification page instead.
     */
    static boolean isAlertChannelWeakened(Context context, String alertMode) {
        NotificationChannel channel = alertChannel(context, alertMode);
        if (channel == null) {
            return false;
        }
        if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return true;
        }
        String mode = normalizeAlertMode(alertMode);
        if (MODE_SILENT.equals(mode)) {
            return false;
        }
        if (!channel.shouldVibrate()) {
            return true;
        }
        return MODE_SOUND_VIBRATE.equals(mode) && channel.getSound() == null;
    }

    private static NotificationChannel alertChannel(Context context, String alertMode) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return null;
        }
        return manager.getNotificationChannel(channelIdForMode(alertMode));
    }

    static void ensureNotificationChannels(Context context) {
        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build();

        NotificationChannel soundVibrate = new NotificationChannel(
                CHANNEL_SOUND_VIBRATE,
                context.getString(R.string.reminder_channel_sound_vibrate),
                NotificationManager.IMPORTANCE_HIGH
        );
        soundVibrate.setDescription(context.getString(
                R.string.reminder_channel_sound_vibrate_description
        ));
        soundVibrate.setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                audioAttributes
        );
        soundVibrate.enableVibration(true);
        soundVibrate.setVibrationPattern(new long[]{0L, 260L, 180L, 260L});
        soundVibrate.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel vibrate = new NotificationChannel(
                CHANNEL_VIBRATE,
                context.getString(R.string.reminder_channel_vibrate),
                NotificationManager.IMPORTANCE_HIGH
        );
        vibrate.setDescription(context.getString(
                R.string.reminder_channel_vibrate_description
        ));
        vibrate.setSound(null, null);
        vibrate.enableVibration(true);
        vibrate.setVibrationPattern(new long[]{0L, 260L, 180L, 260L});
        vibrate.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel silent = new NotificationChannel(
                CHANNEL_SILENT,
                context.getString(R.string.reminder_channel_silent),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        silent.setDescription(context.getString(
                R.string.reminder_channel_silent_description
        ));
        silent.setSound(null, null);
        silent.enableVibration(false);
        silent.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        manager.createNotificationChannel(soundVibrate);
        manager.createNotificationChannel(vibrate);
        manager.createNotificationChannel(silent);
    }
}
