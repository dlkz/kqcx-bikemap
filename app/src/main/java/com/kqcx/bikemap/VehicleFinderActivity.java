package com.kqcx.bikemap;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.osmdroid.util.GeoPoint;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Coarse vehicle finder based on the last reported vehicle coordinate.
 */
public final class VehicleFinderActivity extends BaseActivity
        implements LocationListener, SensorEventListener {
    private static final String EXTRA_NUMBER = "finder_number";
    private static final String EXTRA_LATITUDE = "finder_latitude";
    private static final String EXTRA_LONGITUDE = "finder_longitude";
    private static final String EXTRA_LAST_UPLOAD = "finder_last_upload";
    private static final String EXTRA_CURRENT_LATITUDE = "finder_current_latitude";
    private static final String EXTRA_CURRENT_LONGITUDE = "finder_current_longitude";
    private static final String EXTRA_CURRENT_ACCURACY = "finder_current_accuracy";
    private static final String EXTRA_CURRENT_TIME = "finder_current_time";
    private static final String EXTRA_CURRENT_ELAPSED = "finder_current_elapsed";
    private static final String STATE_LOCATION_PERMISSION_REQUESTED =
            "finder_location_permission_requested";

    private static final int REQUEST_LOCATION = 5101;
    private static final int ACTION_NONE = 0;
    private static final int ACTION_PERMISSION = 1;
    private static final int ACTION_LOCATION_SETTINGS = 2;
    private static final int ACTION_APP_SETTINGS = 3;
    private static final long LOCATION_MAX_AGE_MS = 120_000L;
    private static final long UI_REFRESH_MS = 1_000L;
    private static final long LOCATION_TIMESTAMP_TOLERANCE_MS = 1_000L;
    private static final long LOCATION_SIGNIFICANTLY_NEWER_MS = 10_000L;
    private static final long LOCATION_GPS_FRESH_WINDOW_MS = 15_000L;
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
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private BikeRepository repository;
    private Sensor rotationSensor;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private Location currentLocation;
    private Location latestObservedLocation;
    private Location pendingRefreshLocation;
    private TextView tvDistance;
    private TextView tvDirection;
    private TextView tvFreshness;
    private TextView tvSource;
    private TextView tvHint;
    private ImageView imgArrow;
    private Button btnAction;
    private ImageButton btnFinderRefresh;
    private VehicleFinderRadarView radarView;
    private VehicleFinderTargetState target;
    private float headingDegrees;
    private boolean hasHeading;
    private boolean trackingLocation;
    private boolean hasGravity;
    private boolean hasGeomagnetic;
    private boolean directionSensorAvailable;
    private boolean gpsProviderUnavailable;
    private boolean networkProviderUnavailable;
    private boolean reacquiringLocation;
    private boolean refreshingTarget;
    private boolean locationPermissionRequested;
    private int accuracyQuality = VehicleFinderMath.ACCURACY_UNKNOWN;
    private int headingSensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    private int currentAction = ACTION_NONE;
    private long lastVisualUpdateMs;
    private long locationReacquireStartedElapsedMs;
    private long locationReacquireStartedElapsedNanos;
    private long locationReacquireStartedWallMs;
    private int lastLoggedAccuracyQuality = VehicleFinderMath.ACCURACY_UNKNOWN;
    private String lastDirectionDiagnostic;

    public static Intent createIntent(Context context, Bike bike, Location currentLocation) {
        Intent intent = new Intent(context, VehicleFinderActivity.class)
                .putExtra(EXTRA_NUMBER, bike.number)
                .putExtra(EXTRA_LATITUDE, bike.latitude)
                .putExtra(EXTRA_LONGITUDE, bike.longitude)
                .putExtra(EXTRA_LAST_UPLOAD, bike.lastUploadAtMs);
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
        }
        if (!readTarget()) {
            Toast.makeText(this, R.string.finder_target_invalid, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_vehicle_finder);
        bindViews();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        repository = new BikeRepository(this);
        setupSensors();
        applyInitialLocation();
        ((TextView) findViewById(R.id.tvFinderVehicleNumber)).setText(getString(
                R.string.finder_vehicle_title,
                target.number()
        ));
        findViewById(R.id.btnFinderBack).setOnClickListener(view -> finish());
        btnFinderRefresh.setOnClickListener(view -> refreshPositions());
        btnAction.setOnClickListener(view -> handleAction());
        applySystemBarInsets();
        updateTargetSource();
        updateRefreshButtonState();
        updateFreshness();
        updateVisualsNow();
    }

    private boolean readTarget() {
        Intent intent = getIntent();
        target = VehicleFinderTargetState.createEstimated(
                intent.getStringExtra(EXTRA_NUMBER),
                intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN),
                intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN),
                intent.getLongExtra(EXTRA_LAST_UPLOAD, 0L)
        );
        return target != null;
    }

    private void bindViews() {
        tvDistance = findViewById(R.id.tvFinderDistance);
        tvDirection = findViewById(R.id.tvFinderDirection);
        tvFreshness = findViewById(R.id.tvFinderFreshness);
        tvSource = findViewById(R.id.tvFinderSource);
        tvHint = findViewById(R.id.tvFinderHint);
        imgArrow = findViewById(R.id.imgFinderArrow);
        btnAction = findViewById(R.id.btnFinderAction);
        btnFinderRefresh = findViewById(R.id.btnFinderRefresh);
        radarView = findViewById(R.id.vehicleFinderRadar);
    }

    private void setupSensors() {
        if (sensorManager == null) {
            return;
        }
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationSensor == null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
        directionSensorAvailable = rotationSensor != null
                || (accelerometer != null && magnetometer != null);
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

    private void refreshPositions() {
        if (refreshingTarget
                || reacquiringLocation
                || repository == null
                || !target.number().matches("\\d{9}")) {
            return;
        }
        GeoPoint queryPoint = currentLocation == null
                ? new GeoPoint(target.latitude(), target.longitude())
                : CoordinateUtils.wgs84ToGcj02(
                        currentLocation.getLatitude(),
                        currentLocation.getLongitude()
                );
        refreshingTarget = true;
        beginLocationReacquire();
        updateRefreshButtonState();
        tvFreshness.setText(R.string.finder_refreshing_vehicle);
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
                        finishTargetRefresh();
                        if (bike == null
                                || !bike.locationKnown
                                || !target.applyRefresh(bike)) {
                            Toast.makeText(
                                    VehicleFinderActivity.this,
                                    R.string.finder_refresh_miss,
                                    Toast.LENGTH_SHORT
                            ).show();
                            return;
                        }
                        updateTargetSource();
                        updateFreshness();
                        updateVisualsNow();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        finishTargetRefresh();
                        Toast.makeText(
                                VehicleFinderActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
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

    private void updateTargetSource() {
        if (tvSource == null || target == null) {
            return;
        }
        tvSource.setText(
                target.source() == VehicleFinderTargetState.Source.REFRESHED
                        ? R.string.finder_source_refreshed
                        : R.string.finder_source_estimated
        );
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
            tvHint.setText(R.string.finder_location_off_hint);
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
            tvHint.setText(R.string.finder_location_start_failed);
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
        tvHint.setText(R.string.finder_permission_hint);
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
            return;
        }
        if (reacquiringLocation && !isLocationFromCurrentReacquire(location)) {
            return;
        }
        if (latestObservedLocation != null
                && isOlderLocation(location, latestObservedLocation)) {
            return;
        }
        latestObservedLocation = new Location(location);
        if (reacquiringLocation) {
            handleReacquiredLocation(location);
            return;
        }
        if (currentLocation == null || shouldAcceptLocation(location)) {
            currentLocation = new Location(location);
        }
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
        if (LocationManager.NETWORK_PROVIDER.equals(candidate.getProvider())
                && LocationManager.GPS_PROVIDER.equals(currentLocation.getProvider())
                && (isFresh(currentLocation, LOCATION_GPS_FRESH_WINDOW_MS)
                || !significantlyNewer)) {
            return false;
        }
        if (candidate.hasAccuracy()
                && currentLocation.hasAccuracy()
                && candidate.getAccuracy()
                > currentLocation.getAccuracy() + 50f
                && !significantlyNewer) {
            return false;
        }
        return true;
    }

    private boolean isOlderLocation(Location candidate, Location current) {
        if (current == null) {
            return false;
        }
        long candidateElapsed = candidate.getElapsedRealtimeNanos();
        long currentElapsed = current.getElapsedRealtimeNanos();
        long toleranceNanos = LOCATION_TIMESTAMP_TOLERANCE_MS * 1_000_000L;
        if (candidateElapsed > 0L && currentElapsed > 0L) {
            return candidateElapsed + toleranceNanos < currentElapsed;
        }
        long candidateWallMs = candidate.getTime();
        long currentWallMs = current.getTime();
        return candidateWallMs > 0L
                && currentWallMs > 0L
                && candidateWallMs + LOCATION_TIMESTAMP_TOLERANCE_MS < currentWallMs;
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
        return locationTimeNanos(candidate)
                - locationTimeNanos(currentLocation)
                > LOCATION_SIGNIFICANTLY_NEWER_MS * 1_000_000L;
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
            tvHint.setText(R.string.finder_location_off_hint);
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

    private void scheduleVisualUpdate() {
        handler.removeCallbacks(visualUpdate);
        handler.postDelayed(visualUpdate, 60L);
    }

    private void updateVisualsNow() {
        handler.removeCallbacks(visualUpdate);
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

        if (!hasLocationPermission()) {
            showUnavailable(
                    R.string.finder_distance_placeholder,
                    R.string.finder_direction_placeholder,
                    getString(R.string.finder_permission_hint)
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
                            : getString(R.string.finder_location_off_hint)
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
                            : getString(R.string.finder_location_off_hint)
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
        Location qualityLocation = latestObservedLocation != null
                ? latestObservedLocation
                : currentLocation;
        boolean positionFresh = isFresh(currentLocation);
        boolean qualityFresh = isFresh(qualityLocation);
        boolean locationUsable = locationEnabled && positionFresh && qualityFresh;
        boolean providerAvailable = isProviderAvailable(qualityLocation);
        long qualityAgeMs = VehicleFinderMath.locationAgeMs(
                qualityLocation.getElapsedRealtimeNanos(),
                SystemClock.elapsedRealtimeNanos(),
                qualityLocation.getTime(),
                System.currentTimeMillis()
        );
        float accuracyMeters = qualityLocation.hasAccuracy()
                ? qualityLocation.getAccuracy()
                : 0f;
        accuracyQuality = VehicleFinderMath.accuracyQuality(
                accuracyMeters,
                accuracyQuality
        );
        logAccuracyQuality(accuracyQuality);
        tvDistance.setText(formatFinderDistance(
                distanceMeters,
                accuracyMeters,
                locationUsable
        ));
        radarView.setProximity(1f - Math.min(1f, distanceMeters / 300f));
        imgArrow.setAlpha(1f);

        boolean inEstimatedTargetArea = distanceMeters <= 8f
                || (accuracyMeters > 0f && distanceMeters <= accuracyMeters);
        boolean directionDataReady = VehicleFinderMath.canUseLocationForDirection(
                qualityAgeMs,
                accuracyMeters,
                providerAvailable
        );
        boolean canShowDirectionArrow = VehicleFinderMath.canShowDirectionArrow(
                qualityAgeMs,
                accuracyMeters,
                providerAvailable,
                hasHeading && isHeadingReliable()
        );
        String directionState;
        if (!locationUsable) {
            directionState = "location-stale";
            imgArrow.setVisibility(View.GONE);
            tvDirection.setText(R.string.finder_direction_stale);
            imgArrow.setContentDescription(getString(R.string.finder_direction_stale));
        } else if (!directionDataReady) {
            directionState = !providerAvailable
                    ? "provider-unavailable"
                    : qualityAgeMs > VehicleFinderMath.DIRECTION_MAX_AGE_MS
                    ? "fix-older-than-5s"
                    : "accuracy-over-25m";
            imgArrow.setVisibility(View.GONE);
            int directionRes = !providerAvailable
                    ? R.string.finder_direction_provider_unavailable
                    : qualityAgeMs > VehicleFinderMath.DIRECTION_MAX_AGE_MS
                    ? R.string.finder_direction_stale
                    : R.string.finder_direction_location_uncertain;
            tvDirection.setText(directionRes);
            imgArrow.setContentDescription(getString(directionRes));
        } else if (inEstimatedTargetArea) {
            directionState = "near-target";
            imgArrow.setVisibility(View.GONE);
            int directionRes = VehicleFinderMath.isReliableNearTarget(
                    distanceMeters,
                    accuracyMeters
            )
                    ? R.string.finder_direction_here
                    : R.string.finder_direction_near_uncertain;
            tvDirection.setText(directionRes);
            imgArrow.setContentDescription(getString(directionRes));
        } else if (canShowDirectionArrow) {
            directionState = "relative-arrow";
            imgArrow.setVisibility(View.VISIBLE);
            float relative = VehicleFinderMath.normalizeSignedDegrees(
                    bearing - headingDegrees
            );
            imgArrow.setRotation(relative);
            String label = relativeDirectionLabel(relative);
            tvDirection.setText(label);
            imgArrow.setContentDescription(label);
        } else {
            directionState = "map-bearing";
            imgArrow.setVisibility(View.GONE);
            String label = getString(
                    R.string.finder_direction_north_format,
                    cardinalDirection(bearing)
            );
            tvDirection.setText(label);
            imgArrow.setContentDescription(label);
        }
        logDirectionState(directionState, providerAvailable);

        if (!locationEnabled) {
            warning = appendHint(warning, getString(R.string.finder_location_off_hint));
        } else if (!positionFresh || !qualityFresh) {
            warning = appendHint(
                    warning,
                    getString(R.string.finder_current_location_stale)
            );
        } else if (accuracyQuality == VehicleFinderMath.ACCURACY_MODERATE) {
            warning = appendHint(
                    warning,
                    getString(
                            R.string.finder_hint_accuracy_moderate,
                            VehicleFinderMath.bucketAccuracyMeters(
                                    accuracyMeters,
                                    accuracyQuality
                            )
                    )
            );
        } else if (accuracyQuality == VehicleFinderMath.ACCURACY_POOR) {
            warning = appendHint(
                    warning,
                    getString(
                            R.string.finder_hint_accuracy_poor,
                            VehicleFinderMath.bucketAccuracyMeters(
                                    accuracyMeters,
                                    accuracyQuality
                            )
                    )
            );
        }
        if (directionDataReady && !directionSensorAvailable) {
            warning = appendHint(
                    warning,
                    getString(R.string.finder_hint_compass_unavailable)
            );
        } else if (directionDataReady && !isHeadingReliable()) {
            warning = appendHint(
                    warning,
                    getString(R.string.finder_hint_compass_unreliable)
            );
        }
        long targetLastUploadAtMs = target.lastUploadAtMs();
        boolean uploadTimeKnown = targetLastUploadAtMs > 0L;
        long uploadAge = uploadTimeKnown
                ? Math.max(0L, System.currentTimeMillis() - targetLastUploadAtMs)
                : 0L;
        int targetFreshness = VehicleFinderMath.targetFreshness(uploadTimeKnown, uploadAge);
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

    private void logAccuracyQuality(int quality) {
        if (quality == lastLoggedAccuracyQuality) {
            return;
        }
        lastLoggedAccuracyQuality = quality;
        DiagnosticLog.detail("FinderLoc", "accuracy=" + quality);
    }

    private void logDirectionState(String state, boolean providerAvailable) {
        String signature = state + "|" + providerAvailable + "|" + accuracyQuality;
        if (signature.equals(lastDirectionDiagnostic)) {
            return;
        }
        lastDirectionDiagnostic = signature;
        DiagnosticLog.detail(
                "FinderLoc",
                "direction=" + state
                        + " providerAvailable=" + providerAvailable
                        + " quality=" + accuracyQuality
        );
    }

    private void showUnavailable(
            int distanceRes,
            int directionRes,
            String warning
    ) {
        tvDistance.setText(distanceRes);
        tvDirection.setText(directionRes);
        imgArrow.setVisibility(View.GONE);
        radarView.setProximity(0.1f);
        setHint(warning);
    }

    private void setHint(String warning) {
        if (warning == null || warning.isEmpty()) {
            tvHint.setText("");
            tvHint.setBackground(null);
            tvHint.setVisibility(View.GONE);
        } else {
            tvHint.setBackgroundResource(R.drawable.bg_input);
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
        if (refreshingTarget || reacquiringLocation) {
            tvFreshness.setText(R.string.finder_refreshing_vehicle);
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
                uploadAgeLabel(ageMs)
        ));
    }

    private String uploadAgeLabel(long ageMs) {
        long minutes = Math.max(0L, ageMs) / 60_000L;
        if (minutes < 1L) {
            return getString(R.string.bike_upload_just_now);
        }
        if (minutes < 60L) {
            return getString(R.string.bike_upload_minutes_ago, minutes);
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return getString(R.string.bike_upload_hours_ago, hours);
        }
        return getString(R.string.bike_upload_days_ago, hours / 24L);
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
        if (accuracyQuality == VehicleFinderMath.ACCURACY_POOR && accuracyMeters > 0f) {
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

    private boolean hasLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
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

    private void registerSensors() {
        if (sensorManager == null) {
            return;
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            if (accelerometer != null) {
                sensorManager.registerListener(
                        this,
                        accelerometer,
                        SensorManager.SENSOR_DELAY_UI
                );
            }
            if (magnetometer != null) {
                sensorManager.registerListener(
                        this,
                        magnetometer,
                        SensorManager.SENSOR_DELAY_UI
                );
            }
        }
    }

    private void unregisterSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        hasHeading = false;
        hasGravity = false;
        hasGeomagnetic = false;
        headingSensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            headingSensorAccuracy = event.accuracy;
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            lowPass(event.values, gravity);
            hasGravity = true;
            if (!hasGeomagnetic
                    || !SensorManager.getRotationMatrix(
                    rotationMatrix,
                    null,
                    gravity,
                    geomagnetic
            )) {
                return;
            }
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            lowPass(event.values, geomagnetic);
            hasGeomagnetic = true;
            if (!hasGravity
                    || !SensorManager.getRotationMatrix(
                    rotationMatrix,
                    null,
                    gravity,
                    geomagnetic
            )) {
                return;
            }
        } else {
            return;
        }

        SensorManager.getOrientation(rotationMatrix, orientation);
        float reading = VehicleFinderMath.normalizeDegrees(
                (float) Math.toDegrees(orientation[0])
        );
        if (!hasHeading) {
            headingDegrees = reading;
            hasHeading = true;
        } else {
            float delta = VehicleFinderMath.normalizeSignedDegrees(reading - headingDegrees);
            headingDegrees = VehicleFinderMath.normalizeDegrees(
                    headingDegrees + delta * 0.18f
            );
        }
        scheduleVisualUpdate();
    }

    private void lowPass(float[] input, float[] output) {
        for (int index = 0; index < output.length; index++) {
            output[index] = output[index] * 0.82f + input[index] * 0.18f;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        int type = sensor.getType();
        if (type == Sensor.TYPE_ROTATION_VECTOR
                || type == Sensor.TYPE_ACCELEROMETER
                || type == Sensor.TYPE_MAGNETIC_FIELD) {
            headingSensorAccuracy = accuracy;
            scheduleVisualUpdate();
        }
    }

    private boolean isHeadingReliable() {
        return headingSensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
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
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSensors();
        startLocationTracking(!reacquiringLocation);
        scheduleLocationReacquireTimeout();
        updateVisualsNow();
        handler.removeCallbacks(freshnessTick);
        handler.postDelayed(freshnessTick, UI_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(freshnessTick);
        handler.removeCallbacks(visualUpdate);
        handler.removeCallbacks(locationReacquireTimeout);
        stopLocationTracking();
        unregisterSensors();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (repository != null) {
            repository.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(
                STATE_LOCATION_PERMISSION_REQUESTED,
                locationPermissionRequested
        );
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
            ViewGroup.LayoutParams params = header.getLayoutParams();
            params.height = headerBaseHeight + systemBars.top;
            header.setLayoutParams(params);
            header.setPadding(
                    header.getPaddingLeft(),
                    systemBars.top,
                    header.getPaddingRight(),
                    0
            );
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
