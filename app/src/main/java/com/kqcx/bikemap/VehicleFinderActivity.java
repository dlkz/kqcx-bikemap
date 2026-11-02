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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

    private static final int REQUEST_LOCATION = 5101;
    private static final int ACTION_NONE = 0;
    private static final int ACTION_PERMISSION = 1;
    private static final int ACTION_LOCATION_SETTINGS = 2;
    private static final long LOCATION_MAX_AGE_MS = 120_000L;
    private static final long UI_REFRESH_MS = 1_000L;
    private static final long TARGET_STALE_MS = 60L * 60L * 1_000L;
    private static final long LOCATION_TIMESTAMP_TOLERANCE_MS = 1_000L;
    private static final long LOCATION_SIGNIFICANTLY_NEWER_MS = 10_000L;
    private static final long LOCATION_GPS_FRESH_WINDOW_MS = 15_000L;
    private static final TimeZone CHINA_TIME_ZONE = TimeZone.getTimeZone("Asia/Shanghai");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable visualUpdate = this::updateVisuals;
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
    private Sensor rotationSensor;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private Location currentLocation;
    private TextView tvDistance;
    private TextView tvDirection;
    private TextView tvFreshness;
    private TextView tvHint;
    private ImageView imgArrow;
    private Button btnAction;
    private VehicleFinderRadarView radarView;
    private String vehicleNumber;
    private double targetLatitude;
    private double targetLongitude;
    private long targetLastUploadAtMs;
    private float headingDegrees;
    private boolean hasHeading;
    private boolean trackingLocation;
    private boolean hasGravity;
    private boolean hasGeomagnetic;
    private boolean directionSensorAvailable;
    private int currentAction = ACTION_NONE;
    private long lastVisualUpdateMs;

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
        if (!readTarget()) {
            Toast.makeText(this, R.string.finder_target_invalid, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_vehicle_finder);
        bindViews();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        setupSensors();
        applyInitialLocation();
        ((TextView) findViewById(R.id.tvFinderVehicleNumber)).setText(getString(
                R.string.finder_vehicle_title,
                vehicleNumber
        ));
        findViewById(R.id.btnFinderBack).setOnClickListener(view -> finish());
        findViewById(R.id.btnFinderBackToMap).setOnClickListener(view -> finish());
        btnAction.setOnClickListener(view -> handleAction());
        applySystemBarInsets();
        updateFreshness();
        updateVisualsNow();
    }

    private boolean readTarget() {
        Intent intent = getIntent();
        vehicleNumber = intent.getStringExtra(EXTRA_NUMBER);
        targetLatitude = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN);
        targetLongitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN);
        targetLastUploadAtMs = intent.getLongExtra(EXTRA_LAST_UPLOAD, 0L);
        return vehicleNumber != null
                && !vehicleNumber.trim().isEmpty()
                && !Double.isNaN(targetLatitude)
                && !Double.isNaN(targetLongitude)
                && targetLatitude >= -90
                && targetLatitude <= 90
                && targetLongitude >= -180
                && targetLongitude <= 180
                && (targetLatitude != 0d || targetLongitude != 0d);
    }

    private void bindViews() {
        tvDistance = findViewById(R.id.tvFinderDistance);
        tvDirection = findViewById(R.id.tvFinderDirection);
        tvFreshness = findViewById(R.id.tvFinderFreshness);
        tvHint = findViewById(R.id.tvFinderHint);
        imgArrow = findViewById(R.id.imgFinderArrow);
        btnAction = findViewById(R.id.btnFinderAction);
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
        }
    }

    private void handleAction() {
        if (currentAction == ACTION_PERMISSION) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQUEST_LOCATION
            );
        } else if (currentAction == ACTION_LOCATION_SETTINGS) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        }
    }

    private void startLocationTracking() {
        if (locationManager == null) {
            return;
        }
        if (!hasLocationPermission()) {
            showAction(
                    ACTION_PERMISSION,
                    R.string.finder_permission_hint,
                    R.string.finder_allow_location
            );
            return;
        }
        if (!isLocationEnabled()) {
            showAction(
                    ACTION_LOCATION_SETTINGS,
                    R.string.finder_location_off_hint,
                    R.string.finder_open_location_settings
            );
            return;
        }
        hideAction();
        seedFromLastKnown();
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
        if (currentLocation != null && !shouldAcceptLocation(location)) {
            return;
        }
        currentLocation = new Location(location);
        scheduleVisualUpdate();
    }

    private boolean shouldAcceptLocation(Location candidate) {
        long candidateElapsed = candidate.getElapsedRealtimeNanos();
        long currentElapsed = currentLocation.getElapsedRealtimeNanos();
        long toleranceNanos = LOCATION_TIMESTAMP_TOLERANCE_MS * 1_000_000L;
        if (candidateElapsed > 0L
                && currentElapsed > 0L
                && candidateElapsed + toleranceNanos < currentElapsed) {
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
        useLocation(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // The latest accepted fix remains on screen.
    }

    @Override
    public void onProviderEnabled(String provider) {
        startLocationTracking();
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (!isLocationEnabled()) {
            showAction(
                    ACTION_LOCATION_SETTINGS,
                    R.string.finder_location_off_hint,
                    R.string.finder_open_location_settings
            );
        }
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
        String baseHint = getString(R.string.finder_hint_map_estimate);
        String warning = "";

        if (!hasLocationPermission()) {
            showUnavailable(
                    R.string.finder_distance_placeholder,
                    R.string.finder_direction_placeholder,
                    baseHint,
                    getString(R.string.finder_permission_hint)
            );
            showAction(
                    ACTION_PERMISSION,
                    R.string.finder_permission_hint,
                    R.string.finder_allow_location
            );
            return;
        }
        if (currentLocation == null) {
            showUnavailable(
                    R.string.finder_distance_placeholder,
                    R.string.finder_direction_placeholder,
                    baseHint,
                    isLocationEnabled()
                            ? getString(R.string.finder_waiting_location)
                            : getString(R.string.finder_location_off_hint)
            );
            if (!isLocationEnabled()) {
                showAction(
                        ACTION_LOCATION_SETTINGS,
                        R.string.finder_location_off_hint,
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
                targetLatitude,
                targetLongitude,
                results
        );
        float distanceMeters = results[0];
        float bearing = results[1];
        tvDistance.setText(formatDistance(distanceMeters));
        radarView.setProximity(1f - Math.min(1f, distanceMeters / 300f));
        imgArrow.setAlpha(1f);

        if (distanceMeters <= 8f) {
            imgArrow.setVisibility(View.VISIBLE);
            tvDirection.setText(R.string.finder_direction_here);
            imgArrow.setContentDescription(getString(R.string.finder_direction_here));
        } else if (hasHeading) {
            float relative = VehicleFinderMath.normalizeSignedDegrees(
                    bearing - headingDegrees
            );
            imgArrow.setRotation(relative);
            String label = relativeDirectionLabel(relative);
            tvDirection.setText(label);
            imgArrow.setContentDescription(label);
        } else {
            imgArrow.setRotation(bearing);
            String label = getString(
                    R.string.finder_direction_north_format,
                    cardinalDirection(bearing)
            );
            tvDirection.setText(label);
            imgArrow.setContentDescription(label);
        }

        if (!isLocationEnabled()) {
            warning = appendHint(warning, getString(R.string.finder_location_off_hint));
        }
        if (!isFresh(currentLocation)) {
            warning = appendHint(
                    warning,
                    getString(R.string.finder_current_location_stale)
            );
        }
        if (currentLocation.hasAccuracy() && currentLocation.getAccuracy() > 50f) {
            warning = appendHint(warning, getString(
                    R.string.finder_hint_accuracy,
                    Math.round(currentLocation.getAccuracy())
            ));
        }
        if (!directionSensorAvailable) {
            warning = appendHint(
                    warning,
                    getString(R.string.finder_hint_compass_unavailable)
            );
        }
        long uploadAge = targetLastUploadAtMs <= 0L
                ? 0L
                : Math.max(0L, System.currentTimeMillis() - targetLastUploadAtMs);
        if (uploadAge > TARGET_STALE_MS) {
            warning = appendHint(warning, getString(R.string.finder_hint_stale));
        }
        setHint(baseHint, warning);
        if (!isLocationEnabled()) {
            showAction(
                    ACTION_LOCATION_SETTINGS,
                    R.string.finder_location_off_hint,
                    R.string.finder_open_location_settings
            );
        } else {
            hideAction();
        }
    }

    private void showUnavailable(
            int distanceRes,
            int directionRes,
            String baseHint,
            String warning
    ) {
        tvDistance.setText(distanceRes);
        tvDirection.setText(directionRes);
        imgArrow.setAlpha(0.22f);
        radarView.setProximity(0.1f);
        setHint(baseHint, warning);
    }

    private void setHint(String baseHint, String warning) {
        if (warning == null || warning.isEmpty()) {
            tvHint.setText(baseHint);
        } else {
            tvHint.setText(getString(R.string.finder_hint_combined, baseHint, warning));
        }
    }

    private String appendHint(String first, String second) {
        if (first == null || first.isEmpty()) {
            return second;
        }
        return first + "\n" + second;
    }

    private void updateFreshness() {
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

    private String formatDistance(float meters) {
        if (meters < 1_000f) {
            return getString(R.string.distance_meters_format, Math.round(meters));
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

    private void showAction(int action, int hintRes, int buttonRes) {
        currentAction = action;
        tvHint.setText(hintRes);
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
        hasGravity = false;
        hasGeomagnetic = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
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
        // The page is explicitly a coarse map estimate.
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            startLocationTracking();
            updateVisualsNow();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSensors();
        startLocationTracking();
        updateVisualsNow();
        handler.removeCallbacks(freshnessTick);
        handler.postDelayed(freshnessTick, UI_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(freshnessTick);
        handler.removeCallbacks(visualUpdate);
        stopLocationTracking();
        unregisterSensors();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
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
