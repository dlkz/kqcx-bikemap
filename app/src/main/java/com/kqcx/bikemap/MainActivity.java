package com.kqcx.bikemap;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.activity.OnBackPressedCallback;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.infowindow.InfoWindow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.io.OutputStream;

public final class MainActivity extends BaseActivity implements LocationListener {
    private static final int REQUEST_LOCATION = 1001;
    private static final int REQUEST_STORAGE = 1003;
    private static final int VEHICLE_TAIL_LENGTH = 3;
    private static final long VEHICLE_NUMBER_BASE = 100000000L;
    private static final long MARKER_TAP_GUARD_MS = 700L;
    private static final long MAP_CENTER_REFRESH_DELAY_MS = 700L;
    private static final long QR_AUTO_DELETE_MS = 30_000L;
    /**
     * The 512-pixel tile source doubles the pixel scale of a classic 256-pixel
     * source, so 16.0 keeps roughly the same view span as the old 17.0 setting.
     */
    private static final double DEFAULT_ZOOM = 16.0;

    private static final String PREF_DISCLAIMER_ACCEPTED = "disclaimer_accepted";
    private static final String PREF_USAGE_NOTICE_SHOWN = "usage_notice_shown";
    private static final String PREF_QR_SHOW_URL = "qr_show_url";
    private static final String PREF_QR_COPY_ENABLED = "qr_copy_enabled";
    private static final String PREF_ADVANCED_ENABLED = "advanced_enabled";
    private static final String PREF_PENDING_QR_DELETES = "pending_qr_deletes";

    /**
     * Neutral public-city center used only before a location fix is available.
     * It does not describe the author's or user's actual position.
     */
    private static final GeoPoint DEFAULT_CENTER = new GeoPoint(
            39.9042,
            116.4074
    );
    private static final long MIN_REFRESH_INTERVAL_MS = 10_000L;
    private static final double MIN_REFRESH_DISTANCE_METERS = 80.0;

    /**
     * Mainland China tile service using GCJ-02 coordinates and 512-pixel
     * tiles. The tiles are drawn one-to-one with screen pixels so the high
     * resolution source is not upscaled and blurred on high-DPI phones.
     */
    private static final OnlineTileSourceBase MAP_TILE_SOURCE = new OnlineTileSourceBase(
            "AutoNaviRoadHD",
            4,
            18,
            512,
            ".png",
            new String[]{
                    "https://wprd01.is.autonavi.com/",
                    "https://wprd02.is.autonavi.com/",
                    "https://wprd03.is.autonavi.com/",
                    "https://wprd04.is.autonavi.com/"
            },
            "高德地图"
    ) {
        @Override
        public String getTileURLString(long mapTileIndex) {
            return getBaseUrl()
                    + "appmaptile?x="
                    + MapTileIndex.getX(mapTileIndex)
                    + "&y=" + MapTileIndex.getY(mapTileIndex)
                    + "&z=" + MapTileIndex.getZoom(mapTileIndex)
                    + "&lang=zh_cn&size=1&scl=2&style=8";
        }
    };

    private final BikeRepository repository = new BikeRepository();
    private final List<Bike> bikes = new ArrayList<>();
    private final Map<Long, Marker> bikeMarkers = new HashMap<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshFromMapCenterRunnable = this::refreshFromMapCenter;

    private AppMapView mapView;
    private TextView tvStatus;
    private TextView tvBikeNumber;
    private TextView tvBikeMeta;
    private TextView tvManualNotice;
    private TextView tvSettingsVersion;
    private LinearLayout bikePanel;
    private View settingsPanel;
    private Button btnShowQr;
    private ImageButton btnRefresh;
    private LinearLayout rowAdvanced;
    private CheckBox checkAdvanced;
    private CheckBox checkThemeSystem;
    private CheckBox checkThemeLight;
    private CheckBox checkThemeDark;
    private LinearLayout advancedContainer;
    private Switch switchQrUrl;
    private Switch switchQrCopy;

    private LocationManager locationManager;
    private Location currentLocation;
    private BikeCallout bikeCallout;
    private MapLabelOverlay mapLabelOverlay;
    private Marker userMarker;
    private GeoPoint lastRequestedCenter;
    private GeoPoint pendingRequestCenter;
    private Bike selectedBike;
    private long lastFetchAtMs;
    private long lastMarkerTapAt;
    private int systemBarsBottom;
    private long pendingQrNumber = -1;
    private Bitmap pendingQrBitmap;
    private boolean pendingOpenWeChatAfterQrSave;
    private boolean requestInFlight;
    private boolean pendingRequestForce;
    private boolean permissionDialogShowing;
    private boolean initialLocationCentered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        org.osmdroid.config.Configuration.getInstance().load(
                getApplicationContext(),
                getSharedPreferences("osmdroid", MODE_PRIVATE)
        );
        org.osmdroid.config.Configuration.getInstance().setUserAgentValue(getPackageName());
        org.osmdroid.config.Configuration.getInstance().setTileDownloadThreads((short) 4);

        setContentView(R.layout.activity_main);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        bindViews();
        applySystemBarInsets();
        configureMap();
        setupClickListeners();
        setupSettings();
        cleanupExpiredQrSaves();
        showStartupFlow();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (settingsPanel.getVisibility() == View.VISIBLE) {
                    hideSettings();
                    return;
                }
                if (bikePanel.getVisibility() == View.VISIBLE) {
                    hideBikePanel();
                    return;
                }
                finish();
            }
        });
    }

    private void bindViews() {
        mapView = findViewById(R.id.map);
        tvStatus = findViewById(R.id.tvStatus);
        tvBikeNumber = findViewById(R.id.tvBikeNumber);
        tvBikeMeta = findViewById(R.id.tvBikeMeta);
        tvManualNotice = findViewById(R.id.tvManualNotice);
        bikePanel = findViewById(R.id.bikePanel);
        settingsPanel = findViewById(R.id.settingsPanel);
        tvSettingsVersion = findViewById(R.id.tvSettingsVersion);
        checkAdvanced = findViewById(R.id.checkAdvanced);
        checkThemeSystem = findViewById(R.id.checkThemeSystem);
        checkThemeLight = findViewById(R.id.checkThemeLight);
        checkThemeDark = findViewById(R.id.checkThemeDark);
        rowAdvanced = findViewById(R.id.rowAdvanced);
        advancedContainer = findViewById(R.id.advancedContainer);
        switchQrUrl = findViewById(R.id.switchQrUrl);
        switchQrCopy = findViewById(R.id.switchQrCopy);
        btnShowQr = findViewById(R.id.btnShowQr);
        btnRefresh = findViewById(R.id.btnRefresh);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.root);
        View topBanner = findViewById(R.id.topBanner);
        View settingsHeader = findViewById(R.id.settingsHeader);
        View bottomArea = findViewById(R.id.bottomArea);
        int topBannerBaseMargin = dp(16);
        int settingsHeaderBaseHeight = dp(64);
        int bottomAreaBasePadding = dp(14);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            systemBarsBottom = systemBars.bottom;

            ViewGroup.MarginLayoutParams bannerParams =
                    (ViewGroup.MarginLayoutParams) topBanner.getLayoutParams();
            bannerParams.topMargin = topBannerBaseMargin + systemBars.top;
            topBanner.setLayoutParams(bannerParams);

            ViewGroup.LayoutParams headerParams = settingsHeader.getLayoutParams();
            headerParams.height = settingsHeaderBaseHeight + systemBars.top;
            settingsHeader.setLayoutParams(headerParams);
            settingsHeader.setPadding(
                    settingsHeader.getPaddingLeft(),
                    systemBars.top,
                    settingsHeader.getPaddingRight(),
                    settingsHeader.getPaddingBottom()
            );

            bottomArea.setPadding(
                    bottomArea.getPaddingLeft(),
                    bottomArea.getPaddingTop(),
                    bottomArea.getPaddingRight(),
                    bottomAreaBasePadding + systemBars.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void configureMap() {
        mapView.setTileSource(MAP_TILE_SOURCE);
        mapView.setTilesScaledToDpi(false);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(false);
        mapView.setFlingEnabled(true);
        mapView.setMinZoomLevel(4.0);
        mapView.setMaxZoomLevel(18.0);
        mapView.getController().setZoom(DEFAULT_ZOOM);
        mapView.getController().setCenter(DEFAULT_CENTER);
        mapView.setOnMapGestureListener(new AppMapView.OnMapGestureListener() {
            @Override
            public void onSingleTapConfirmed() {
                handleMapBackgroundTap();
            }

            @Override
            public void onTripleTap(
                    double zoomLevelBeforeSequence,
                    org.osmdroid.api.IGeoPoint centerBeforeSequence
            ) {
                // osmdroid runs its own double-tap zoom-in before the third tap is
                // recognized, so always write an absolute target zoom here instead of
                // relying on a relative zoom-out: otherwise a triple tap at the default
                // zoom level would leave the map stuck one level in.
                double targetZoom = zoomLevelBeforeSequence > DEFAULT_ZOOM
                        ? zoomLevelBeforeSequence - 1.0
                        : zoomLevelBeforeSequence;
                // A double tap starts an animated zoom-in that keeps running after
                // this callback, so it has to be cancelled first or it would overwrite
                // the zoom level written below.
                mapView.getController().stopAnimation(false);
                mapView.getController().setZoom(targetZoom);
                if (centerBeforeSequence != null) {
                    mapView.getController().setCenter(centerBeforeSequence);
                }
            }
        });
        mapView.addMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                updateViewportStatus();
                scheduleMapCenterRefresh();
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                updateViewportStatus();
                updateMapLabels();
                scheduleMapCenterRefresh();
                return false;
            }
        });
    }

    private void setupClickListeners() {
        findViewById(R.id.btnLocate).setOnClickListener(view -> {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                requestLocationPermission();
                return;
            }

            // Tapping locate also re-registers, so the button recovers the fix
            // even if the system dropped updates while the app was in the
            // background.
            startLocationUpdates();
            if (currentLocation == null) {
                return;
            }

            GeoPoint displayPoint = CoordinateUtils.wgs84ToGcj02(
                    currentLocation.getLatitude(),
                    currentLocation.getLongitude()
            );
            mapView.getController().animateTo(displayPoint);
            loadBikes(displayPoint, true);
        });

        findViewById(R.id.btnList).setOnClickListener(view -> showBikeList());
        findViewById(R.id.btnRefresh).setOnClickListener(view -> {
            GeoPoint center = mapCenterGcj02();
            loadBikes(center, true);
        });
        findViewById(R.id.btnScan).setOnClickListener(view -> openWeChatScanner());
        findViewById(R.id.btnInputNumber).setOnClickListener(view -> showNumberInput());
        findViewById(R.id.btnSettings).setOnClickListener(view -> showSettings());
        btnShowQr.setOnClickListener(view -> {
            if (selectedBike != null) {
                showQrDialog(selectedBike);
            }
        });
    }

    private void setupSettings() {
        findViewById(R.id.btnSettingsBack).setOnClickListener(view -> hideSettings());
        findViewById(R.id.rowVersion).setOnClickListener(view ->
                startActivity(new Intent(this, AboutActivity.class))
        );
        findViewById(R.id.rowDisclaimer).setOnClickListener(view ->
                showDisclaimerDialog(false)
        );
        findViewById(R.id.rowCredits).setOnClickListener(view -> showInfoDialog(
                getString(R.string.settings_credits),
                getString(R.string.credits_content)
        ));
        findViewById(R.id.rowPrivacy).setOnClickListener(view -> showPrivacyDialog());

        tvSettingsVersion.setText(getString(R.string.version_format, versionName()));
        String theme = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                .getString(PREF_THEME, THEME_SYSTEM);
        showSelectedTheme(theme);
        findViewById(R.id.rowThemeSystem).setOnClickListener(view ->
                selectTheme(THEME_SYSTEM)
        );
        findViewById(R.id.rowThemeLight).setOnClickListener(view ->
                selectTheme(THEME_LIGHT)
        );
        findViewById(R.id.rowThemeDark).setOnClickListener(view ->
                selectTheme(THEME_DARK)
        );

        SharedPreferences preferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        switchQrUrl.setChecked(preferences.getBoolean(PREF_QR_SHOW_URL, false));
        switchQrCopy.setChecked(preferences.getBoolean(PREF_QR_COPY_ENABLED, false));
        switchQrUrl.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(PREF_QR_SHOW_URL, checked).apply()
        );
        switchQrCopy.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(PREF_QR_COPY_ENABLED, checked).apply()
        );

        boolean advancedEnabled = preferences.getBoolean(PREF_ADVANCED_ENABLED, false);
        checkAdvanced.setChecked(advancedEnabled);
        advancedContainer.setVisibility(advancedEnabled ? View.VISIBLE : View.GONE);
        checkAdvanced.setOnCheckedChangeListener((button, checked) -> {
            advancedContainer.setVisibility(checked ? View.VISIBLE : View.GONE);
            preferences.edit().putBoolean(PREF_ADVANCED_ENABLED, checked).apply();
        });
        rowAdvanced.setOnClickListener(view -> checkAdvanced.toggle());
    }

    private void showSelectedTheme(String theme) {
        checkThemeSystem.setChecked(THEME_SYSTEM.equals(theme));
        checkThemeLight.setChecked(THEME_LIGHT.equals(theme));
        checkThemeDark.setChecked(THEME_DARK.equals(theme));
    }

    private void selectTheme(String newTheme) {
        SharedPreferences preferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        showSelectedTheme(newTheme);
        if (!newTheme.equals(preferences.getString(PREF_THEME, THEME_SYSTEM))) {
            preferences.edit().putString(PREF_THEME, newTheme).apply();
            recreate();
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException exception) {
            return "1.0.1";
        }
    }

    private void showStartupFlow() {
        SharedPreferences preferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        if (!preferences.getBoolean(PREF_DISCLAIMER_ACCEPTED, false)) {
            showDisclaimerDialog(true);
            return;
        }
        showFirstUseNoticeIfNeeded();
    }

    /**
     * Used both on first launch and from the settings entry. Both entry points
     * have the same choices: agree to continue, or exit the app.
     */
    private void showDisclaimerDialog(boolean startup) {
        View content = createInfoContent(getString(R.string.disclaimer_content));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.disclaimer_title)
                .setView(content)
                .setCancelable(false)
                .setPositiveButton(R.string.disclaimer_agree, (ignored, which) -> {
                    getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_DISCLAIMER_ACCEPTED, true)
                            .apply();
                    if (startup) {
                        showFirstUseNoticeIfNeeded();
                    }
                })
                .setNegativeButton(R.string.disclaimer_exit, (ignored, which) ->
                        exitApplication()
                )
                .create();
        dialog.show();
        applyInfoDialogSize(dialog, content);
    }

    private void showFirstUseNoticeIfNeeded() {
        SharedPreferences preferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        if (preferences.getBoolean(PREF_USAGE_NOTICE_SHOWN, false)) {
            startInitialPermissionFlow();
            return;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.usage_notice_title)
                .setMessage(R.string.usage_notice_message)
                .setCancelable(false)
                .setPositiveButton(R.string.usage_notice_confirm, (ignored, which) -> {
                    preferences.edit()
                            .putBoolean(PREF_USAGE_NOTICE_SHOWN, true)
                            .apply();
                    startInitialPermissionFlow();
                })
                .create();
        dialog.show();
    }

    private void startInitialPermissionFlow() {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startLocationUpdates();
        } else {
            requestLocationPermission();
        }
    }

    private void showSettings() {
        settingsPanel.setVisibility(View.VISIBLE);
    }

    private void hideSettings() {
        settingsPanel.setVisibility(View.GONE);
    }

    private void showPrivacyDialog() {
        View content = createInfoContent(getString(R.string.privacy_content));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_privacy)
                .setView(content)
                .setCancelable(false)
                .setPositiveButton(R.string.disclaimer_agree, null)
                .setNegativeButton(R.string.disclaimer_exit, (ignored, which) ->
                        exitApplication()
                )
                .create();
        dialog.show();
        applyInfoDialogSize(dialog, content);
    }

    private void showInfoDialog(String title, String message) {
        View content = createInfoContent(message);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.show();
        applyInfoDialogSize(dialog, content);
    }

    private View createInfoContent(String message) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_info, null);
        LinearLayout sections = content.findViewById(R.id.infoSections);

        String[] blocks = message.split("\\n\\n");
        for (String block : blocks) {
            String[] lines = block.split("\\n", 2);
            String headingText = lines[0].trim();
            if (headingText.isEmpty()) {
                continue;
            }

            TextView heading = textView(
                    headingText,
                    15,
                    R.color.text_primary,
                    true
            );
            if (sections.getChildCount() > 0) {
                heading.setPadding(0, dp(14), 0, 0);
            }
            sections.addView(heading);

            if (lines.length < 2 || lines[1].trim().isEmpty()) {
                continue;
            }
            TextView body = textView(
                    lines[1].trim(),
                    14,
                    R.color.text_secondary,
                    false
            );
            Linkify.addLinks(body, Linkify.WEB_URLS);
            body.setMovementMethod(LinkMovementMethod.getInstance());
            body.setLinkTextColor(getColor(R.color.brand_green));
            body.setLineSpacing(dp(3), 1.0f);
            body.setPadding(0, dp(4), 0, 0);
            sections.addView(body);
        }
        return content;
    }

    private void applyInfoDialogSize(AlertDialog dialog, View content) {
        ScrollView scrollView = content.findViewById(R.id.infoScroll);
        int dialogWidth = Math.round(
                getResources().getDisplayMetrics().widthPixels * 0.92f
        );
        int maxContentHeight = Math.round(
                getResources().getDisplayMetrics().heightPixels * 0.68f
        );
        content.measure(
                View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxContentHeight, View.MeasureSpec.AT_MOST)
        );
        int contentHeight = Math.min(
                maxContentHeight,
                content.getMeasuredHeight()
        );
        scrollView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                contentHeight
        ));

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(
                    dialogWidth,
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void exitApplication() {
        finishAffinity();
        System.exit(0);
    }

    private void handleMapBackgroundTap() {
        if (SystemClock.uptimeMillis() - lastMarkerTapAt < MARKER_TAP_GUARD_MS) {
            return;
        }
        if (settingsPanel.getVisibility() == View.VISIBLE) {
            hideSettings();
        } else if (bikePanel.getVisibility() == View.VISIBLE) {
            hideBikePanel();
        }
    }

    private void hideBikePanel() {
        for (Marker marker : bikeMarkers.values()) {
            marker.closeInfoWindow();
        }
        bikePanel.setVisibility(View.GONE);
        selectedBike = null;
        updateMarkerSelection();
    }

    private void requestLocationPermission() {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startLocationUpdates();
            return;
        }
        showPermissionExplanation(
                R.string.permission_location_title,
                R.string.permission_location_message,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                REQUEST_LOCATION
        );
    }

    private void showPermissionExplanation(
            int titleRes,
            int messageRes,
            String[] permissions,
            int requestCode
    ) {
        if (permissionDialogShowing) {
            return;
        }
        permissionDialogShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(
                        R.string.permission_continue,
                        (ignored, which) -> requestPermissions(permissions, requestCode)
                )
                .setNegativeButton(R.string.permission_not_now, (ignored, which) -> {
                    if (requestCode == REQUEST_LOCATION) {
                        tvStatus.setText(R.string.permission_location);
                    } else if (requestCode == REQUEST_STORAGE) {
                        pendingQrBitmap = null;
                        pendingQrNumber = -1;
                        pendingOpenWeChatAfterQrSave = false;
                    }
                })
                .create();
        dialog.setOnDismissListener(ignored -> permissionDialogShowing = false);
        dialog.show();
    }

    private void loadBikes(GeoPoint center, boolean force) {
        if (center == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (requestInFlight) {
            pendingRequestCenter = new GeoPoint(
                    center.getLatitude(),
                    center.getLongitude()
            );
            pendingRequestForce = pendingRequestForce || force;
            return;
        }
        if (!force
                && lastRequestedCenter != null
                && distanceBetween(lastRequestedCenter, center) < MIN_REFRESH_DISTANCE_METERS
                && now - lastFetchAtMs < MIN_REFRESH_INTERVAL_MS) {
            return;
        }

        requestInFlight = true;
        lastRequestedCenter = new GeoPoint(center.getLatitude(), center.getLongitude());
        lastFetchAtMs = now;
        tvStatus.setText(R.string.status_loading);
        btnRefresh.setEnabled(false);

        repository.fetchNearby(
                center.getLatitude(),
                center.getLongitude(),
                new BikeRepository.Callback() {
                    @Override
                    public void onSuccess(List<Bike> result, long fetchedAt) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        bikes.clear();
                        bikes.addAll(result);
                        renderMap();
                        finishLoadRequest();
                        updateViewportStatus();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        tvStatus.setText(message);
                        finishLoadRequest();
                    }
                }
        );
    }

    private void finishLoadRequest() {
        requestInFlight = false;
        btnRefresh.setEnabled(true);
        if (pendingRequestCenter == null) {
            return;
        }

        GeoPoint nextCenter = pendingRequestCenter;
        boolean nextForce = pendingRequestForce;
        pendingRequestCenter = null;
        pendingRequestForce = false;
        loadBikes(nextCenter, nextForce);
    }

    private void scheduleMapCenterRefresh() {
        uiHandler.removeCallbacks(refreshFromMapCenterRunnable);
        uiHandler.postDelayed(
                refreshFromMapCenterRunnable,
                MAP_CENTER_REFRESH_DELAY_MS
        );
    }

    private void refreshFromMapCenter() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        loadBikes(mapCenterGcj02(), false);
    }

    private GeoPoint mapCenterGcj02() {
        return new GeoPoint(
                mapView.getMapCenter().getLatitude(),
                mapView.getMapCenter().getLongitude()
        );
    }

    /**
     * The top banner describes what is actually visible right now, while the
     * list keeps every vehicle from the last query, including ones outside the
     * current viewport.
     */
    private void updateViewportStatus() {
        if (requestInFlight || isFinishing() || isDestroyed()) {
            return;
        }
        if (bikes.isEmpty()) {
            tvStatus.setText(R.string.status_no_bikes);
            return;
        }
        if (mapView.getWidth() == 0 || mapView.getHeight() == 0) {
            tvStatus.setText(getString(R.string.status_bikes, bikes.size()));
            return;
        }

        BoundingBox visible = mapView.getBoundingBox();
        int visibleAvailable = 0;
        for (Bike bike : bikes) {
            if (bike.locationKnown
                    && bike.available
                    && visible.contains(bike.latitude, bike.longitude)) {
                visibleAvailable++;
            }
        }
        if (visibleAvailable > 0) {
            tvStatus.setText(getString(R.string.status_bikes, visibleAvailable));
        } else {
            tvStatus.setText(R.string.status_no_available);
        }
    }

    private void renderMap() {
        InfoWindow.closeAllInfoWindowsOn(mapView);
        mapView.getOverlays().clear();
        bikeMarkers.clear();
        mapLabelOverlay = null;
        if (bikeCallout == null) {
            bikeCallout = new BikeCallout(mapView);
        }

        if (currentLocation != null) {
            if (userMarker == null) {
                userMarker = new Marker(mapView);
                userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                userMarker.setIcon(getDrawable(R.drawable.ic_user_location));
                userMarker.setTitle("我的位置");
            }
            userMarker.setPosition(CoordinateUtils.wgs84ToGcj02(
                    currentLocation.getLatitude(),
                    currentLocation.getLongitude()
            ));
            mapView.getOverlays().add(userMarker);
        }
        if (userMarker != null && currentLocation == null) {
            userMarker = null;
        }

        for (Bike bike : bikes) {
            if (!bike.locationKnown) {
                continue;
            }
            Marker marker = new Marker(mapView);
            // The vehicle service already returns GCJ-02 coordinates.
            marker.setPosition(new GeoPoint(bike.latitude, bike.longitude));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marker.setIcon(getDrawable(
                    selectedBike != null && selectedBike.number == bike.number
                            ? R.drawable.ic_bike_marker_selected
                            : R.drawable.ic_bike_marker
            ));
            marker.setTitle(String.valueOf(bike.number));
            marker.setInfoWindow(bikeCallout);
            marker.setOnMarkerClickListener((clickedMarker, map) -> {
                lastMarkerTapAt = SystemClock.uptimeMillis();
                showBikeDetail(bike, false);
                return true;
            });
            bikeMarkers.put(bike.number, marker);
            mapView.getOverlays().add(marker);
        }

        updateMapLabels();
        mapView.invalidate();
    }

    /**
     * The Amap raster tiles carry no text labels, so the real campus and point
     * names returned by the vehicle service are drawn as map labels instead.
     */
    private void updateMapLabels() {
        if (mapView == null) {
            return;
        }
        if (mapLabelOverlay != null) {
            mapView.getOverlays().remove(mapLabelOverlay);
            mapLabelOverlay = null;
        }
        List<MapLabelOverlay.Label> labels = buildMapLabels();
        if (labels.isEmpty()) {
            mapView.invalidate();
            return;
        }
        mapLabelOverlay = new MapLabelOverlay(this, labels);
        mapView.getOverlays().add(0, mapLabelOverlay);
        mapView.invalidate();
    }

    private List<MapLabelOverlay.Label> buildMapLabels() {
        List<MapLabelOverlay.Label> labels = new ArrayList<>();
        Map<String, double[]> pointGroups = new LinkedHashMap<>();
        for (Bike bike : bikes) {
            if (!bike.locationKnown) {
                continue;
            }
            String pointName = mapPointName(bike);
            if (pointName.isEmpty()) {
                continue;
            }
            double[] group = pointGroups.get(pointName);
            if (group == null) {
                group = new double[]{0, 0, 0};
                pointGroups.put(pointName, group);
            }
            group[0] += bike.latitude;
            group[1] += bike.longitude;
            group[2] += 1;
        }
        for (Map.Entry<String, double[]> entry : pointGroups.entrySet()) {
            double[] group = entry.getValue();
            labels.add(new MapLabelOverlay.Label(
                    new GeoPoint(group[0] / group[2], group[1] / group[2]),
                    entry.getKey(),
                    true
            ));
        }
        return labels;
    }

    private String mapPointName(Bike bike) {
        String point = bike.siteName == null ? "" : bike.siteName.trim();
        if (!point.isEmpty()) {
            return point;
        }
        return bike.serviceSiteName == null ? "" : bike.serviceSiteName.trim();
    }

    private void showBikeDetail(Bike bike, boolean moveCamera) {
        selectedBike = bike;
        updateMarkerSelection();
        bikePanel.setVisibility(View.VISIBLE);
        tvBikeNumber.setText(getString(R.string.number_format, bike.number));
        tvManualNotice.setVisibility(bike.locationKnown ? View.GONE : View.VISIBLE);

        if (!bike.locationKnown) {
            tvBikeMeta.setText(R.string.bike_meta_manual);
        } else {
            String detail = bike.status;
            if (!bike.model.isEmpty()) {
                detail += " · " + bike.model;
            }
            tvBikeMeta.setText(getString(
                    R.string.bike_meta,
                    formatBattery(bike.batteryPercent),
                    formatDistance(bike.distanceMeters),
                    detail
            ));
        }

        Marker marker = bikeMarkers.get(bike.number);
        if (marker != null) {
            marker.showInfoWindow();
        }
        if (moveCamera && bike.locationKnown) {
            mapView.getController().animateTo(new GeoPoint(
                    bike.latitude,
                    bike.longitude
            ));
        }
    }

    private void updateMarkerSelection() {
        for (Map.Entry<Long, Marker> entry : bikeMarkers.entrySet()) {
            boolean selected = selectedBike != null
                    && selectedBike.number == entry.getKey();
            entry.getValue().setIcon(getDrawable(
                    selected
                            ? R.drawable.ic_bike_marker_selected
                            : R.drawable.ic_bike_marker
            ));
        }
        mapView.invalidate();
    }

    private void showBikeList() {
        if (bikes.isEmpty()) {
            Toast.makeText(this, R.string.status_no_bikes, Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_sheet);
        content.setPadding(dp(18), dp(14), dp(18), dp(18) + systemBarsBottom);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = textView(getString(R.string.list_title), 20, R.color.text_primary, true);
        TextView subtitle = textView(
                getString(R.string.list_subtitle, bikes.size()),
                12,
                R.color.text_secondary,
                false
        );
        heading.addView(title);
        heading.addView(subtitle);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setBackgroundResource(android.R.drawable.list_selector_background);
        close.setContentDescription(getString(R.string.close));
        close.setPadding(dp(10), dp(10), dp(10), dp(10));
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        content.addView(header);
        close.setOnClickListener(view -> dialog.dismiss());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        for (Bike bike : bikes) {
            rows.addView(createBikeRow(bike, dialog));
        }

        int maxSheetHeight = Math.max(
                dp(260),
                getResources().getDisplayMetrics().heightPixels - dp(48)
        );
        int desiredSheetHeight = dp(108) + bikes.size() * dp(78);
        int sheetHeight = Math.min(maxSheetHeight, Math.max(dp(260), desiredSheetHeight));
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                sheetHeight
        ));
        content.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = sheetHeight;
            params.gravity = Gravity.BOTTOM;
            params.dimAmount = 0.3f;
            window.setAttributes(params);
        }
        dialog.show();
    }

    private View createBikeRow(Bike bike, Dialog dialog) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(9), dp(8), dp(9));
        row.setBackgroundResource(R.drawable.bg_list_row);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_bike_marker);
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, dp(8), 0);
        labels.addView(textView(
                "#" + bike.number,
                17,
                R.color.text_primary,
                true
        ));

        String site = siteDisplayName(bike);
        if (!site.isEmpty()) {
            labels.addView(textView(site, 13, R.color.text_secondary, false));
        }
        labels.addView(textView(
                formatDistance(bike.distanceMeters)
                        + " · " + formatBatteryLabel(bike)
                        + " · " + bike.status,
                11,
                R.color.text_tertiary,
                false
        ));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView action = textView("查看", 13, R.color.brand_green_dark, true);
        action.setGravity(Gravity.CENTER);
        row.addView(action, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(view -> {
            dialog.dismiss();
            showBikeDetail(bike, true);
        });
        return row;
    }

    /**
     * The vehicle service returns both the wider campus and the exact point.
     * Showing them together keeps short names such as "大楼梯" understandable.
     */
    private String siteDisplayName(Bike bike) {
        String point = bike.siteName == null ? "" : bike.siteName.trim();
        String campus = bike.serviceSiteName == null ? "" : bike.serviceSiteName.trim();
        if (point.isEmpty()) {
            return campus;
        }
        if (campus.isEmpty() || point.equals(campus) || point.startsWith(campus)) {
            return point;
        }
        return campus + " · " + point;
    }

    private TextView textView(String text, int sizeSp, int colorRes, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        textView.setTextColor(getColor(colorRes));
        if (bold) {
            textView.setTypeface(textView.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return textView;
    }

    private void showNumberInput() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_number_input, null);
        EditText input = content.findViewById(R.id.inputTail);
        TextView preview = content.findViewById(R.id.tvInputPreview);
        TextView error = content.findViewById(R.id.tvInputError);
        input.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(VEHICLE_TAIL_LENGTH),
                (source, start, end, destination, destinationStart, destinationEnd) -> {
                    for (int index = start; index < end; index++) {
                        if (!Character.isDigit(source.charAt(index))) {
                            return "";
                        }
                    }
                    return null;
                }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.input_title)
                .setView(content)
                .setPositiveButton(R.string.confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
                // No-op.
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                String tail = value.toString();
                preview.setVisibility(tail.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                preview.setText(getString(R.string.input_preview, tail));
                error.setVisibility(View.GONE);
                if (dialog.isShowing()) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(
                            tail.length() == VEHICLE_TAIL_LENGTH
                    );
                }
            }

            @Override
            public void afterTextChanged(Editable value) {
                // No-op.
            }
        });

        dialog.setOnShowListener(ignored -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setEnabled(false);
            confirm.setOnClickListener(view -> {
                String tail = input.getText().toString();
                if (tail.length() != VEHICLE_TAIL_LENGTH) {
                    error.setText(R.string.invalid_number);
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                long number = VEHICLE_NUMBER_BASE + Long.parseLong(tail);
                Bike bike = findOrCreateBike(number);
                dialog.dismiss();
                showBikeDetail(bike, true);
            });
            input.requestFocus();
        });
        dialog.show();
    }

    private Bike findOrCreateBike(long number) {
        for (Bike bike : bikes) {
            if (bike.number == number) {
                return bike;
            }
        }
        return Bike.manual(number);
    }

    private void showQrDialog(Bike bike) {
        Bitmap qrBitmap;
        try {
            qrBitmap = new BarcodeEncoder().encodeBitmap(
                    bike.qrUrl(),
                    BarcodeFormat.QR_CODE,
                    720,
                    720
            );
        } catch (WriterException exception) {
            Toast.makeText(this, R.string.qr_generate_failed, Toast.LENGTH_LONG)
                    .show();
            return;
        }

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_qr, null);
        ImageView imageView = content.findViewById(R.id.qrImage);
        TextView urlView = content.findViewById(R.id.tvQrUrl);
        Button copyButton = content.findViewById(R.id.btnCopyQr);
        Button wechatButton = content.findViewById(R.id.btnQrWechat);
        View qrActions = content.findViewById(R.id.qrActions);
        SharedPreferences preferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        imageView.setImageBitmap(qrBitmap);
        urlView.setText(bike.qrUrl());
        urlView.setVisibility(
                preferences.getBoolean(PREF_QR_SHOW_URL, false)
                        ? View.VISIBLE
                        : View.GONE
        );
        qrActions.setVisibility(
                preferences.getBoolean(PREF_QR_COPY_ENABLED, false)
                        ? View.VISIBLE
                        : View.GONE
        );

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("车辆二维码 · " + bike.number)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .create();

        imageView.setOnClickListener(view -> saveQrToGallery(qrBitmap, bike.number));
        copyButton.setOnClickListener(view -> copyToClipboard(bike.qrUrl()));
        wechatButton.setOnClickListener(
                view -> saveQrAndOpenWeChat(qrBitmap, bike.number)
        );
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(
                    Math.round(getResources().getDisplayMetrics().widthPixels * 0.92f),
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void saveQrAndOpenWeChat(Bitmap bitmap, long number) {
        pendingOpenWeChatAfterQrSave = true;
        saveQrToGallery(bitmap, number);
    }

    private void saveQrToGallery(Bitmap bitmap, long number) {
        if (bitmap == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pendingQrBitmap = bitmap;
            pendingQrNumber = number;
            showPermissionExplanation(
                    R.string.permission_storage_title,
                    R.string.permission_storage_message,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE
            );
            return;
        }
        openWeChatAfterQrSaveIfNeeded(performQrSave(bitmap, number));
    }

    private boolean performQrSave(Bitmap bitmap, long number) {
        // When the save is only a preparation step for opening WeChat, the
        // system Toast would keep floating above WeChat's scanner and cover
        // its album button, so no in-app hint is shown on that path.
        boolean openWeChatAfterSave = pendingOpenWeChatAfterQrSave;
        String fileName = "bike_qr_" + number + "_" + System.currentTimeMillis() + ".png";
        Uri savedUri = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/BikeMap"
                );
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                savedUri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                );
                if (savedUri == null) {
                    throw new IOException("无法创建相册文件");
                }
                try (OutputStream output = getContentResolver().openOutputStream(savedUri)) {
                    if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IOException("无法写入二维码图片");
                    }
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(savedUri, values, null, null);
            } else {
                String uriString = MediaStore.Images.Media.insertImage(
                        getContentResolver(),
                        bitmap,
                        fileName,
                        "车辆二维码"
                );
                if (uriString == null || uriString.trim().isEmpty()) {
                    throw new IOException("无法写入相册");
                }
                savedUri = Uri.parse(uriString);
            }

            scheduleQrDeletion(savedUri);
            if (!openWeChatAfterSave) {
                Toast.makeText(this, R.string.qr_saved, Toast.LENGTH_LONG).show();
            }
            return true;
        } catch (Exception exception) {
            if (savedUri != null) {
                try {
                    getContentResolver().delete(savedUri, null, null);
                } catch (Exception ignored) {
                    // The incomplete file is left for the system media scanner.
                }
            }
            Toast.makeText(this, R.string.qr_save_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void openWeChatAfterQrSaveIfNeeded(boolean saved) {
        boolean shouldOpenWeChat = pendingOpenWeChatAfterQrSave;
        pendingOpenWeChatAfterQrSave = false;
        if (saved && shouldOpenWeChat) {
            openWeChatScanner();
        }
    }

    private void scheduleQrDeletion(Uri uri) {
        long expiresAt = System.currentTimeMillis() + QR_AUTO_DELETE_MS;
        rememberPendingQr(uri, expiresAt);
        uiHandler.postDelayed(
                () -> deletePendingQr(uri, true),
                QR_AUTO_DELETE_MS
        );
    }

    private void cleanupExpiredQrSaves() {
        SharedPreferences preferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        Set<String> stored = preferences.getStringSet(PREF_PENDING_QR_DELETES, null);
        if (stored == null || stored.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (String entry : new HashSet<>(stored)) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length != 2) {
                removePendingQr(entry);
                continue;
            }
            try {
                long expiresAt = Long.parseLong(parts[0]);
                Uri uri = Uri.parse(parts[1]);
                if (expiresAt <= now) {
                    deletePendingQr(uri, false);
                } else {
                    uiHandler.postDelayed(
                            () -> deletePendingQr(uri, false),
                            expiresAt - now
                    );
                }
            } catch (NumberFormatException exception) {
                removePendingQr(entry);
            }
        }
    }

    private void rememberPendingQr(Uri uri, long expiresAt) {
        SharedPreferences preferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        Set<String> stored = new HashSet<>(
                preferences.getStringSet(PREF_PENDING_QR_DELETES, new HashSet<>())
        );
        stored.add(expiresAt + "|" + uri);
        preferences.edit().putStringSet(PREF_PENDING_QR_DELETES, stored).apply();
    }

    private void deletePendingQr(Uri uri, boolean showFailure) {
        try {
            getContentResolver().delete(uri, null, null);
            removePendingQr("|" + uri);
        } catch (Exception exception) {
            if (showFailure) {
                Toast.makeText(this, R.string.qr_delete_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void removePendingQr(String entryOrUriSuffix) {
        SharedPreferences preferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        Set<String> stored = new HashSet<>(
                preferences.getStringSet(PREF_PENDING_QR_DELETES, new HashSet<>())
        );
        Set<String> updated = new HashSet<>();
        for (String entry : stored) {
            if (!entry.equals(entryOrUriSuffix)
                    && !entry.endsWith(entryOrUriSuffix)) {
                updated.add(entry);
            }
        }
        preferences.edit().putStringSet(PREF_PENDING_QR_DELETES, updated).apply();
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("车辆二维码链接", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    /**
     * Matches the two WeChat scan entry points used by JUWP-Schedule's
     * "快趣出行" screen, then falls back to opening WeChat's home screen.
     */
    private void openWeChatScanner() {
        Intent dispatchScan = new Intent("com.tencent.mm.ui.ShortCutDispatchAction")
                .setPackage("com.tencent.mm")
                .putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_scan_qrcode");
        if (tryLaunchWeChat(dispatchScan)) {
            return;
        }

        Intent bizShortcut = new Intent("com.tencent.mm.action.BIZSHORTCUT")
                .setPackage("com.tencent.mm")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("LauncherUI.From.Scaner.Shortcut", true);
        if (tryLaunchWeChat(bizShortcut)) {
            return;
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
        } else {
            Toast.makeText(this, R.string.wechat_not_installed, Toast.LENGTH_LONG).show();
        }
    }

    private boolean tryLaunchWeChat(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String formatDistance(float distanceMeters) {
        if (distanceMeters < 1000f) {
            return Math.round(distanceMeters) + " 米";
        }
        return String.format(Locale.CHINA, "%.1f 公里", distanceMeters / 1000f);
    }

    private String formatBattery(double batteryPercent) {
        if (batteryPercent < 0) {
            return "--";
        }
        if (Math.abs(batteryPercent - Math.rint(batteryPercent)) < 0.05) {
            return String.valueOf(Math.round(batteryPercent));
        }
        return String.format(Locale.CHINA, "%.1f", batteryPercent);
    }

    private String formatBatteryLabel(Bike bike) {
        String value = formatBattery(bike.batteryPercent);
        return "--".equals(value) ? value : value + "%";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationUpdates() {
        // Re-registering is idempotent: drop any previous registration first so
        // repeated calls cannot stack duplicate listeners.
        stopLocationUpdates();

        Location bestLastKnown = null;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
        }) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null
                        && (bestLastKnown == null
                        || candidate.getAccuracy() < bestLastKnown.getAccuracy())) {
                    bestLastKnown = candidate;
                }
            } catch (SecurityException ignored) {
                // Permission is checked before this method is called.
            }
        }

        // Falling back to the fix already on screen keeps the marker attached
        // even when the providers have no cached location to hand back yet.
        if (bestLastKnown == null) {
            bestLastKnown = currentLocation;
        }
        if (bestLastKnown != null) {
            onLocationChanged(bestLastKnown);
        }

        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    4000L,
                    8f,
                    this,
                    Looper.getMainLooper()
            );
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    8000L,
                    20f,
                    this,
                    Looper.getMainLooper()
            );
        } catch (SecurityException exception) {
            tvStatus.setText(R.string.permission_location);
        }
    }

    /**
     * Location updates are only held while the app is in the foreground. The
     * system can drop a foreground registration when the app moves to the
     * background, so it is registered again on every resume instead of once at
     * startup, which used to leave the map with a frozen last fix.
     */
    private void stopLocationUpdates() {
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
            // The permission may already be gone; the registration is dropped
            // either way.
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        currentLocation = location;
        GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
        GeoPoint displayPoint = CoordinateUtils.wgs84ToGcj02(
                point.getLatitude(),
                point.getLongitude()
        );

        if (userMarker == null) {
            userMarker = new Marker(mapView);
            userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            userMarker.setIcon(getDrawable(R.drawable.ic_user_location));
            userMarker.setTitle("我的位置");
        }
        // Never leave the position marker detached from the overlay list: a
        // rebuild without a known location used to drop the dot for good.
        if (!mapView.getOverlays().contains(userMarker)) {
            mapView.getOverlays().add(userMarker);
        }
        userMarker.setPosition(displayPoint);
        mapView.invalidate();

        if (!initialLocationCentered) {
            initialLocationCentered = true;
            mapView.getController().animateTo(displayPoint);
            loadBikes(displayPoint, true);
        }
    }

    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Location state changes do not alter the displayed vehicles.
    }

    public void onProviderEnabled(String provider) {
        // Location updates are already registered.
    }

    public void onProviderDisabled(String provider) {
        // Keep the last known map state.
    }

    private double distanceBetween(GeoPoint first, GeoPoint second) {
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

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQUEST_LOCATION) {
                tvStatus.setText(R.string.location_denied);
            } else if (requestCode == REQUEST_STORAGE) {
                pendingQrBitmap = null;
                pendingQrNumber = -1;
                pendingOpenWeChatAfterQrSave = false;
                Toast.makeText(this, R.string.permission_storage_message, Toast.LENGTH_LONG)
                        .show();
            }
            return;
        }

        if (requestCode == REQUEST_LOCATION) {
            startLocationUpdates();
        } else if (requestCode == REQUEST_STORAGE
                && pendingQrBitmap != null
                && pendingQrNumber > 0) {
            Bitmap bitmap = pendingQrBitmap;
            long number = pendingQrNumber;
            pendingQrBitmap = null;
            pendingQrNumber = -1;
            openWeChatAfterQrSaveIfNeeded(performQrSave(bitmap, number));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        // Foreground-only location: re-register on every resume, otherwise the
        // position stays frozen after the app returns from the background.
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        stopLocationUpdates();
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(refreshFromMapCenterRunnable);
        stopLocationUpdates();
        repository.shutdown();
        super.onDestroy();
    }

}
