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
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.osmdroid.util.GeoPoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Background vehicle watch: refreshes the nearby list on a user-chosen interval
 * and warns when fewer than three available vehicles remain within 100 m.
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

    private static final float LOW_STOCK_RADIUS_METERS = 100f;
    private static final int LOW_STOCK_THRESHOLD = 3;
    private static final int LOW_STOCK_RECOVERY_COUNT = 4;
    private static final int MAX_NEARBY_RESULTS = 20;
    private static final float SUPPLEMENTAL_QUERY_OFFSET_METERS = 75f;
    private static final float SUPPLEMENTAL_CACHE_RADIUS_METERS = 25f;
    private static final long SUPPLEMENTAL_QUERY_MIN_INTERVAL_MS = 60_000L;
    private static final long FIRST_CHECK_DELAY_MS = 1_500L;
    private static final long MIN_NOTIFICATION_INTERVAL_MS = 15_000L;
    private static final long WARNING_COOLDOWN_MS = 60_000L;
    private static final long FIX_TIMEOUT_MS = 8_000L;
    /**
     * 低库存统计只接受这个时限内的定位：陈旧位置会让 100 米边界和车辆数量
     * 明显偏移，从而产生误报或漏报。
     */
    private static final long MAX_FIX_AGE_MS = 60_000L;
    /** 精度差于此值的位置不用于低库存统计（预警半径是 100 米）。 */
    private static final float MAX_FIX_ACCURACY_METERS = 50f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkRunnable = this::runCheck;
    private final Map<String, Bike> supplementalBikes = new LinkedHashMap<>();
    private BikeRepository repository;
    private LocationManager locationManager;
    private boolean checkInFlight;
    /** 已排期的下一次检查，用于避免重复 start 把检查间隔重置。 */
    private boolean checkScheduled;
    private AlertState alertState = AlertState.NORMAL;
    private AlertState lastNotifiedState;
    private String lastNotificationMessage;
    private long lastNotificationAtMs;
    private GeoPoint lastSupplementalQueryPoint;
    private VehicleStats lastSupplementalStats;
    private long lastSupplementalQueryAtMs;

    private enum AlertState {
        NORMAL,
        LOW,
        EMPTY
    }

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
            DiagnosticLog.warn(TAG, "Vehicle alert could not become foreground", exception);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (checkRequested) {
            if (!checkInFlight) {
                runCheck();
            }
        } else if (!checkInFlight && !checkScheduled) {
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
        checkScheduled = false;
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
        checkScheduled = true;
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
            DiagnosticLog.warn(TAG, "Vehicle alert backstop alarm unavailable", exception);
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
        checkScheduled = false;
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
                DiagnosticLog.warn(TAG, "Vehicle alert check skipped: no location fix");
                finishCheck();
                return;
            }
            // 前台和车辆接口均使用 GCJ-02；直接传 Android 的 WGS-84
            // 会造成约几百米的国内坐标偏移，导致车辆预警误报。
            GeoPoint queryPoint = CoordinateUtils.wgs84ToGcj02(
                    location.getLatitude(),
                    location.getLongitude()
            );
            float locationAccuracy = location.getAccuracy();
            repository.fetchNearby(
                    queryPoint.getLatitude(),
                    queryPoint.getLongitude(),
                    new BikeRepository.Callback() {
                        @Override
                        public void onSuccess(List<Bike> bikes, long fetchedAt) {
                            evaluateNearby(
                                    bikes,
                                    queryPoint,
                                    locationAccuracy,
                                    VehicleAlertService.this::finishCheck
                            );
                        }

                        @Override
                        public void onError(String message) {
                            DiagnosticLog.warn(TAG, "Vehicle alert query failed: " + message);
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

    private void evaluateNearby(
            List<Bike> bikes,
            GeoPoint queryPoint,
            float locationAccuracy,
            Runnable onComplete
    ) {
        float accuracy = Float.isNaN(locationAccuracy)
                ? Float.MAX_VALUE
                : Math.max(0f, locationAccuracy);
        if (accuracy > MAX_FIX_ACCURACY_METERS) {
            DiagnosticLog.detail(TAG, "check deferred accuracy=" + Math.round(accuracy));
            onComplete.run();
            return;
        }

        VehicleStats stats = statsFor(bikes, queryPoint);
        logStats("check", bikes == null ? 0 : bikes.size(), stats, accuracy);
        if (bikes != null
                && bikes.size() >= MAX_NEARBY_RESULTS
                && stats.nearbyAvailableCount < LOW_STOCK_THRESHOLD) {
            long now = SystemClock.elapsedRealtime();
            if (lastSupplementalStats != null
                    && lastSupplementalQueryPoint != null
                    && now - lastSupplementalQueryAtMs
                    < SUPPLEMENTAL_QUERY_MIN_INTERVAL_MS
                    && distanceMeters(lastSupplementalQueryPoint, queryPoint)
                    <= SUPPLEMENTAL_CACHE_RADIUS_METERS) {
                logStats("check cached", bikes.size(), lastSupplementalStats, accuracy);
                applyAlertState(lastSupplementalStats);
                onComplete.run();
                return;
            }
            DiagnosticLog.detail(TAG, "check full result; supplemental query required");
            fetchSupplementalNearby(
                    bikes,
                    queryPoint,
                    () -> {
                        VehicleStats mergedStats = statsFor(
                                supplementalBikes.values(),
                                queryPoint
                        );
                        logStats(
                                "check merged",
                                supplementalBikes.size(),
                                mergedStats,
                                accuracy
                        );
                        lastSupplementalQueryPoint = new GeoPoint(
                                queryPoint.getLatitude(),
                                queryPoint.getLongitude()
                        );
                        lastSupplementalStats = mergedStats;
                        lastSupplementalQueryAtMs = SystemClock.elapsedRealtime();
                        applyAlertState(mergedStats);
                        onComplete.run();
                    },
                    onComplete
            );
            return;
        }

        applyAlertState(stats);
        onComplete.run();
    }

    private void applyAlertState(VehicleStats stats) {
        AlertState previousState = alertState;
        AlertState nextState = alertState;
        if (stats.nearbyAvailableCount >= LOW_STOCK_RECOVERY_COUNT) {
            nextState = AlertState.NORMAL;
        } else if (stats.nearbyAvailableCount < LOW_STOCK_THRESHOLD) {
            nextState = stats.nearbyAvailableCount == 0
                    ? AlertState.EMPTY
                    : AlertState.LOW;
        }

        alertState = nextState;
        boolean stateChanged = nextState != previousState;
        if (nextState == AlertState.NORMAL) {
            if (stateChanged) {
                DiagnosticLog.event(TAG, "alert recovered count=" + stats.nearbyAvailableCount);
                NotificationManagerCompat.from(this).cancel(WARNING_ID);
            }
            lastNotifiedState = null;
            lastNotificationMessage = null;
            return;
        }

        // 阈值 3、恢复线 4：数量为 3 时保持上一状态，但撤掉已经过时的提醒。
        if (stats.nearbyAvailableCount >= LOW_STOCK_THRESHOLD
                && stats.nearbyAvailableCount < LOW_STOCK_RECOVERY_COUNT) {
            NotificationManagerCompat.from(this).cancel(WARNING_ID);
            lastNotifiedState = null;
            lastNotificationMessage = null;
            return;
        }

        boolean severityEscalation = nextState == AlertState.EMPTY
                && lastNotifiedState != AlertState.EMPTY;
        if (nextState == AlertState.EMPTY) {
            DiagnosticLog.event(TAG, "alert empty");
            warn(
                    getString(R.string.vehicle_alert_warning_empty),
                    nextState,
                    severityEscalation
            );
        } else {
            DiagnosticLog.event(TAG, "alert low count=" + stats.nearbyAvailableCount);
            warn(
                    getString(R.string.vehicle_alert_warning_few, stats.nearbyAvailableCount),
                    nextState,
                    severityEscalation
            );
        }
    }

    private void warn(
            String message,
            AlertState warningState,
            boolean severityEscalation
    ) {
        if (!hasNotificationAccess(this)) {
            stopSelf();
            return;
        }
        long now = System.currentTimeMillis();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (!severityEscalation) {
            if (lastNotificationAtMs > 0L
                    && now - lastNotificationAtMs < MIN_NOTIFICATION_INTERVAL_MS) {
                updateWarningNotificationSilently(manager, message, now, warningState);
                return;
            }
            if (lastNotifiedState == warningState
                    && lastNotificationAtMs > 0L
                    && now - lastNotificationAtMs < WARNING_COOLDOWN_MS) {
                updateWarningNotificationSilently(manager, message, now, warningState);
                return;
            }
        }
        lastNotificationAtMs = now;
        lastNotifiedState = warningState;
        lastNotificationMessage = message;
        postWarningNotification(manager, message, now, false);
    }

    private void updateWarningNotificationSilently(
            NotificationManager manager,
            String message,
            long now,
            AlertState warningState
    ) {
        if (lastNotifiedState != warningState
                || message.equals(lastNotificationMessage)
                || !hasActiveWarningNotification(manager)) {
            return;
        }
        lastNotificationMessage = message;
        postWarningNotification(manager, message, now, true);
    }

    private static boolean hasActiveWarningNotification(NotificationManager manager) {
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            if (notification.getId() == WARNING_ID) {
                return true;
            }
        }
        return false;
    }

    private void postWarningNotification(
            NotificationManager manager,
            String message,
            long now,
            boolean onlyAlertOnce
    ) {
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
                .setOnlyAlertOnce(onlyAlertOnce)
                .setWhen(now)
                .setShowWhen(true)
                .build();
        manager.notify(WARNING_ID, notification);
    }

    private void fetchSupplementalNearby(
            List<Bike> initialBikes,
            GeoPoint queryPoint,
            Runnable onSuccess,
            Runnable onFailure
    ) {
        supplementalBikes.clear();
        if (initialBikes != null) {
            for (Bike bike : initialBikes) {
                supplementalBikes.put(bike.number, bike);
            }
        }
        GeoPoint[] queryPoints = supplementalQueryPoints(queryPoint);
        fetchSupplementalAt(queryPoints, 0, onSuccess, onFailure);
    }

    private void fetchSupplementalAt(
            GeoPoint[] queryPoints,
            int index,
            Runnable onSuccess,
            Runnable onFailure
    ) {
        if (index >= queryPoints.length) {
            onSuccess.run();
            return;
        }
        GeoPoint queryPoint = queryPoints[index];
        repository.fetchNearby(
                queryPoint.getLatitude(),
                queryPoint.getLongitude(),
                new BikeRepository.Callback() {
                    @Override
                    public void onSuccess(List<Bike> bikes, long fetchedAt) {
                        if (bikes != null) {
                            for (Bike bike : bikes) {
                                supplementalBikes.put(bike.number, bike);
                            }
                        }
                        fetchSupplementalAt(
                                queryPoints,
                                index + 1,
                                onSuccess,
                                onFailure
                        );
                    }

                    @Override
                    public void onError(String message) {
                        DiagnosticLog.warn(
                                TAG,
                                "Vehicle alert supplemental query failed: " + message
                        );
                        onFailure.run();
                    }
                }
        );
    }

    private static GeoPoint[] supplementalQueryPoints(GeoPoint center) {
        double latitude = center.getLatitude();
        double longitude = center.getLongitude();
        double latitudeOffset = SUPPLEMENTAL_QUERY_OFFSET_METERS / 111_320.0;
        double longitudeOffset = SUPPLEMENTAL_QUERY_OFFSET_METERS
                / (111_320.0 * Math.cos(Math.toRadians(latitude)));
        return new GeoPoint[]{
                new GeoPoint(latitude + latitudeOffset, longitude),
                new GeoPoint(latitude - latitudeOffset, longitude),
                new GeoPoint(latitude, longitude + longitudeOffset),
                new GeoPoint(latitude, longitude - longitudeOffset)
        };
    }

    private static VehicleStats statsFor(Iterable<Bike> bikes, GeoPoint queryPoint) {
        int availableCount = 0;
        int nearbyAvailableCount = 0;
        float nearest = Float.MAX_VALUE;
        if (bikes != null) {
            for (Bike bike : bikes) {
                if (!bike.available || !bike.locationKnown) {
                    continue;
                }
                availableCount++;
                float distance = distanceMeters(queryPoint, bike);
                if (distance <= LOW_STOCK_RADIUS_METERS) {
                    nearbyAvailableCount++;
                }
                if (distance < nearest) {
                    nearest = distance;
                }
            }
        }
        return new VehicleStats(availableCount, nearbyAvailableCount, nearest);
    }

    private static float distanceMeters(GeoPoint queryPoint, Bike bike) {
        float[] result = new float[1];
        Location.distanceBetween(
                queryPoint.getLatitude(),
                queryPoint.getLongitude(),
                bike.latitude,
                bike.longitude,
                result
        );
        return result[0];
    }

    private static float distanceMeters(GeoPoint first, GeoPoint second) {
        float[] result = new float[1];
        Location.distanceBetween(
                first.getLatitude(),
                first.getLongitude(),
                second.getLatitude(),
                second.getLongitude(),
                result
        );
        return result[0];
    }

    private static void logStats(
            String prefix,
            int rawCount,
            VehicleStats stats,
            float accuracy
    ) {
        DiagnosticLog.detail(TAG, prefix
                + " raw=" + rawCount
                + " available=" + stats.availableCount
                + " within100=" + stats.nearbyAvailableCount
                + " nearest=" + (stats.nearest == Float.MAX_VALUE
                ? "n/a"
                : Math.round(stats.nearest))
                + " acc=" + Math.round(accuracy));
    }

    private static final class VehicleStats {
        final int availableCount;
        final int nearbyAvailableCount;
        final float nearest;

        VehicleStats(int availableCount, int nearbyAvailableCount, float nearest) {
            this.availableCount = availableCount;
            this.nearbyAvailableCount = nearbyAvailableCount;
            this.nearest = nearest;
        }
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
            DiagnosticLog.warn(TAG, "Location request unavailable", exception);
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
