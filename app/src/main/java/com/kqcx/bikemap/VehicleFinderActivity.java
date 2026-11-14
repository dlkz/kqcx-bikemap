package com.kqcx.bikemap;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.osmdroid.util.GeoPoint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Coarse vehicle finder based on the last reported vehicle coordinate.
 */
public final class VehicleFinderActivity extends BaseActivity
        implements LocationListener, VehicleFinderBleController.Host {
    private static final String EXTRA_NUMBER = "finder_number";
    private static final String EXTRA_LATITUDE = "finder_latitude";
    private static final String EXTRA_LONGITUDE = "finder_longitude";
    private static final String EXTRA_LAST_UPLOAD = "finder_last_upload";
    private static final String EXTRA_BLUETOOTH_NAME = "finder_bluetooth_name";
    private static final String EXTRA_SOURCE = "finder_source";
    private static final String EXTRA_SNAPSHOT_CAPTURE_TIME =
            "finder_snapshot_capture_time";
    private static final String EXTRA_CURRENT_LATITUDE = "finder_current_latitude";
    private static final String EXTRA_CURRENT_LONGITUDE = "finder_current_longitude";
    private static final String EXTRA_CURRENT_ACCURACY = "finder_current_accuracy";
    private static final String EXTRA_CURRENT_TIME = "finder_current_time";
    private static final String EXTRA_CURRENT_ELAPSED = "finder_current_elapsed";
    private static final String STATE_LOCATION_PERMISSION_REQUESTED =
            "finder_location_permission_requested";
    private static final String STATE_TARGET_LATITUDE = "finder_target_latitude";
    private static final String STATE_TARGET_LONGITUDE = "finder_target_longitude";
    private static final String STATE_TARGET_LAST_UPLOAD = "finder_target_last_upload";
    private static final String STATE_TARGET_SOURCE = "finder_target_source";
    private static final String STATE_TARGET_BLUETOOTH_NAME = "finder_target_bluetooth_name";
    private static final String STATE_BLE_PERMISSION_REQUESTED =
            "finder_ble_permission_requested";

    private static final int REQUEST_LOCATION = 5101;
    private static final int REQUEST_BLE_PERMISSIONS = 5102;
    private static final int ACTION_NONE = 0;
    private static final int ACTION_PERMISSION = 1;
    private static final int ACTION_LOCATION_SETTINGS = 2;
    private static final int ACTION_APP_SETTINGS = 3;
    private static final long LOCATION_MAX_AGE_MS = 120_000L;
    private static final long UI_REFRESH_MS = 1_000L;
    private static final long LOCATION_TIMESTAMP_TOLERANCE_MS = 1_000L;
    private static final long LOCATION_SIGNIFICANTLY_NEWER_MS = 10_000L;
    private static final long LOCATION_GPS_FRESH_WINDOW_MS = 15_000L;
    private static final long TARGET_AUTO_REFRESH_AGE_MS = 2L * 60L * 1000L;
    private static final TimeZone CHINA_TIME_ZONE = TimeZone.getTimeZone("Asia/Shanghai");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable visualUpdate = this::updateVisuals;
    private final Runnable locationReacquireTimeout = this::finishLocationReacquire;
    private final Runnable freshnessTick = new Runnable() {
        @Override
        public void run() {
            updateVisualsNow();
            handler.postDelayed(this, UI_REFRESH_MS);
        }
    };
    private LocationManager locationManager;
    private BikeRepository repository;
    private VehicleFinderBleController bleController;
    private VehicleHeadingTracker headingTracker;
    private FinderSnapshotStore finderSnapshotStore;
    private Location currentLocation;
    private Location latestObservedLocation;
    private Location pendingRefreshLocation;
    private TextView tvDistance;
    private TextView tvDirection;
    private TextView tvFreshness;
    private TextView tvSource;
    private TextView tvHint;
    private TextView tvBleStatus;
    private ImageView imgArrow;
    private Button btnBleAction;
    private Button btnAction;
    private ImageButton btnFinderRefresh;
    private VehicleFinderRadarView radarView;
    private VehicleFinderTargetState target;
    private float heldBearingDegrees;
    private float heldBearingDistanceMeters;
    private boolean hasHeldBearing;
    private boolean trackingLocation;
    private boolean gpsProviderUnavailable;
    private boolean networkProviderUnavailable;
    private boolean reacquiringLocation;
    private boolean refreshingTarget;
    private boolean lastTargetRefreshMissed;
    private boolean entryRefreshEvaluated;
    private boolean locationPermissionRequested;
    private boolean blePermissionRequested;
    private int accuracyQuality = VehicleFinderMath.ACCURACY_UNKNOWN;
    private int currentAction = ACTION_NONE;
    private long lastVisualUpdateMs;
    private long locationReacquireStartedElapsedMs;
    private long locationReacquireStartedElapsedNanos;
    private long locationReacquireStartedWallMs;
    private long snapshotCapturedAtMs;
    private String lastLocationDiagnostic;
    private String lastDirectionDiagnostic;

    public static Intent createIntent(Context context, Bike bike, Location currentLocation) {
        return createIntent(context, bike, currentLocation, 0L);
    }

    static Intent createIntent(
            Context context,
            Bike bike,
            Location currentLocation,
            long snapshotCapturedAtMs
    ) {
        return createIntent(
                context,
                bike.number,
                bike.latitude,
                bike.longitude,
                bike.lastUploadAtMs,
                bike.bluetoothName,
                VehicleFinderTargetState.Source.ESTIMATED,
                currentLocation,
                snapshotCapturedAtMs
        );
    }

    public static Intent createSnapshotIntent(
            Context context,
            FinderSnapshotStore.Snapshot snapshot,
            Location currentLocation
    ) {
        return createIntent(
                context,
                snapshot.number,
                snapshot.latitude,
                snapshot.longitude,
                snapshot.lastUploadAtMs,
                snapshot.bluetoothName,
                VehicleFinderTargetState.Source.SNAPSHOT,
                currentLocation,
                snapshot.capturedAtMs
        );
    }

    private static Intent createIntent(
            Context context,
            String number,
            double latitude,
            double longitude,
            long lastUploadAtMs,
            String bluetoothName,
            VehicleFinderTargetState.Source source,
            Location currentLocation,
            long snapshotCapturedAtMs
    ) {
        Intent intent = new Intent(context, VehicleFinderActivity.class)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_LATITUDE, latitude)
                .putExtra(EXTRA_LONGITUDE, longitude)
                .putExtra(EXTRA_LAST_UPLOAD, lastUploadAtMs)
                .putExtra(EXTRA_BLUETOOTH_NAME, bluetoothName)
                .putExtra(EXTRA_SOURCE, source.name())
                .putExtra(EXTRA_SNAPSHOT_CAPTURE_TIME, snapshotCapturedAtMs);
        if (currentLocation != null) {
            intent.putExtra(EXTRA_CURRENT_LATITUDE, currentLocation.getLatitude())
                    .putExtra(EXTRA_CURRENT_LONGITUDE, currentLocation.getLongitude())
                    .putExtra(EXTRA_CURRENT_ACCURACY, currentLocation.getAccuracy())
                    .putExtra(EXTRA_CURRENT_TIME, currentLocation.getTime())
                    .putExtra(EXTRA_CURRENT_ELAPSED, currentLocation.getElapsedRealtimeNanos());
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            locationPermissionRequested = savedInstanceState.getBoolean(
                    STATE_LOCATION_PERMISSION_REQUESTED,
                    false
            );
            blePermissionRequested = savedInstanceState.getBoolean(
                    STATE_BLE_PERMISSION_REQUESTED,
                    false
            );
        }
        if (!readTarget()) {
            Toast.makeText(this, R.string.finder_target_invalid, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        restoreTargetState(savedInstanceState);

        setContentView(R.layout.activity_vehicle_finder);
        bindViews();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        headingTracker = new VehicleHeadingTracker(this, this::onHeadingChanged);
        repository = new BikeRepository(this);
        finderSnapshotStore = new FinderSnapshotStore(this);
        bleController = new VehicleFinderBleController(this, this);
        applyInitialLocation();
        ((TextView) findViewById(R.id.tvFinderVehicleNumber)).setText(getString(
                R.string.finder_vehicle_title,
                target.number()
        ));
        findViewById(R.id.btnFinderBack).setOnClickListener(view -> finish());
        btnFinderRefresh.setOnClickListener(view -> refreshPositions());
        btnAction.setOnClickListener(view -> handleAction());
        btnBleAction.setOnClickListener(view -> handleBleAction());
        applySystemBarInsets();
        updateTargetSource();
        updateRefreshButtonState();
        updateBlePanel();
        updateFreshness();
        updateVisualsNow();
    }

    private boolean readTarget() {
        Intent intent = getIntent();
        snapshotCapturedAtMs = intent.getLongExtra(
                EXTRA_SNAPSHOT_CAPTURE_TIME,
                0L
        );
        VehicleFinderTargetState.Source source =
                VehicleFinderTargetState.Source.ESTIMATED;
        String sourceName = intent.getStringExtra(EXTRA_SOURCE);
        if (sourceName != null) {
            try {
                source = VehicleFinderTargetState.Source.valueOf(sourceName);
            } catch (IllegalArgumentException ignored) {
                source = VehicleFinderTargetState.Source.ESTIMATED;
            }
        }
        target = VehicleFinderTargetState.restore(
                intent.getStringExtra(EXTRA_NUMBER),
                intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN),
                intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN),
                intent.getLongExtra(EXTRA_LAST_UPLOAD, 0L),
                source,
                intent.getStringExtra(EXTRA_BLUETOOTH_NAME)
        );
        return target != null;
    }

    private void bindViews() {
        tvDistance = findViewById(R.id.tvFinderDistance);
        tvDirection = findViewById(R.id.tvFinderDirection);
        tvFreshness = findViewById(R.id.tvFinderFreshness);
        tvSource = findViewById(R.id.tvFinderSource);
        tvHint = findViewById(R.id.tvFinderHint);
        tvBleStatus = findViewById(R.id.tvFinderBleStatus);
        imgArrow = findViewById(R.id.imgFinderArrow);
        btnBleAction = findViewById(R.id.btnFinderBleAction);
        btnAction = findViewById(R.id.btnFinderAction);
        btnFinderRefresh = findViewById(R.id.btnFinderRefresh);
        radarView = findViewById(R.id.vehicleFinderRadar);
    }

    private void applyInitialLocation() {
        Intent intent = getIntent();
        if (!intent.hasExtra(EXTRA_CURRENT_LATITUDE)
                || !intent.hasExtra(EXTRA_CURRENT_LONGITUDE)) {
            return;
        }
        Location location = new Location("initial");
        location.setLatitude(intent.getDoubleExtra(EXTRA_CURRENT_LATITUDE, 0d));
        location.setLongitude(intent.getDoubleExtra(EXTRA_CURRENT_LONGITUDE, 0d));
        location.setAccuracy(intent.getFloatExtra(EXTRA_CURRENT_ACCURACY, 0f));
        location.setTime(intent.getLongExtra(
                EXTRA_CURRENT_TIME,
                System.currentTimeMillis()
        ));
        location.setElapsedRealtimeNanos(intent.getLongExtra(EXTRA_CURRENT_ELAPSED, 0L));
        if (isFresh(location)) {
            currentLocation = location;
            latestObservedLocation = new Location(location);
        }
    }

    private void handleAction() {
        if (currentAction == ACTION_PERMISSION) {
            locationPermissionRequested = true;
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQUEST_LOCATION
            );
        } else if (currentAction == ACTION_LOCATION_SETTINGS) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else if (currentAction == ACTION_APP_SETTINGS) {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            ));
        }
    }

    private void handleBleAction() {
        if (target == null || VehicleBleMath.isTwoSeries(target.number())) {
            return;
        }
        if (!VehicleBleMath.supportsDirectMatch(
                target.number(),
                target.bluetoothName()
        )) {
            if (target.bluetoothName().isEmpty()) {
                refreshPositions();
            }
            return;
        }
        if (!hasBlePermissions()) {
            if (shouldOpenSettingsForBle()) {
                startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())
                ));
            } else {
                blePermissionRequested = true;
                requestPermissions(
                        requiredBlePermissions().toArray(new String[0]),
                        REQUEST_BLE_PERMISSIONS
                );
            }
            return;
        }
        if (bleController == null || !bleController.hasAdapter()) {
            updateBlePanel();
            return;
        }
        if (!isLocationEnabled()) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        if (!bleController.isEnabled()) {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        if (bleController.isScanning()) {
            stopBleScan(false);
        } else {
            startBleScan();
        }
    }

    private void startBleScan() {
        if (bleController != null) {
            bleController.start();
        }
    }

    private void stopBleScan(boolean timedOut) {
        if (bleController != null) {
            bleController.stop(timedOut, true);
        }
    }

    private boolean hasBlePermissions() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || (hasPermission(Manifest.permission.BLUETOOTH_SCAN)
                && hasPermission(Manifest.permission.BLUETOOTH_CONNECT));
    }

    private ArrayList<String> requiredBlePermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return permissions;
    }

    private boolean shouldOpenSettingsForBle() {
        if (!blePermissionRequested) {
            return false;
        }
        for (String permission : requiredBlePermissions()) {
            if (!hasPermission(permission)
                    && !shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return false;
    }

    private void updateBlePanel() {
        if (tvBleStatus == null || btnBleAction == null || target == null) {
            return;
        }
        if (VehicleBleMath.isTwoSeries(target.number())) {
            showBleState(
                    R.string.finder_ble_unsupported_two_series,
                    View.GONE,
                    R.string.finder_ble_start
            );
            return;
        }
        if (!VehicleBleMath.supportsDirectMatch(
                target.number(),
                target.bluetoothName()
        )) {
            if (target.bluetoothName().isEmpty()) {
                boolean refreshing = refreshingTarget;
                showBleState(
                        refreshing
                                ? R.string.finder_ble_identity_refreshing
                                : R.string.finder_ble_identity_unavailable,
                        refreshing ? View.GONE : View.VISIBLE,
                        R.string.finder_ble_refresh_identity
                );
            } else {
                showBleState(
                        R.string.finder_ble_unsupported_identity,
                        View.GONE,
                        R.string.finder_ble_start
                );
            }
            return;
        }
        if (bleController == null || !bleController.hasAdapter()) {
            showBleState(
                    R.string.finder_ble_unsupported_device,
                    View.GONE,
                    R.string.finder_ble_start
            );
            return;
        }
        if (!hasBlePermissions()) {
            showBleState(
                    R.string.finder_ble_permission_required,
                    View.VISIBLE,
                    shouldOpenSettingsForBle()
                            ? R.string.finder_ble_open_app_settings
                            : R.string.finder_ble_allow_permission
            );
            return;
        }
        if (!isLocationEnabled()) {
            showBleState(
                    R.string.finder_ble_location_off,
                    View.VISIBLE,
                    R.string.finder_open_location_settings
            );
            return;
        }
        if (!bleController.isEnabled()) {
            showBleState(
                    R.string.finder_ble_bluetooth_off,
                    View.VISIBLE,
                    R.string.finder_ble_open_bluetooth_settings
            );
            return;
        }
        switch (bleController.status()) {
            case SCANNING:
                showBleState(
                        R.string.finder_ble_scanning,
                        View.VISIBLE,
                        R.string.finder_ble_stop
                );
                return;
            case FOUND:
                showBleState(
                        bleBandStatusRes(),
                        View.VISIBLE,
                        R.string.finder_ble_stop
                );
                return;
            case WEAK:
                showBleState(
                        R.string.finder_ble_weak,
                        View.VISIBLE,
                        R.string.finder_ble_stop
                );
                return;
            case LOST:
                showBleState(
                        R.string.finder_ble_lost,
                        View.VISIBLE,
                        R.string.finder_ble_stop
                );
                return;
            case FAILED:
                showBleState(
                        R.string.finder_ble_failed,
                        View.VISIBLE,
                        R.string.finder_ble_retry
                );
                return;
            case TIMED_OUT:
                showBleState(
                        R.string.finder_ble_timeout,
                        View.VISIBLE,
                        R.string.finder_ble_retry
                );
                return;
            case STOPPED:
                showBleState(
                        R.string.finder_ble_stopped,
                        View.VISIBLE,
                        R.string.finder_ble_start
                );
                return;
            case READY:
            default:
                showBleState(
                        R.string.finder_ble_ready,
                        View.VISIBLE,
                        R.string.finder_ble_start
                );
        }
    }

    private void showBleState(int statusRes, int actionVisibility, int actionRes) {
        tvBleStatus.setText(statusRes);
        btnBleAction.setVisibility(actionVisibility);
        if (actionVisibility == View.VISIBLE) {
            btnBleAction.setText(actionRes);
        }
    }

    private int bleBandStatusRes() {
        if (bleController.band() == VehicleBleMath.BAND_VERY_NEAR) {
            return R.string.finder_ble_found_very_near;
        }
        if (bleController.band() == VehicleBleMath.BAND_NEAR) {
            return R.string.finder_ble_found_near;
        }
        return R.string.finder_ble_found_far;
    }

    private void refreshPositions() {
        if (refreshingTarget
                || reacquiringLocation
                || repository == null
                || !target.number().matches("\\d{9}")) {
            return;
        }
        if (bleController.isScanning()) {
            stopBleScan(false);
        }
        GeoPoint queryPoint = currentLocation == null
                ? new GeoPoint(target.latitude(), target.longitude())
                : CoordinateUtils.wgs84ToGcj02(
                        currentLocation.getLatitude(),
                        currentLocation.getLongitude()
                );
        refreshingTarget = true;
        lastTargetRefreshMissed = false;
        updateBlePanel();
        if (hasLocationPermission() && isLocationEnabled()) {
            beginLocationReacquire();
        }
        updateRefreshButtonState();
        tvFreshness.setText(R.string.finder_refreshing_vehicle);
        logTargetRefresh("request");
        repository.queryOneCar(
                target.number(),
                queryPoint.getLatitude(),
                queryPoint.getLongitude(),
                new BikeRepository.BikeCallback() {
                    @Override
                    public void onSuccess(Bike bike) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (bike == null || !bike.locationKnown) {
                            lastTargetRefreshMissed = true;
                            finishTargetRefresh();
                            logTargetRefresh("miss");
                            updateBlePanel();
                            Toast.makeText(
                                    VehicleFinderActivity.this,
                                    R.string.finder_refresh_miss,
                                    Toast.LENGTH_SHORT
                            ).show();
                            return;
                        }
                        boolean applied = target.applyRefresh(bike);
                        updateFinderSnapshot(bike);
                        updateBlePanel();
                        if (!applied) {
                            lastTargetRefreshMissed = true;
                            finishTargetRefresh();
                            logTargetRefresh("unchanged");
                            Toast.makeText(
                                    VehicleFinderActivity.this,
                                    R.string.finder_refresh_miss,
                                    Toast.LENGTH_SHORT
                            ).show();
                            return;
                        }
                        lastTargetRefreshMissed = false;
                        hasHeldBearing = false;
                        finishTargetRefresh();
                        logTargetRefresh("hit");
                        updateTargetSource();
                        updateFreshness();
                        updateVisualsNow();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        lastTargetRefreshMissed = true;
                        finishTargetRefresh();
                        logTargetRefresh("error");
                        updateBlePanel();
                        Toast.makeText(
                                VehicleFinderActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show();
                    }

                    @Override
                    public void onUnavailable(int errorCode, String message) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        lastTargetRefreshMissed = true;
                        finishTargetRefresh();
                        logTargetRefresh("unavailable");
                        updateBlePanel();
                        updateVisualsNow();
                    }
                }
        );
    }

    private void logTargetRefresh(String result) {
        DiagnosticLog.detail("FinderLoc", "targetRefresh=" + result);
    }

    private void beginLocationReacquire() {
        handler.removeCallbacks(locationReacquireTimeout);
        reacquiringLocation = true;
        pendingRefreshLocation = null;
        locationReacquireStartedElapsedMs = SystemClock.elapsedRealtime();
        locationReacquireStartedElapsedNanos = SystemClock.elapsedRealtimeNanos();
        locationReacquireStartedWallMs = System.currentTimeMillis();
        stopLocationTracking();
        startLocationTracking(false);
        scheduleLocationReacquireTimeout();
        DiagnosticLog.detail("FinderLoc", "refresh start");
        updateRefreshButtonState();
        updateVisualsNow();
    }

    private void scheduleLocationReacquireTimeout() {
        if (!reacquiringLocation) {
            return;
        }
        long elapsedMs = SystemClock.elapsedRealtime() - locationReacquireStartedElapsedMs;
        long remainingMs = Math.max(
                0L,
                VehicleFinderMath.LOCATION_REACQUIRE_MAX_WAIT_MS - elapsedMs
        );
        handler.removeCallbacks(locationReacquireTimeout);
        handler.postDelayed(locationReacquireTimeout, remainingMs);
    }

    private void finishLocationReacquire() {
        if (!reacquiringLocation) {
            return;
        }
        Location fallback = pendingRefreshLocation != null
                ? pendingRefreshLocation
                : currentLocation;
        completeLocationReacquire(fallback);
    }

    private void completeLocationReacquire(Location location) {
        handler.removeCallbacks(locationReacquireTimeout);
        if (location != null) {
            currentLocation = new Location(location);
            latestObservedLocation = new Location(location);
        }
        pendingRefreshLocation = null;
        reacquiringLocation = false;
        accuracyQuality = VehicleFinderMath.ACCURACY_UNKNOWN;
        DiagnosticLog.detail("FinderLoc", "refresh complete");
        updateRefreshButtonState();
        updateVisualsNow();
    }

    private void updateRefreshButtonState() {
        if (btnFinderRefresh != null) {
            btnFinderRefresh.setEnabled(!refreshingTarget && !reacquiringLocation);
        }
    }

    private void finishTargetRefresh() {
        refreshingTarget = false;
        updateRefreshButtonState();
        updateFreshness();
    }

    private boolean shouldAutoRefreshTarget() {
        if (target == null) {
            return false;
        }
        if (target.source() == VehicleFinderTargetState.Source.SNAPSHOT) {
            return false;
        }
        boolean identityMissing = !VehicleBleMath.isTwoSeries(target.number())
                && target.bluetoothName().isEmpty();
        return identityMissing || target.needsRefresh(
                System.currentTimeMillis(),
                TARGET_AUTO_REFRESH_AGE_MS
        );
    }

    private void updateFinderSnapshot(Bike bike) {
        if (finderSnapshotStore == null
                || snapshotCapturedAtMs <= 0L
                || bike == null) {
            return;
        }
        finderSnapshotStore.updateFromBike(
                target.number(),
                snapshotCapturedAtMs,
                bike
        );
    }

    private boolean shouldReacquireCurrentLocation() {
        return hasLocationPermission()
                && isLocationEnabled()
                && (currentLocation == null
                || !isFresh(currentLocation, VehicleFinderMath.DIRECTION_MAX_AGE_MS));
    }

    private void restoreTargetState(Bundle savedState) {
        if (savedState == null || !savedState.containsKey(STATE_TARGET_SOURCE)) {
            return;
        }
        VehicleFinderTargetState.Source source;
        try {
            source = VehicleFinderTargetState.Source.valueOf(
                    savedState.getString(STATE_TARGET_SOURCE)
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            return;
        }
        VehicleFinderTargetState restored = VehicleFinderTargetState.restore(
                target.number(),
                savedState.getDouble(STATE_TARGET_LATITUDE, target.latitude()),
                savedState.getDouble(STATE_TARGET_LONGITUDE, target.longitude()),
                savedState.getLong(STATE_TARGET_LAST_UPLOAD, target.lastUploadAtMs()),
                source,
                savedState.getString(
                        STATE_TARGET_BLUETOOTH_NAME,
                        target.bluetoothName()
                )
        );
        if (restored != null) {
            target = restored;
        }
    }

    private void updateTargetSource() {
        if (tvSource == null || target == null) {
            return;
        }
        int sourceRes;
        if (target.source() == VehicleFinderTargetState.Source.REFRESHED) {
            sourceRes = R.string.finder_source_refreshed;
        } else if (target.source() == VehicleFinderTargetState.Source.SNAPSHOT) {
            sourceRes = R.string.finder_source_snapshot;
        } else {
            sourceRes = R.string.finder_source_estimated;
        }
        tvSource.setText(sourceRes);
    }

    private void startLocationTracking() {
        startLocationTracking(true);
    }

    private void startLocationTracking(boolean seedLastKnown) {
        if (locationManager == null) {
            return;
        }
        if (!hasLocationPermission()) {
            showLocationPermissionAction();
            return;
        }
        if (!isLocationEnabled()) {
            setHint(getString(R.string.finder_location_off_hint));
            showAction(
                    ACTION_LOCATION_SETTINGS,
                    R.string.finder_open_location_settings
            );
            return;
        }
        hideAction();
        if (seedLastKnown) {
            seedFromLastKnown();
        }
        if (trackingLocation) {
            return;
        }

        boolean hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean registered = false;
        try {
            if (hasFine) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2_000L,
                        2f,
                        this,
                        Looper.getMainLooper()
                );
                registered = true;
            }
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5_000L,
                    10f,
                    this,
                    Looper.getMainLooper()
            );
            registered = true;
        } catch (IllegalArgumentException | SecurityException exception) {
            // Another provider may still have registered successfully.
        }
        trackingLocation = registered;
        if (!registered) {
            currentAction = ACTION_NONE;
            btnAction.setVisibility(View.GONE);
            setHint(getString(R.string.finder_location_start_failed));
        }
        scheduleVisualUpdate();
    }

    private void showLocationPermissionAction() {
        boolean openSettings = locationPermissionRequested
                && !shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_FINE_LOCATION
        )
                && !shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_COARSE_LOCATION
        );
        setHint(getString(R.string.finder_permission_hint));
        showAction(
                openSettings ? ACTION_APP_SETTINGS : ACTION_PERMISSION,
                openSettings
                        ? R.string.finder_open_app_settings
                        : R.string.finder_allow_location
        );
    }

    private void stopLocationTracking() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException exception) {
                // Permission may already be gone.
            }
        }
        trackingLocation = false;
    }

    private void seedFromLastKnown() {
        if (locationManager == null || !hasLocationPermission()) {
            return;
        }
        if (currentLocation != null && isFresh(currentLocation)) {
            return;
        }
        Location best = null;
        String[] providers = {
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
        };
        for (String provider : providers) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate == null || !isFresh(candidate)) {
                    continue;
                }
                if (best == null || isBetterLocation(candidate, best)) {
                    best = candidate;
                }
            } catch (IllegalArgumentException | SecurityException exception) {
                // Provider unavailable.
            }
        }
        if (best != null) {
            useLocation(best);
        }
    }

    private void useLocation(Location location) {
        if (!isFresh(location)) {
            logLocationDecision("stale", location);
            return;
        }
        if (reacquiringLocation && !isLocationFromCurrentReacquire(location)) {
            logLocationDecision("reacquire-filtered", location);
            return;
        }
        if (latestObservedLocation != null
                && isOlderLocation(location, latestObservedLocation)) {
            logLocationDecision("out-of-order", location);
            return;
        }
        latestObservedLocation = new Location(location);
        if (reacquiringLocation) {
            handleReacquiredLocation(location);
            logLocationDecision("reacquire", location);
            return;
        }
        boolean accepted = currentLocation == null || shouldAcceptLocation(location);
        if (accepted) {
            currentLocation = new Location(location);
        }
        logLocationDecision(accepted ? "accepted" : "rejected", location);
        scheduleVisualUpdate();
    }

    private void handleReacquiredLocation(Location location) {
        float accuracyMeters = location.hasAccuracy() ? location.getAccuracy() : 0f;
        boolean gpsProvider = LocationManager.GPS_PROVIDER.equals(location.getProvider());
        if (VehicleFinderMath.canCommitReacquiredLocation(gpsProvider, accuracyMeters)) {
            completeLocationReacquire(location);
            return;
        }
        if (pendingRefreshLocation == null
                || isMoreAccurate(location, pendingRefreshLocation)) {
            pendingRefreshLocation = new Location(location);
        }
        scheduleVisualUpdate();
    }

    private boolean isMoreAccurate(Location candidate, Location current) {
        return candidate.hasAccuracy()
                && (!current.hasAccuracy()
                || candidate.getAccuracy() < current.getAccuracy());
    }

    private boolean shouldAcceptLocation(Location candidate) {
        if (isOlderLocation(candidate, currentLocation)) {
            return false;
        }
        boolean significantlyNewer = isSignificantlyNewer(candidate);
        return LocationAcceptancePolicy.shouldAccept(
                LocationManager.NETWORK_PROVIDER.equals(candidate.getProvider()),
                LocationManager.GPS_PROVIDER.equals(currentLocation.getProvider()),
                isFresh(currentLocation, LOCATION_GPS_FRESH_WINDOW_MS),
                candidate.hasAccuracy(),
                candidate.hasAccuracy() ? candidate.getAccuracy() : 0f,
                currentLocation.hasAccuracy(),
                currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0f,
                significantlyNewer
        );
    }

    private boolean isOlderLocation(Location candidate, Location current) {
        if (current == null) {
            return false;
        }
        return LocationAcceptancePolicy.isOlder(
                candidate.getElapsedRealtimeNanos(),
                current.getElapsedRealtimeNanos(),
                candidate.getTime(),
                current.getTime(),
                LOCATION_TIMESTAMP_TOLERANCE_MS
        );
    }

    private boolean isLocationFromCurrentReacquire(Location candidate) {
        return VehicleFinderMath.isAfterReacquireStart(
                candidate.getElapsedRealtimeNanos(),
                locationReacquireStartedElapsedNanos,
                candidate.getTime(),
                locationReacquireStartedWallMs
        );
    }

    private boolean isBetterLocation(Location candidate, Location current) {
        long candidateTime = locationTimeNanos(candidate);
        long currentTime = locationTimeNanos(current);
        if (candidateTime > currentTime + 2_000_000_000L) {
            return true;
        }
        return Math.abs(candidateTime - currentTime) <= 2_000_000_000L
                && candidate.hasAccuracy()
                && (!current.hasAccuracy()
                || candidate.getAccuracy() < current.getAccuracy());
    }

    private boolean isSignificantlyNewer(Location candidate) {
        return LocationAcceptancePolicy.isSignificantlyNewer(
                candidate.getElapsedRealtimeNanos(),
                currentLocation.getElapsedRealtimeNanos(),
                candidate.getTime(),
                currentLocation.getTime(),
                LOCATION_SIGNIFICANTLY_NEWER_MS
        );
    }

    private long locationTimeNanos(Location location) {
        return location.getElapsedRealtimeNanos() > 0L
                ? location.getElapsedRealtimeNanos()
                : location.getTime() * 1_000_000L;
    }

    private boolean isFresh(Location location) {
        return isFresh(location, LOCATION_MAX_AGE_MS);
    }

    private boolean isFresh(Location location, long maxAgeMs) {
        return VehicleFinderMath.isFresh(
                location.getElapsedRealtimeNanos(),
                SystemClock.elapsedRealtimeNanos(),
                location.getTime(),
                System.currentTimeMillis(),
                maxAgeMs
        );
    }

    @Override
    public void onLocationChanged(Location location) {
        markProviderAvailability(location.getProvider(), true);
        useLocation(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        markProviderAvailability(provider, status == LocationProvider.AVAILABLE);
    }

    @Override
    public void onProviderEnabled(String provider) {
        markProviderAvailability(provider, true);
        stopLocationTracking();
        startLocationTracking(false);
    }

    @Override
    public void onProviderDisabled(String provider) {
        markProviderAvailability(provider, false);
        if (!isLocationEnabled()) {
            stopLocationTracking();
            setHint(getString(R.string.finder_location_off_hint));
            showAction(
                    ACTION_LOCATION_SETTINGS,
                    R.string.finder_open_location_settings
            );
        }
    }

    private void markProviderAvailability(String provider, boolean available) {
        boolean unavailable = !available;
        boolean changed = false;
        if (LocationManager.GPS_PROVIDER.equals(provider)
                && gpsProviderUnavailable != unavailable) {
            gpsProviderUnavailable = unavailable;
            changed = true;
        } else if (LocationManager.NETWORK_PROVIDER.equals(provider)
                && networkProviderUnavailable != unavailable) {
            networkProviderUnavailable = unavailable;
            changed = true;
        }
        if (changed) {
            DiagnosticLog.detail(
                    "FinderLoc",
                    "provider=" + (provider == null ? "unknown" : provider)
                            + " available=" + available
            );
            scheduleVisualUpdate();
        }
    }

    private boolean isProviderAvailable(Location location) {
        if (location == null) {
            return false;
        }
        String provider = location.getProvider();
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            return !gpsProviderUnavailable;
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            return !networkProviderUnavailable;
        }
        return true;
    }

    private void logLocationDecision(String decision, Location location) {
        if (!DiagnosticLog.isDetailEnabled()) {
            return;
        }
        long ageMs = VehicleFinderMath.locationAgeMs(
                location.getElapsedRealtimeNanos(),
                SystemClock.elapsedRealtimeNanos(),
                location.getTime(),
                System.currentTimeMillis()
        );
        float accuracyMeters = location.hasAccuracy() ? location.getAccuracy() : 0f;
        long currentAgeMs = currentLocation == null
                ? Long.MAX_VALUE
                : VehicleFinderMath.locationAgeMs(
                currentLocation.getElapsedRealtimeNanos(),
                SystemClock.elapsedRealtimeNanos(),
                currentLocation.getTime(),
                System.currentTimeMillis()
        );
        float currentAccuracyMeters = currentLocation != null
                && currentLocation.hasAccuracy()
                ? currentLocation.getAccuracy()
                : 0f;
        String signature = decision
                + "|" + location.getProvider()
                + "|" + locationAgeDiagnosticBucket(ageMs)
                + "|" + accuracyDiagnosticBucket(accuracyMeters)
                + "|" + locationAgeDiagnosticBucket(currentAgeMs)
                + "|" + accuracyDiagnosticBucket(currentAccuracyMeters);
        if (signature.equals(lastLocationDiagnostic)) {
            return;
        }
        lastLocationDiagnostic = signature;
        DiagnosticLog.detail(
                "FinderFix",
                "decision=" + decision
                        + " provider=" + location.getProvider()
                        + " age=" + locationAgeDiagnosticBucket(ageMs)
                        + " accuracy=" + accuracyDiagnosticBucket(accuracyMeters)
                        + " currentAge=" + locationAgeDiagnosticBucket(currentAgeMs)
                        + " currentAccuracy="
                        + accuracyDiagnosticBucket(currentAccuracyMeters)
        );
    }

    private static String locationAgeDiagnosticBucket(long ageMs) {
        if (ageMs < 0L) {
            return "future";
        }
        if (ageMs <= VehicleFinderMath.DIRECTION_MAX_AGE_MS) {
            return "<=5s";
        }
        if (ageMs <= VehicleFinderMath.DIRECTION_HOLD_MAX_AGE_MS) {
            return "6-8s";
        }
        if (ageMs <= LOCATION_MAX_AGE_MS) {
            return "9-120s";
        }
        return ">120s";
    }

    private static String accuracyDiagnosticBucket(float accuracyMeters) {
        if (accuracyMeters <= 0f) {
            return "unknown";
        }
        if (accuracyMeters <= 5f) {
            return "<=5m";
        }
        if (accuracyMeters <= 10f) {
            return "6-10m";
        }
        if (accuracyMeters <= 25f) {
            return "11-25m";
        }
        if (accuracyMeters <= 45f) {
            return "26-45m";
        }
        return ">45m";
    }

    private void scheduleVisualUpdate() {
        handler.removeCallbacks(visualUpdate);
        handler.postDelayed(visualUpdate, 60L);
    }

    private void updateVisualsNow() {
        handler.removeCallbacks(visualUpdate);
        lastVisualUpdateMs = 0L;
        updateVisuals();
    }

    private void updateVisuals() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastVisualUpdateMs < 50L) {
            return;
        }
        lastVisualUpdateMs = now;
        updateFreshness();
        String warning = "";

        boolean blePrimary = bleController.isPrimary();
        if (blePrimary) {
            showBleDistance();
        }
        if (!hasLocationPermission()) {
            showUnavailable(
                    R.string.finder_distance_placeholder,
                    R.string.finder_direction_placeholder,
                    getString(R.string.finder_permission_hint),
                    blePrimary
            );
            showLocationPermissionAction();
            return;
        }
        if (reacquiringLocation) {
            boolean locationEnabled = isLocationEnabled();
            showUnavailable(
                    R.string.finder_distance_placeholder,
                    R.string.finder_direction_placeholder,
                    locationEnabled
                            ? getString(R.string.finder_waiting_location)
                            : getString(R.string.finder_location_off_hint),
                    blePrimary
            );
            if (!locationEnabled) {
                showAction(
                        ACTION_LOCATION_SETTINGS,
                        R.string.finder_open_location_settings
                );
            } else {
                hideAction();
            }
            return;
        }
        if (currentLocation == null) {
            showUnavailable(
                    R.string.finder_distance_placeholder,
                    R.string.finder_direction_placeholder,
                    isLocationEnabled()
                            ? getString(R.string.finder_waiting_location)
                            : getString(R.string.finder_location_off_hint),
                    blePrimary
            );
            if (!isLocationEnabled()) {
                showAction(
                        ACTION_LOCATION_SETTINGS,
                        R.string.finder_open_location_settings
                );
            }
            return;
        }
        GeoPoint currentGcj = CoordinateUtils.wgs84ToGcj02(
                currentLocation.getLatitude(),
                currentLocation.getLongitude()
        );
        float[] results = new float[2];
        Location.distanceBetween(
                currentGcj.getLatitude(),
                currentGcj.getLongitude(),
                target.latitude(),
                target.longitude(),
                results
        );
        float distanceMeters = results[0];
        float bearing = results[1];
        boolean locationEnabled = isLocationEnabled();
        boolean positionFresh = isFresh(currentLocation);
        boolean locationUsable = locationEnabled && positionFresh;
        boolean providerAvailable = isProviderAvailable(currentLocation);
        long qualityAgeMs = VehicleFinderMath.locationAgeMs(
                currentLocation.getElapsedRealtimeNanos(),
                SystemClock.elapsedRealtimeNanos(),
                currentLocation.getTime(),
                System.currentTimeMillis()
        );
        float accuracyMeters = currentLocation.hasAccuracy()
                ? currentLocation.getAccuracy()
                : 0f;
        accuracyQuality = VehicleFinderMath.accuracyQuality(
                accuracyMeters,
                accuracyQuality
        );
        if (!blePrimary) {
            tvDistance.setText(formatFinderDistance(
                    distanceMeters,
                    accuracyMeters,
                    locationUsable
            ));
            radarView.setProximity(1f - Math.min(1f, distanceMeters / 300f));
        }
        imgArrow.setAlpha(1f);

        boolean positionCurrentForNear = providerAvailable
                && qualityAgeMs >= 0L
                && qualityAgeMs <= VehicleFinderMath.DIRECTION_HOLD_MAX_AGE_MS
                && accuracyMeters > 0f;
        boolean inEstimatedTargetArea = positionCurrentForNear
                && (distanceMeters <= 8f || distanceMeters <= accuracyMeters);
        boolean headingReliable = headingTracker.hasHeading()
                && headingTracker.isReliable();
        boolean directionDataReady = VehicleFinderMath.canUseLocationForDirection(
                qualityAgeMs,
                accuracyMeters,
                distanceMeters,
                providerAvailable
        );
        boolean canShowDirectionArrow = VehicleFinderMath.canShowDirectionArrow(
                qualityAgeMs,
                accuracyMeters,
                distanceMeters,
                providerAvailable,
                headingReliable
        );
        long targetLastUploadAtMs = target.lastUploadAtMs();
        boolean uploadTimeKnown = targetLastUploadAtMs > 0L;
        long uploadAge = uploadTimeKnown
                ? Math.max(0L, System.currentTimeMillis() - targetLastUploadAtMs)
                : 0L;
        int targetFreshness = VehicleFinderMath.targetFreshness(uploadTimeKnown, uploadAge);
        if (directionDataReady) {
            heldBearingDegrees = bearing;
            heldBearingDistanceMeters = distanceMeters;
            hasHeldBearing = true;
        }
        boolean heldDirectionConsistent = hasHeldBearing
                && VehicleFinderMath.isHeldDirectionConsistent(
                heldBearingDegrees,
                bearing,
                heldBearingDistanceMeters,
                distanceMeters
        );
        boolean directionHold = !canShowDirectionArrow
                && !inEstimatedTargetArea
                && VehicleFinderMath.canHoldDirection(
                        qualityAgeMs,
                        accuracyMeters,
                        distanceMeters,
                        providerAvailable,
                        hasHeldBearing,
                        headingReliable
                )
                && heldDirectionConsistent;
        if (!directionDataReady && !directionHold) {
            clearHeldBearing();
        }
        VehicleFinderDirection.Mode directionMode = VehicleFinderDirection.resolve(
                locationUsable,
                providerAvailable,
                directionDataReady,
                inEstimatedTargetArea,
                canShowDirectionArrow,
                directionHold,
                qualityAgeMs
        );
        String directionState = directionMode.logName;
        switch (directionMode) {
            case LOCATION_STALE:
                imgArrow.setVisibility(View.GONE);
                tvDirection.setText(R.string.finder_direction_stale);
                break;
            case PROVIDER_UNAVAILABLE:
                imgArrow.setVisibility(View.GONE);
                tvDirection.setText(R.string.finder_direction_provider_unavailable);
                break;
            case NEAR_TARGET:
                imgArrow.setVisibility(View.GONE);
                boolean reliableNear = VehicleFinderMath.isReliableNearTarget(
                        distanceMeters,
                        accuracyMeters
                );
                int directionRes;
                if (!reliableNear) {
                    directionRes = R.string.finder_direction_near_uncertain;
                } else if (targetFreshness == VehicleFinderMath.TARGET_FRESH) {
                    directionRes = R.string.finder_direction_here;
                } else {
                    directionRes = R.string.finder_direction_last_position_here;
                }
                tvDirection.setText(directionRes);
                break;
            case RELATIVE_ARROW:
                imgArrow.setVisibility(View.VISIBLE);
                float relative = VehicleFinderMath.normalizeSignedDegrees(
                        bearing - headingTracker.headingDegrees()
                );
                imgArrow.setRotation(relative);
                String label = targetAwareDirectionLabel(
                        relativeDirectionLabel(relative),
                        targetFreshness
                );
                tvDirection.setText(label);
                imgArrow.setContentDescription(label);
                break;
            case MAP_BEARING:
                imgArrow.setVisibility(View.GONE);
                String bearingLabel = getString(
                        R.string.finder_direction_north_format,
                        cardinalDirection(bearing)
                );
                tvDirection.setText(bearingLabel);
                imgArrow.setContentDescription(bearingLabel);
                break;
            case HOLD:
                imgArrow.setAlpha(0.55f);
                imgArrow.setVisibility(View.VISIBLE);
                float heldRelative = VehicleFinderMath.normalizeSignedDegrees(
                        heldBearingDegrees - headingTracker.headingDegrees()
                );
                imgArrow.setRotation(heldRelative);
                String heldLabel = targetAwareDirectionLabel(
                        relativeDirectionLabel(heldRelative),
                        targetFreshness
                );
                heldLabel = getString(R.string.finder_direction_hold_format, heldLabel);
                tvDirection.setText(heldLabel);
                imgArrow.setContentDescription(heldLabel);
                break;
            case FIX_TOO_OLD:
            case ACCURACY_LIMIT:
                imgArrow.setVisibility(View.GONE);
                int fallbackRes = directionMode == VehicleFinderDirection.Mode.FIX_TOO_OLD
                        ? R.string.finder_direction_stale
                        : R.string.finder_direction_location_uncertain;
                tvDirection.setText(fallbackRes);
                imgArrow.setContentDescription(getString(fallbackRes));
                break;
            default:
                throw new IllegalStateException("Unexpected direction mode");
        }
        logDirectionState(
                directionState,
                providerAvailable,
                distanceMeters,
                bearing,
                accuracyMeters
        );

        if (!locationEnabled) {
            warning = appendHint(warning, getString(R.string.finder_location_off_hint));
        } else if (!positionFresh) {
            warning = appendHint(
                    warning,
                    getString(R.string.finder_current_location_stale)
            );
        }
        if (directionHold) {
            warning = appendHint(
                    warning,
                    getString(R.string.finder_hint_direction_hold)
            );
        }
        if (!directionDataReady
                && providerAvailable
                && qualityAgeMs >= 0L
                && qualityAgeMs <= VehicleFinderMath.DIRECTION_HOLD_MAX_AGE_MS
                && accuracyMeters > 0f) {
            warning = appendHint(
                    warning,
                    getString(
                            R.string.finder_hint_direction_accuracy,
                            VehicleFinderMath.bucketAccuracyMeters(
                                    accuracyMeters,
                                    accuracyQuality
                            )
                    )
            );
        }
        if (directionDataReady && !headingTracker.hasSensor()) {
            warning = appendHint(
                    warning,
                    getString(R.string.finder_hint_compass_unavailable)
            );
        } else if (directionDataReady && !headingTracker.isReliable()) {
            warning = appendHint(
                    warning,
                    getString(R.string.finder_hint_compass_unreliable)
            );
        }
        if (targetFreshness == VehicleFinderMath.TARGET_UNKNOWN) {
            warning = appendHint(warning, getString(R.string.finder_hint_target_unknown));
        } else if (targetFreshness == VehicleFinderMath.TARGET_STALE) {
            warning = appendHint(warning, getString(R.string.finder_hint_stale));
        } else if (targetFreshness == VehicleFinderMath.TARGET_AGING) {
            warning = appendHint(warning, getString(R.string.finder_hint_target_aging));
        }
        setHint(warning);
        if (!locationEnabled) {
            showAction(
                    ACTION_LOCATION_SETTINGS,
                    R.string.finder_open_location_settings
            );
        } else {
            hideAction();
        }
    }

    private void showBleDistance() {
        int distanceRes;
        float proximity;
        if (bleController.status() == VehicleBleFinderState.Status.WEAK) {
            distanceRes = R.string.finder_ble_primary_weak;
            proximity = bleController.band() == VehicleBleMath.BAND_VERY_NEAR ? 1f : 0.66f;
        } else if (!bleController.hasTargetSignal()) {
            distanceRes = R.string.finder_ble_primary_lost;
            proximity = 0.1f;
        } else if (bleController.band() == VehicleBleMath.BAND_VERY_NEAR) {
            distanceRes = R.string.finder_ble_primary_very_near;
            proximity = 1f;
        } else if (bleController.band() == VehicleBleMath.BAND_NEAR) {
            distanceRes = R.string.finder_ble_primary_near;
            proximity = 0.66f;
        } else {
            distanceRes = R.string.finder_ble_primary_lost;
            proximity = 0.1f;
        }
        tvDistance.setText(distanceRes);
        radarView.setProximity(proximity);
    }

    private void logBleState(String state) {
        String message = state
                + " band=" + bleBandLabel(bleController.band())
                + " scanning=" + bleController.isScanning();
        if ("start".equals(state)
                || "stop".equals(state)
                || "timeout".equals(state)
                || "failed".equals(state)) {
            DiagnosticLog.event("FinderBle", message);
        } else {
            DiagnosticLog.detail("FinderBle", message);
        }
    }

    @Override
    public VehicleFinderTargetState target() {
        return target;
    }

    @Override
    public void onBleStateChanged() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        updateBlePanel();
        updateVisualsNow();
    }

    @Override
    public void onBleLog(String state) {
        if (!isFinishing() && !isDestroyed()) {
            logBleState(state);
        }
    }

    private void logDirectionState(
            String state,
            boolean providerAvailable,
            float distanceMeters,
            float bearingDegrees,
            float accuracyMeters
    ) {
        String distanceBucket = distanceBucket(distanceMeters);
        String directionAccuracy = directionAccuracyLabel(distanceMeters, accuracyMeters);
        String heading = headingDiagnosticState();
        String headingSector = headingTracker.hasHeading()
                ? directionSector(headingTracker.headingDegrees())
                : "unavailable";
        String arrowSector = headingTracker.hasHeading()
                ? directionSector(bearingDegrees - headingTracker.headingDegrees())
                : "unavailable";
        String accuracyRatio = directionAccuracyRatioLabel(distanceMeters, accuracyMeters);
        String holdDelta = hasHeldBearing
                ? holdDistanceDeltaLabel(distanceMeters)
                + "/" + holdBearingDeltaLabel(bearingDegrees)
                : "none";
        String targetSource = target.source().name();
        String targetAge = targetAgeDiagnosticState();
        String near = "near-target".equals(state)
                ? nearTargetReason(distanceMeters, accuracyMeters)
                : "none";
        String signature = state
                + "|" + providerAvailable
                + "|" + accuracyQuality
                + "|" + distanceBucket
                + "|" + directionAccuracy
                + "|" + heading
                + "|" + headingSector
                + "|" + arrowSector
                + "|" + accuracyRatio
                + "|" + holdDelta
                + "|" + targetSource
                + "|" + targetAge
                + "|" + near;
        if (signature.equals(lastDirectionDiagnostic)) {
            return;
        }
        lastDirectionDiagnostic = signature;
        DiagnosticLog.detail(
                "FinderLoc",
                "direction=" + state
                        + " providerAvailable=" + providerAvailable
                        + " quality=" + accuracyQualityLabel(accuracyQuality)
                        + " rangeSource=" + (bleController.isPrimary() ? "ble" : "map")
                        + " mapDistance=" + distanceBucket
                        + " directionAccuracy=" + directionAccuracy
                        + " heading=" + heading
                        + " headingSector=" + headingSector
                        + " arrowSector=" + arrowSector
                        + " accuracyRatio=" + accuracyRatio
                        + " holdDelta=" + holdDelta
                        + " target=" + targetSource
                        + " targetAge=" + targetAge
                        + " near=" + near
        );
    }

    private static String directionSector(float degrees) {
        float normalized = VehicleFinderMath.normalizeDegrees(degrees);
        int sector = Math.floorDiv(Math.round(normalized), 45) % 8;
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[sector];
    }

    private static String directionAccuracyRatioLabel(
            float distanceMeters,
            float accuracyMeters
    ) {
        if (distanceMeters <= 0f || accuracyMeters <= 0f) {
            return "unknown";
        }
        if (VehicleFinderMath.isDirectionAccuracySufficient(
                distanceMeters,
                accuracyMeters
        )) {
            return "tight";
        }
        if (accuracyMeters
                <= VehicleFinderMath.directionHoldAccuracyLimitMeters(distanceMeters)) {
            return "limited";
        }
        float ratio = accuracyMeters / distanceMeters;
        return ratio <= 1f ? "overlap" : "invalid";
    }

    private String holdDistanceDeltaLabel(float distanceMeters) {
        return Math.abs(distanceMeters - heldBearingDistanceMeters)
                <= VehicleFinderMath.DIRECTION_HOLD_MAX_DISPLACEMENT_METERS
                ? "<=5m"
                : ">5m";
    }

    private String holdBearingDeltaLabel(float bearingDegrees) {
        float delta = Math.abs(VehicleFinderMath.normalizeSignedDegrees(
                bearingDegrees - heldBearingDegrees
        ));
        if (delta <= 15f) {
            return "<=15deg";
        }
        if (delta <= VehicleFinderMath.DIRECTION_HOLD_MAX_BEARING_DELTA_DEGREES) {
            return "16-45deg";
        }
        return ">45deg";
    }

    private String headingDiagnosticState() {
        if (!headingTracker.hasSensor() || !headingTracker.hasHeading()) {
            return "unavailable";
        }
        return headingTracker.isReliable() ? "ok" : "unreliable";
    }

    private String targetAgeDiagnosticState() {
        long lastUploadAtMs = target.lastUploadAtMs();
        boolean known = lastUploadAtMs > 0L;
        long ageMs = known
                ? Math.max(0L, System.currentTimeMillis() - lastUploadAtMs)
                : 0L;
        switch (VehicleFinderMath.targetFreshness(known, ageMs)) {
            case VehicleFinderMath.TARGET_FRESH:
                return "fresh";
            case VehicleFinderMath.TARGET_AGING:
                return "aging";
            case VehicleFinderMath.TARGET_STALE:
                return "stale";
            default:
                return "unknown";
        }
    }

    private static String accuracyQualityLabel(int quality) {
        if (quality == VehicleFinderMath.ACCURACY_GOOD) {
            return "good";
        }
        if (quality == VehicleFinderMath.ACCURACY_MODERATE) {
            return "moderate";
        }
        if (quality == VehicleFinderMath.ACCURACY_POOR) {
            return "poor";
        }
        return "unknown";
    }

    private static String distanceBucket(float distanceMeters) {
        if (Float.isNaN(distanceMeters) || Float.isInfinite(distanceMeters)
                || distanceMeters < 0f) {
            return "unknown";
        }
        if (distanceMeters <= 8f) {
            return "<=8m";
        }
        if (distanceMeters <= 20f) {
            return "9-20m";
        }
        if (distanceMeters <= 50f) {
            return "21-50m";
        }
        if (distanceMeters <= 100f) {
            return "51-100m";
        }
        return ">100m";
    }

    private static String directionAccuracyLabel(
            float distanceMeters,
            float accuracyMeters
    ) {
        if (accuracyMeters <= 0f) {
            return "unknown";
        }
        if (VehicleFinderMath.isDirectionAccuracySufficient(
                distanceMeters,
                accuracyMeters
        )) {
            return "sufficient";
        }
        if (accuracyMeters
                <= VehicleFinderMath.directionHoldAccuracyLimitMeters(distanceMeters)) {
            return "hold-only";
        }
        return accuracyMeters <= 80f ? "moderate" : "poor";
    }

    private static String nearTargetReason(float distanceMeters, float accuracyMeters) {
        if (distanceMeters <= 8f) {
            return "fixed-8m";
        }
        if (accuracyMeters > 0f && distanceMeters <= accuracyMeters) {
            return "within-accuracy";
        }
        return "none";
    }

    private static String bleBandLabel(int band) {
        if (band == VehicleBleMath.BAND_VERY_NEAR) {
            return "very_near";
        }
        if (band == VehicleBleMath.BAND_NEAR) {
            return "near";
        }
        if (band == VehicleBleMath.BAND_FAR) {
            return "far";
        }
        return "none";
    }

    private void showUnavailable(
            int distanceRes,
            int directionRes,
            String warning,
            boolean keepDistance
    ) {
        if (!keepDistance) {
            tvDistance.setText(distanceRes);
            radarView.setProximity(0.1f);
        }
        tvDirection.setText(directionRes);
        imgArrow.setVisibility(View.GONE);
        setHint(warning);
    }

    private void setHint(String warning) {
        if (warning == null || warning.isEmpty()) {
            tvHint.setText("");
            tvHint.setVisibility(View.GONE);
        } else {
            tvHint.setText(warning);
            tvHint.setVisibility(View.VISIBLE);
        }
    }

    private String appendHint(String first, String second) {
        if (first == null || first.isEmpty()) {
            return second;
        }
        return first + "\n" + second;
    }

    private void updateFreshness() {
        if (refreshingTarget) {
            tvFreshness.setText(R.string.finder_refreshing_vehicle);
            return;
        }
        if (reacquiringLocation) {
            tvFreshness.setText(R.string.finder_refreshing_location);
            return;
        }
        if (lastTargetRefreshMissed) {
            tvFreshness.setText(R.string.finder_target_refresh_failed);
            return;
        }
        long targetLastUploadAtMs = target.lastUploadAtMs();
        if (targetLastUploadAtMs <= 0L) {
            tvFreshness.setText(R.string.finder_last_upload_unknown);
            return;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - targetLastUploadAtMs);
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        format.setTimeZone(CHINA_TIME_ZONE);
        tvFreshness.setText(getString(
                R.string.finder_last_upload_format,
                format.format(new Date(targetLastUploadAtMs)),
                formatUploadAge(ageMs)
        ));
    }

    private String formatFinderDistance(
            float distanceMeters,
            float accuracyMeters,
            boolean locationUsable
    ) {
        int approximateMeters = VehicleFinderMath.approximateDistanceMeters(
                distanceMeters,
                accuracyQuality
        );
        if (!locationUsable) {
            return getString(
                    R.string.finder_distance_last_format,
                    formatDistanceMagnitude(approximateMeters)
            );
        }
        if (accuracyMeters > VehicleFinderMath.DIRECTION_MAX_ACCURACY_METERS) {
            int radius = VehicleFinderMath.bucketAccuracyMeters(
                    accuracyMeters,
                    accuracyQuality
            );
            int lowMeters = Math.max(0, approximateMeters - radius);
            int highMeters = approximateMeters + radius;
            if (lowMeters == 0) {
                return highMeters < 1_000
                        ? getString(R.string.finder_distance_within_meters_format, highMeters)
                        : getString(
                                R.string.finder_distance_within_kilometers_format,
                                highMeters / 1_000f
                        );
            }
            return highMeters < 1_000
                    ? getString(
                            R.string.finder_distance_range_meters_format,
                            lowMeters,
                            highMeters
                    )
                    : getString(
                            R.string.finder_distance_range_kilometers_format,
                            lowMeters / 1_000f,
                            highMeters / 1_000f
                    );
        }
        return getString(
                R.string.finder_distance_approximate_format,
                formatDistanceMagnitude(approximateMeters)
        );
    }

    private String formatDistanceMagnitude(int meters) {
        if (meters < 1_000) {
            return getString(R.string.distance_meters_format, meters);
        }
        return getString(R.string.distance_kilometers_format, meters / 1_000f);
    }

    private String relativeDirectionLabel(float degrees) {
        if (degrees < 22.5f || degrees >= 337.5f) {
            return getString(R.string.finder_direction_ahead);
        }
        if (degrees < 67.5f) {
            return getString(R.string.finder_direction_right_ahead);
        }
        if (degrees < 112.5f) {
            return getString(R.string.finder_direction_right);
        }
        if (degrees < 157.5f) {
            return getString(R.string.finder_direction_back_right);
        }
        if (degrees < 202.5f) {
            return getString(R.string.finder_direction_back);
        }
        if (degrees < 247.5f) {
            return getString(R.string.finder_direction_back_left);
        }
        if (degrees < 292.5f) {
            return getString(R.string.finder_direction_left);
        }
        return getString(R.string.finder_direction_left_ahead);
    }

    private String targetAwareDirectionLabel(String label, int targetFreshness) {
        if (targetFreshness == VehicleFinderMath.TARGET_FRESH) {
            return label;
        }
        if (targetFreshness == VehicleFinderMath.TARGET_STALE) {
            return getString(R.string.finder_direction_expired_format, label);
        }
        return getString(R.string.finder_direction_last_position_format, label);
    }

    private String cardinalDirection(float degrees) {
        if (degrees < 22.5f || degrees >= 337.5f) {
            return getString(R.string.finder_cardinal_north);
        }
        if (degrees < 67.5f) {
            return getString(R.string.finder_cardinal_north_east);
        }
        if (degrees < 112.5f) {
            return getString(R.string.finder_cardinal_east);
        }
        if (degrees < 157.5f) {
            return getString(R.string.finder_cardinal_south_east);
        }
        if (degrees < 202.5f) {
            return getString(R.string.finder_cardinal_south);
        }
        if (degrees < 247.5f) {
            return getString(R.string.finder_cardinal_south_west);
        }
        if (degrees < 292.5f) {
            return getString(R.string.finder_cardinal_west);
        }
        return getString(R.string.finder_cardinal_north_west);
    }

    private void showAction(int action, int buttonRes) {
        currentAction = action;
        btnAction.setText(buttonRes);
        btnAction.setEnabled(true);
        btnAction.setVisibility(View.VISIBLE);
    }

    private void hideAction() {
        currentAction = ACTION_NONE;
        btnAction.setVisibility(View.GONE);
    }

    private boolean isLocationEnabled() {
        if (locationManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void clearHeldBearing() {
        hasHeldBearing = false;
        heldBearingDegrees = 0f;
        heldBearingDistanceMeters = 0f;
    }

    private void onHeadingChanged() {
        if (!headingTracker.isReliable()) {
            clearHeldBearing();
        }
        scheduleVisualUpdate();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            startLocationTracking(!reacquiringLocation);
            scheduleLocationReacquireTimeout();
            updateVisualsNow();
        } else if (requestCode == REQUEST_BLE_PERMISSIONS) {
            if (hasBlePermissions()) {
                // 重新走完整前置检查：定位或蓝牙被关闭时应引导设置，
                // 而不是直接启动扫描后显示泛化的失败状态。
                handleBleAction();
            } else {
                updateBlePanel();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastLocationDiagnostic = null;
        lastDirectionDiagnostic = null;
        headingTracker.register();
        if (!entryRefreshEvaluated) {
            entryRefreshEvaluated = true;
            if (shouldAutoRefreshTarget()) {
                refreshPositions();
            } else {
                startLocationTracking(!reacquiringLocation);
            }
        } else if (reacquiringLocation) {
            startLocationTracking(false);
            scheduleLocationReacquireTimeout();
        } else if (shouldReacquireCurrentLocation()) {
            beginLocationReacquire();
        } else {
            startLocationTracking(true);
        }
        updateBlePanel();
        updateVisualsNow();
        handler.removeCallbacks(freshnessTick);
        handler.postDelayed(freshnessTick, UI_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(freshnessTick);
        handler.removeCallbacks(visualUpdate);
        handler.removeCallbacks(locationReacquireTimeout);
        if (bleController != null && bleController.isScanning()) {
            bleController.stop(false, false);
        }
        stopLocationTracking();
        headingTracker.unregister();
        clearHeldBearing();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (repository != null) {
            repository.shutdown();
        }
        if (bleController != null) {
            bleController.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(
                STATE_LOCATION_PERMISSION_REQUESTED,
                locationPermissionRequested
        );
        outState.putBoolean(
                STATE_BLE_PERMISSION_REQUESTED,
                blePermissionRequested
        );
        outState.putDouble(STATE_TARGET_LATITUDE, target.latitude());
        outState.putDouble(STATE_TARGET_LONGITUDE, target.longitude());
        outState.putLong(STATE_TARGET_LAST_UPLOAD, target.lastUploadAtMs());
        outState.putString(STATE_TARGET_SOURCE, target.source().name());
        outState.putString(STATE_TARGET_BLUETOOTH_NAME, target.bluetoothName());
        super.onSaveInstanceState(outState);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.vehicleFinderRoot);
        View header = findViewById(R.id.vehicleFinderHeader);
        View content = findViewById(R.id.vehicleFinderContent);
        int headerBaseHeight = dp(64);
        int contentBaseBottom = dp(16);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            applyHeaderInsets(header, headerBaseHeight, systemBars.top);
            content.setPadding(
                    content.getPaddingLeft(),
                    content.getPaddingTop(),
                    content.getPaddingRight(),
                    contentBaseBottom + systemBars.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
