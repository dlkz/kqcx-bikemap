package com.kqcx.bikemap;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.osmdroid.util.GeoPoint;

import java.util.List;

/**
 * Background vehicle watch: refreshes the nearby list on a user-chosen interval
 * and warns when the list shrinks to five or fewer vehicles, or when the closest
 * vehicle is farther than 150 m away.
 */
public final class VehicleAlertService extends Service {
    private static final String TAG = "VehicleAlertService";

    static final String PREFS = "app_settings";
    static final String PREF_ENABLED = "vehicle_alert_enabled";
    static final String PREF_INTERVAL_SECONDS = "vehicle_alert_interval_seconds";
    static final int DEFAULT_INTERVAL_SECONDS = 60;
    static final int[] INTERVALS_SECONDS = {10, 30, 60, 180, 300};

    private static final String CHANNEL_ONGOING = "vehicle_alert_active_v1";
    private static final String CHANNEL_WARNING = "vehicle_alert_warning_v1";
    private static final int ONGOING_ID = 4301;
    private static final int WARNING_ID = 4302;
    private static final int OPEN_REQUEST_CODE = 4303;
    private static final int CHECK_REQUEST_CODE = 4304;
    private static final String ACTION_CHECK =
            "com.kqcx.bikemap.action.VEHICLE_ALERT_CHECK";
    private static final long BACKSTOP_MARGIN_MS = 5_000L;

    private static final int LOW_VEHICLE_COUNT = 5;
    private static final float FAR_DISTANCE_METERS = 150f;
    private static final long FIRST_CHECK_DELAY_MS = 1_500L;
    private static final long WARNING_COOLDOWN_MS = 5L * 60L * 1000L;
    private static final long FIX_TIMEOUT_MS = 8_000L;
    /**
     * 距离判定只接受这个时限内的定位：陈旧的“最近已知位置”会让距离偏出几百米，
     * 从而出现“附近明明有车却提示最近车辆 300 米”的误报。
     */
    private static final long MAX_FIX_AGE_MS = 60_000L;
    /** 精度差于此值的位置不用于距离判定（阈值是 150 米）。 */
    private static final float MAX_FIX_ACCURACY_METERS = 100f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkRunnable = this::runCheck;
    private BikeRepository repository;
    private LocationManager locationManager;
    private boolean checkInFlight;
    private boolean warningActive;
    private long lastWarningAtMs;

    /** 功能开关：只由高级设置里的「车辆预警」开关决定。 */
    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(PREF_ENABLED, false);
    }

    /**
     * 计时开关：由提醒页的车辆预警间隔决定，0 表示计时已关闭。
     * 与功能开关相互独立——计时关闭不会改动高级设置里的开关。
     */
    static boolean isTimerRunning(Context context) {
        return preferences(context)
                .getInt(PREF_INTERVAL_SECONDS, 0) > 0;
    }

    static int intervalSeconds(Context context) {
        int value = preferences(context)
                .getInt(PREF_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS);
        for (int candidate : INTERVALS_SECONDS) {
            if (candidate == value) {
                return value;
            }
        }
        return DEFAULT_INTERVAL_SECONDS;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 车辆预警通知权限、总开关和两个预警频道是否可用。 */
    static boolean hasNotificationAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return true;
        }
        for (String channelId : new String[]{CHANNEL_ONGOING, CHANNEL_WARNING}) {
            NotificationChannel channel = manager.getNotificationChannel(channelId);
            if (channel != null
                    && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return false;
            }
        }
        return true;
    }

    static void start(Context context) {
        context.startForegroundService(new Intent(context, VehicleAlertService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, VehicleAlertService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)
                || !isTimerRunning(this)
                || !hasNotificationAccess(this)
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean checkRequested = intent != null
                && ACTION_CHECK.equals(intent.getAction());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        ensureChannels(this);
        Notification notification = buildOngoingNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        ONGOING_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                );
            } else {
                startForeground(ONGOING_ID, notification);
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Vehicle alert could not become foreground", exception);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (checkRequested) {
            if (!checkInFlight) {
                runCheck();
            }
        } else if (!checkInFlight) {
            scheduleNext(FIRST_CHECK_DELAY_MS);
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new BikeRepository(this);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checkRunnable);
        cancelBackstop();
        cancelLocationRequest();
        if (repository != null) {
            repository.shutdown();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void scheduleNext(long delayMs) {
        handler.removeCallbacks(checkRunnable);
        long safeDelay = Math.max(1_000L, delayMs);
        handler.postDelayed(checkRunnable, safeDelay);
        scheduleBackstop(safeDelay + BACKSTOP_MARGIN_MS);
    }

    /**
     * Handler 负责正常运行时按间隔检查；AlarmManager 作为进程被回收或深度休眠
     * 时的唤醒兜底。能申请精确闹钟时使用精确唤醒，否则使用 allow-while-idle。
     */
    private void scheduleBackstop(long delayMs) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        Intent checkIntent = new Intent(this, VehicleAlertService.class)
                .setAction(ACTION_CHECK);
        PendingIntent operation = PendingIntent.getForegroundService(
                this,
                CHECK_REQUEST_CODE,
                checkIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(5_000L, delayMs);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        operation
                );
            } else {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        operation
                );
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Vehicle alert backstop alarm unavailable", exception);
            operation.cancel();
        }
    }

    private void cancelBackstop() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        Intent checkIntent = new Intent(this, VehicleAlertService.class)
                .setAction(ACTION_CHECK);
        PendingIntent operation = PendingIntent.getForegroundService(
                this,
                CHECK_REQUEST_CODE,
                checkIntent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (operation != null) {
            alarmManager.cancel(operation);
            operation.cancel();
        }
    }

    private void runCheck() {
        if (!isEnabled(this)
                || !isTimerRunning(this)
                || !hasNotificationAccess(this)) {
            stopSelf();
            return;
        }
        cancelBackstop();
        if (checkInFlight) {
            return;
        }
        checkInFlight = true;
        requestLocation(location -> {
            if (location == null) {
                Log.w(TAG, "Vehicle alert check skipped: no location fix");
                finishCheck();
                return;
            }
            // 前台和车辆接口均使用 GCJ-02；直接传 Android 的 WGS-84
            // 会造成约几百米的国内坐标偏移，导致车辆预警误报。
            GeoPoint queryPoint = CoordinateUtils.wgs84ToGcj02(
                    location.getLatitude(),
                    location.getLongitude()
            );
            repository.fetchNearby(
                    queryPoint.getLatitude(),
                    queryPoint.getLongitude(),
                    new BikeRepository.Callback() {
                        @Override
                        public void onSuccess(List<Bike> bikes, long fetchedAt) {
                            evaluate(bikes, location.getAccuracy());
                            finishCheck();
                        }

                        @Override
                        public void onError(String message) {
                            Log.w(TAG, "Vehicle alert query failed: " + message);
                            finishCheck();
                        }
                    }
            );
        });
    }

    private void finishCheck() {
        checkInFlight = false;
        if (!isEnabled(this) || !isTimerRunning(this)) {
            stopSelf();
            return;
        }
        scheduleNext(intervalSeconds(this) * 1000L);
    }

    private void evaluate(List<Bike> bikes, float locationAccuracy) {
        int count = bikes == null ? 0 : bikes.size();
        float nearest = Float.MAX_VALUE;
        if (bikes != null) {
            for (Bike bike : bikes) {
                if (bike.locationKnown && bike.distanceMeters < nearest) {
                    nearest = bike.distanceMeters;
                }
            }
        }
        if (count <= LOW_VEHICLE_COUNT) {
            warn(getString(R.string.vehicle_alert_warning_few, count));
            return;
        }
        if (nearest == Float.MAX_VALUE) {
            // Every listed vehicle is missing a position, so nothing can be
            // measured; say so instead of printing an absurd distance.
            warn(getString(R.string.vehicle_alert_warning_unknown));
            return;
        }
        // 只有“最近距离减去本次定位误差”后仍超过阈值，才认为确实偏远；
        // 否则几十到上百米的定位误差会把 100 多米误报成 300 米。
        float accuracy = Float.isNaN(locationAccuracy) ? 0f : Math.max(0f, locationAccuracy);
        if (nearest - accuracy > FAR_DISTANCE_METERS) {
            warn(getString(
                    R.string.vehicle_alert_warning_far,
                    Math.round(nearest)
            ));
            return;
        }
        warningActive = false;
    }

    private void warn(String message) {
        if (!hasNotificationAccess(this)) {
            stopSelf();
            return;
        }
        long now = System.currentTimeMillis();
        if (warningActive && now - lastWarningAtMs < WARNING_COOLDOWN_MS) {
            return;
        }
        warningActive = true;
        lastWarningAtMs = now;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                OPEN_REQUEST_CODE,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_WARNING)
                .setSmallIcon(R.drawable.ic_reminder_notification)
                .setContentTitle(getString(R.string.vehicle_alert_warning_title))
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setWhen(now)
                .setShowWhen(true)
                .build();
        manager.notify(WARNING_ID, notification);
    }

    private interface LocationCallback {
        void onLocation(Location location);
    }

    private LocationListener activeListener;
    private Runnable activeTimeout;

    @SuppressLint("MissingPermission")
    private void requestLocation(LocationCallback callback) {
        if (locationManager == null
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onLocation(null);
            return;
        }
        Location lastKnown = bestFreshLocation();
        try {
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (!isUsableLocation(location)) {
                        // 继续等待更可靠的位置，避免用陈旧/粗糙定位算错距离。
                        return;
                    }
                    cancelLocationRequest();
                    callback.onLocation(location);
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            activeListener = listener;
            locationManager.requestLocationUpdates(
                    preferredProvider(),
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
            );
            activeTimeout = () -> {
                cancelLocationRequest();
                Location fallback = bestFreshLocation();
                callback.onLocation(fallback != null ? fallback : lastKnown);
            };
            handler.postDelayed(activeTimeout, FIX_TIMEOUT_MS);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Location request unavailable", exception);
            callback.onLocation(lastKnown);
        }
    }

    private void cancelLocationRequest() {
        if (activeTimeout != null) {
            handler.removeCallbacks(activeTimeout);
            activeTimeout = null;
        }
        if (activeListener != null && locationManager != null) {
            try {
                locationManager.removeUpdates(activeListener);
            } catch (RuntimeException ignored) {
                // Provider already gone.
            }
            activeListener = null;
        }
    }

    /** GPS 优先，不可用时退回网络定位。 */
    private String preferredProvider() {
        try {
            if (locationManager != null
                    && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the network provider.
        }
        return LocationManager.NETWORK_PROVIDER;
    }

    /** 只接受新鲜且精度足够的位置，避免用陈旧或粗糙定位做距离判定。 */
    private static boolean isUsableLocation(Location location) {
        if (location == null) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - location.getTime();
        if (ageMs > MAX_FIX_AGE_MS) {
            return false;
        }
        return location.hasAccuracy() && location.getAccuracy() <= MAX_FIX_ACCURACY_METERS;
    }

    /** 系统中最近一次可用（新鲜、精度足够）的已知位置。 */
    @SuppressLint("MissingPermission")
    private Location bestFreshLocation() {
        if (locationManager == null
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        Location best = null;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
        }) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (isUsableLocation(candidate)
                        && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (RuntimeException ignored) {
                // Provider not present on this device.
            }
        }
        return best;
    }

    private Notification buildOngoingNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                OPEN_REQUEST_CODE + 1,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_reminder_notification)
                .setContentTitle(getString(R.string.vehicle_alert_ongoing_title))
                .setContentText(getString(
                        R.string.vehicle_alert_ongoing_text,
                        intervalLabel(this, intervalSeconds(this))
                ))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    static String intervalLabel(Context context, int seconds) {
        if (seconds < 60) {
            return context.getString(
                    R.string.vehicle_alert_interval_seconds_format,
                    seconds
            );
        }
        return context.getString(
                R.string.vehicle_alert_interval_minutes_format,
                seconds / 60
        );
    }

    static void ensureChannels(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel ongoing = new NotificationChannel(
                CHANNEL_ONGOING,
                context.getString(R.string.vehicle_alert_channel_ongoing),
                NotificationManager.IMPORTANCE_LOW
        );
        ongoing.setDescription(context.getString(
                R.string.vehicle_alert_channel_ongoing_description
        ));
        ongoing.setSound(null, null);
        ongoing.enableVibration(false);
        manager.createNotificationChannel(ongoing);

        NotificationChannel warning = new NotificationChannel(
                CHANNEL_WARNING,
                context.getString(R.string.vehicle_alert_channel_warning),
                NotificationManager.IMPORTANCE_HIGH
        );
        warning.setDescription(context.getString(
                R.string.vehicle_alert_channel_warning_description
        ));
        warning.enableVibration(true);
        manager.createNotificationChannel(warning);
    }
}
