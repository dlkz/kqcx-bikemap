package com.kqcx.bikemap;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.CountDownTimer;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.core.app.NotificationManagerCompat;
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
import android.widget.RadioGroup;
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
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class MainActivity extends BaseActivity implements LocationListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_LOCATION = 1001;
    private static final int REQUEST_STORAGE = 1003;
    private static final int REQUEST_NOTIFICATIONS = 1004;
    private static final int REQUEST_VEHICLE_ALERT_LOCATION = 1005;
    private static final int REMINDER_FLOW_NONE = 0;
    private static final int REMINDER_FLOW_FIRST_USE = 1;
    private static final long CHANNEL_HINT_DELAY_MS = 1300L;
    private static final long REMINDER_ALERT_SLIDE_MS = 220L;
    /** 清理后残留不超过该值即视为清理成功（VACUUM 后 sqlite 仍占少量页）。 */
    private static final long MAP_CACHE_SMALL_REMAINDER_BYTES = 2L * 1024L * 1024L;
    private static final int REMINDER_FLOW_SCHEDULE = 2;
    private static final int QR_HISTORY_LIMIT = 5;
    private static final long MARKER_TAP_GUARD_MS = 700L;
    private static final long MAP_CENTER_REFRESH_DELAY_MS = 700L;
    private static final long RECENTER_ANIMATION_MS = 700L;
    /**
     * 动画本身只跑 RECENTER_ANIMATION_MS，再多等一小段用于校验落点：
     * 动画被打断或最后一帧没落到目标点时，地图会停在半路并错误地弹出
     * 只有在“拉远”后才会出现的定位指示针。
     */
    private static final long RECENTER_SETTLE_DELAY_MS = RECENTER_ANIMATION_MS + 250L;
    private static final double CENTER_INDICATOR_DISTANCE_METERS = 25.0;
    /** 落点校验允许的误差，超过该值说明地图没有真正回到定位点。 */
    private static final double RECENTER_SETTLE_TOLERANCE_METERS = 5.0;
    /**
     * 居中后的一小段跟随窗口。刚启动或刚回前台时，系统可能先给出缓存的
     * 旧定位，真正的新定位稍后才到；窗口内到达的新定位会把地图再拉回一次，
     * 否则地图会停在旧点并错误地显示定位指示针。
     */
    private static final long RECENTER_FOLLOW_WINDOW_MS = 6000L;
    /**
     * 后台挂起较久后回到前台时，系统常常只给到很久以前的缓存定位，真正的新定位
     * 要等 GPS 重新捕获，往往超过固定的跟随窗口。用户主动点“重新定位”后，跟随
     * 逻辑要一直等到收到比这次点击更新的定位为止，最长等待该时长。
     */
    private static final long RECENTER_FRESH_FIX_TIMEOUT_MS = 60_000L;
    /**
     * The 512-pixel tile source doubles the pixel scale of a classic 256-pixel
     * source, so 16.0 keeps roughly the same view span as the old 17.0 setting.
     */
    private static final double DEFAULT_ZOOM = 16.0;
    private static final double RECENTER_ZOOM = 17.0;
    /** 按编号点名查到车后把镜头拉过去的缩放级别，比默认视野更近一些。 */
    private static final double BIKE_FOCUS_ZOOM = 18.0;
    private static final float MAP_TILE_SATURATION = 0.58f;

    private static final String PREF_DISCLAIMER_ACCEPTED = "disclaimer_accepted";
    private static final String PREF_USAGE_NOTICE_SHOWN = "usage_notice_shown";
    private static final String PREF_QR_SHOW_URL = "qr_show_url";
    private static final String PREF_QR_COPY_ENABLED = "qr_copy_enabled";
    private static final String PREF_ADVANCED_ENABLED = "advanced_enabled";
    /**
     * 设置里的「自动过滤不可用车辆」，默认开启；键值与
     * 地图列表过滤共享。车辆预警不再复用此开关。
     */
    private static final String PREF_FILTER_UNAVAILABLE = "filter_unavailable";
    private static final String PREF_VEHICLE_ALERT_ENABLED = "vehicle_alert_enabled";
    private static final String PREF_VEHICLE_ALERT_INTERVAL = "vehicle_alert_interval_seconds";
    private static final String PREF_VEHICLE_ALERT_AUTOSTART_PROMPTED = "vehicle_alert_autostart_prompted";
    private static final String PREF_VEHICLE_ALERT_WAITING_NOTIFICATION =
            "vehicle_alert_waiting_notification";
    private static final String PREF_QR_HISTORY = "qr_history";
    private static final String PREF_KEEP_QR_HISTORY = "keep_qr_history";
    private static final String PREF_NUMBER_SITE_ID = "number_site_id";
    private static final String PREF_NUMBER_SERIES = "number_series";
    private static final String PREF_REMINDER_LEAD_MINUTES = "reminder_lead_minutes";
    private static final String PREF_REMINDER_ALERT_MODE = "reminder_alert_mode";
    private static final String PREF_REMINDER_SETUP_PROMPTED = "reminder_setup_prompted";
    private static final String PREF_REMINDER_RISK_MUTED = "reminder_risk_muted";
    private static final String PREF_REMINDER_CHANNEL_GUIDE_SHOWN =
            "reminder_channel_guide_shown_v1";
    private static final String PREF_REMINDER_PENDING_FLOW = "reminder_pending_flow";
    private static final String PREF_REMINDER_PENDING_STEP = "reminder_pending_step";
    private static final String PREF_REMINDER_PENDING_START_AT = "reminder_pending_start_at";
    private static final String PREF_REMINDER_PENDING_END_AT = "reminder_pending_end_at";
    private static final String PREF_REMINDER_PENDING_LEAD_MINUTES =
            "reminder_pending_lead_minutes";
    private static final String PREF_REMINDER_PENDING_ALERT_MODE =
            "reminder_pending_alert_mode";
    private static final String PREF_REMINDER_OEM_GUIDE_SHOWN = "reminder_oem_guide_shown_v3";

    /**
     * Neutral public-city center used only before a location fix is available.
     * It does not describe the author's or user's actual position.
     */
    private static final GeoPoint DEFAULT_CENTER = new GeoPoint(
            39.9042,
            116.4074
    );
    /** 说明类文本里用于标记分组标题的前缀（渲染时去掉）。 */
    private static final String GROUP_HEADING_MARKER = "## ";
    private static final long MIN_REFRESH_INTERVAL_MS = 10_000L;
    private static final double MIN_REFRESH_DISTANCE_METERS = 80.0;

    /**
     * Mainland China tile service using GCJ-02 coordinates and 512-pixel
     * tiles. The tiles are drawn one-to-one with screen pixels so the high
     * resolution source is not upscaled and blurred on high-DPI phones.
     */
    private OnlineTileSourceBase createMapTileSource() {
        return new OnlineTileSourceBase(
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
                getString(R.string.map_tile_copyright)
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
    }

    private BikeRepository repository;
    private QrGalleryStore qrGalleryStore;
    private VehicleNumberCatalog vehicleNumberCatalog;
    private boolean vehicleNumberCatalogLoadFinished;
    private Runnable numberCatalogRefresh;
    private SharedPreferences appPreferences;
    private final List<Bike> bikes = new ArrayList<>();
    private final Map<String, Marker> bikeMarkers = new HashMap<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshFromMapCenterRunnable = this::refreshFromMapCenter;

    private AppMapView mapView;
    private TextView tvStatus;
    private TextView tvDataLabel;
    private TextView tvBikeNumber;
    private TextView tvBikeMeta;
    private TextView tvBikeFreshness;
    private TextView tvManualNotice;
    private TextView tvSettingsVersion;
    private TextView tvVehicleAlertStatus;
    private LinearLayout bikePanel;
    private View settingsPanel;
    private Button btnShowQr;
    private ImageButton btnRefresh;
    private ImageButton btnReminder;
    private LinearLayout rowAdvanced;
    private CheckBox checkAdvanced;
    private CheckBox checkThemeSystem;
    private CheckBox checkThemeLight;
    private CheckBox checkThemeDark;
    private View advancedSection;
    private View advancedPanel;
    private Switch switchQrUrl;
    private Switch switchQrCopy;
    private Switch switchVehicleAlert;
    private Switch switchKeepHistory;
    private Switch switchFilterUnavailable;
    private TextView tvMapCacheSize;
    private ImageView mapCenterIndicator;
    private final SimpleDateFormat refreshTimeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private final SimpleDateFormat uploadTimeFormat =
            new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    private LocationManager locationManager;
    private Location currentLocation;
    private BikeCallout bikeCallout;
    private UserLocationCallout userLocationCallout;
    private MapLabelOverlay mapLabelOverlay;
    private Marker userMarker;
    private GeoPoint lastRequestedCenter;
    private GeoPoint pendingRequestCenter;
    private Bike selectedBike;
    private long lastFetchAtMs;
    /** 最近一次附近查询返回的车辆总数，用于区分“没查到车”和“全是不可用车”。 */
    private int lastNearbyRawCount;
    private long lastMarkerTapAt;
    private int systemBarsBottom;
    private String pendingQrNumber = "";
    private Bitmap pendingQrBitmap;
    private boolean pendingOpenWeChatAfterQrSave;
    private boolean requestInFlight;
    private boolean pendingRequestForce;
    private boolean permissionDialogShowing;
    private boolean initialLocationCentered;
    private boolean recenterInProgress;
    /** 还没有定位结果时按下“重新定位”，等首个定位回调到达后再居中。 */
    private boolean pendingRecenterOnFix;
    /** 正在等待本次居中动画结束后的落点校验。 */
    private boolean recenterSettlePending;
    /** 本次居中期间用户自己操作过地图，校验时不再抢回镜头。 */
    private boolean recenterUserInterrupted;
    /** 居中后跟随窗口的截止时间（elapsedRealtime，含深度休眠时间）。 */
    private long recenterFollowUntilMs;
    /** 本次居中要落到的缩放级别，跟随窗口复用该值。 */
    private double recenterZoom = RECENTER_ZOOM;
    /**
     * 用户按下“重新定位”的时刻（elapsedRealtime）。非 0 表示还没收到比这次点击
     * 更新的定位；此时即使固定跟随窗口到期，也要继续跟随，直到新定位到达或超时。
     */
    private long recenterAwaitFixSinceElapsedMs;
    /**
     * 点名查车（按编号定位）优先于自身定位跟随。置位后，居中落点校验和
     * 6 秒跟随窗口都不会把镜头从目标车拉回自身定位点，直到用户按“重新定位”
     * 回到自身视角才解除。
     */
    private boolean vehicleFocusActive;
    /** 按编号点名查到的车。它不在附近列表里，重查后要并回地图，避免标记消失。 */
    private Bike queriedBike;
    /** 正在点名查询的车号，用于合并重复请求。 */
    private String queryingBikeNumber;
    /**
     * 通过编号点名定位车辆时，镜头会离开自身定位点；这种“主动查看某辆车”
     * 的场景不应弹出“偏离自身定位”的中心指示针。置位后直到用户自己操作
     * 地图或按“重新定位”才恢复原有判定。
     */
    private boolean suppressCenterIndicator;
    private CountDownTimer reminderDialogTimer;
    private int pendingReminderLeadMinutes = ReminderScheduler.DEFAULT_LEAD_MINUTES;
    private String pendingReminderAlertMode = ReminderScheduler.MODE_SOUND_VIBRATE;
    private long pendingReminderStartAt;
    private long pendingReminderEndAt;
    private boolean awaitingExactAlarmPermission;
    private boolean awaitingBatteryOptimizationPermission;
    private boolean awaitingReminderChannelPermission;
    private final java.util.Map<Integer, Integer> chipLastChecked = new java.util.HashMap<>();
    private boolean backgroundSettingsDialogShowing;
    private boolean vehicleAlertNotificationDialogShowing;
    private boolean awaitingVehicleAlertNotificationPermission;
    private int reminderFlow = REMINDER_FLOW_NONE;
    private View reminderAlertOverlay;
    private int mapCacheSizeGeneration;
    private int mapCacheDialogGeneration;

    /**
     * 当前处于前台的 MainActivity。提醒小方块只在应用前台时显示，
     * 后台只保留系统通知，不再从后台拉起任何界面。
     */
    private static MainActivity foregroundInstance;

    static MainActivity foregroundInstance() {
        return foregroundInstance;
    }

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
        repository = new BikeRepository(this);
        qrGalleryStore = new QrGalleryStore(getApplicationContext(), PREFS_APP);
        appPreferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        awaitingVehicleAlertNotificationPermission = appPreferences.getBoolean(
                PREF_VEHICLE_ALERT_WAITING_NOTIFICATION,
                false
        );
        // 目录解析放到后台线程，避免将来目录变大时在 onCreate 主线程同步解析卡顿
        // （接近 ANR）。先用空目录占位，所有代码路径都能拿到非 null 实例，解析完成后
        // 再替换并刷新“数据最后更新于”文案。
        vehicleNumberCatalog = VehicleNumberCatalog.empty();
        loadVehicleNumberCatalogAsync();

        // 上次在系统设置页里没走完的提醒权限流程，重建后继续。
        restoreReminderFlowStep();
        bindViews();
        applySystemBarInsets();
        configureMap();
        setupClickListeners();
        setupSettings();
        updateReminderButtonState();
        applyVehicleAlertState();
        cleanupExpiredQrSaves();
        showStartupFlow();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (advancedPanel != null && advancedPanel.getVisibility() == View.VISIBLE) {
                    hideAdvancedPage();
                    return;
                }
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

    /**
     * Reads and parses the bundled vehicle-number catalog off the main thread.
     *
     * <p>Failure keeps the empty placeholder from {@link #onCreate}: the map and
     * the nearby-vehicle queries keep working, only the number lookup stays
     * unavailable.  This mirrors the previous synchronous fallback but no longer
     * blocks startup, so a future, larger catalog cannot stall {@code onCreate}.
     */
    private void loadVehicleNumberCatalogAsync() {
        Context appContext = getApplicationContext();
        new Thread(() -> {
            VehicleNumberCatalog loaded = null;
            try {
                loaded = VehicleNumberCatalog.load(appContext);
            } catch (Exception exception) {
                // 内置目录损坏，或将来被升级成当前版本无法解析的格式时，降级为“无内置编号”
                // 继续启动：地图、附近车辆查询等主功能可用，只有编号补全不可用，而不是闪退。
                DiagnosticLog.warn(TAG, "vehicle number catalog unavailable; continuing without it", exception);
            }
            VehicleNumberCatalog resolved = loaded;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (resolved != null) {
                    vehicleNumberCatalog = resolved;
                }
                vehicleNumberCatalogLoadFinished = true;
                applyCatalogUpdatedAt(findViewById(R.id.tvNumberDataUpdated));
                if (numberCatalogRefresh != null) {
                    numberCatalogRefresh.run();
                }
            });
        }, "vehicle-number-catalog").start();
    }

    private void bindViews() {
        mapView = findViewById(R.id.map);
        tvStatus = findViewById(R.id.tvStatus);
        tvDataLabel = findViewById(R.id.tvDataLabel);
        tvBikeNumber = findViewById(R.id.tvBikeNumber);
        tvBikeMeta = findViewById(R.id.tvBikeMeta);
        tvBikeFreshness = findViewById(R.id.tvBikeFreshness);
        tvManualNotice = findViewById(R.id.tvManualNotice);
        bikePanel = findViewById(R.id.bikePanel);
        settingsPanel = findViewById(R.id.settingsPanel);
        tvSettingsVersion = findViewById(R.id.tvSettingsVersion);
        tvVehicleAlertStatus = findViewById(R.id.tvVehicleAlertStatus);
        checkAdvanced = findViewById(R.id.checkAdvanced);
        checkThemeSystem = findViewById(R.id.checkThemeSystem);
        checkThemeLight = findViewById(R.id.checkThemeLight);
        checkThemeDark = findViewById(R.id.checkThemeDark);
        rowAdvanced = findViewById(R.id.rowAdvanced);
        advancedSection = findViewById(R.id.advancedSection);
        advancedPanel = findViewById(R.id.advancedPanel);
        switchQrUrl = findViewById(R.id.switchQrUrl);
        switchQrCopy = findViewById(R.id.switchQrCopy);
        switchVehicleAlert = findViewById(R.id.switchVehicleAlert);
        switchKeepHistory = findViewById(R.id.switchKeepHistory);
        switchFilterUnavailable = findViewById(R.id.switchFilterUnavailable);
        applyCatalogUpdatedAt(findViewById(R.id.tvNumberDataUpdated));
        mapCenterIndicator = findViewById(R.id.mapCenterIndicator);
        btnShowQr = findViewById(R.id.btnShowQr);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnReminder = findViewById(R.id.btnReminder);
    }

    /**
     * “车辆编号数据最后更新于 …” 直接读内置目录的 {@code updatedAt}，与随版本
     * 内置的目录保持一致；目录缺失日期时隐藏该行，而不是显示错误的旧日期。
     */
    private void applyCatalogUpdatedAt(TextView view) {
        if (view == null) {
            return;
        }
        String isoDate = vehicleNumberCatalog == null ? "" : vehicleNumberCatalog.updatedAt();
        String[] parts = isoDate == null ? new String[0] : isoDate.split("-");
        if (parts.length != 3) {
            view.setVisibility(View.GONE);
            return;
        }
        try {
            view.setText(getString(
                    R.string.vehicle_number_data_updated,
                    parts[0],
                    String.valueOf(Integer.parseInt(parts[1])),
                    String.valueOf(Integer.parseInt(parts[2]))
            ));
            // 目录是后台加载的，首次（空占位目录）调用会先隐藏这一行，
            // 解析成功后必须显式恢复可见，否则文案永远不会出现。
            view.setVisibility(View.VISIBLE);
        } catch (NumberFormatException exception) {
            view.setVisibility(View.GONE);
        }
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.root);
        View topBanner = findViewById(R.id.topBanner);
        View settingsHeader = findViewById(R.id.settingsHeader);
        View advancedHeader = findViewById(R.id.advancedHeader);
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

            applyHeaderInsets(settingsHeader, settingsHeaderBaseHeight, systemBars.top);
            applyHeaderInsets(advancedHeader, settingsHeaderBaseHeight, systemBars.top);

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

    /** 全屏页面标题栏统一避让状态栏。 */
    private void applyHeaderInsets(View header, int baseHeight, int topInset) {
        ViewGroup.LayoutParams params = header.getLayoutParams();
        params.height = baseHeight + topInset;
        header.setLayoutParams(params);
        header.setPadding(
                header.getPaddingLeft(),
                topInset,
                header.getPaddingRight(),
                header.getPaddingBottom()
        );
    }

    private void configureMap() {
        mapView.setTileSource(createMapTileSource());
        mapView.getMapOverlay().setColorFilter(createMapColorFilter());
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
            public void onUserGestureStart() {
                // 用户自己动了地图，恢复“偏离自身定位才显示指示针”的默认判定。
                suppressCenterIndicator = false;
                // 用户自己动过地图就不再自动居中，避免把镜头抢回去。
                if (recenterSettlePending
                        || recenterAwaitFixSinceElapsedMs != 0L
                        || SystemClock.elapsedRealtime() < recenterFollowUntilMs) {
                    recenterUserInterrupted = true;
                }
                // 用户已经接管地图，停止等待后台恢复后的新定位。
                recenterAwaitFixSinceElapsedMs = 0L;
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
                updateMapCenterIndicator();
                scheduleMapCenterRefresh();
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                updateViewportStatus();
                updateMapLabels();
                updateMapCenterIndicator();
                scheduleMapCenterRefresh();
                return false;
            }
        });
    }

    private ColorMatrixColorFilter createMapColorFilter() {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(MAP_TILE_SATURATION);
        return new ColorMatrixColorFilter(matrix);
    }

    private void setupClickListeners() {
        findViewById(R.id.btnLocate).setOnClickListener(view -> {
            if (!hasLocationPermission()) {
                requestLocationPermission();
                return;
            }

            // Tapping locate also re-registers, so the button recovers the fix
            // even if the system dropped updates while the app was in the
            // background.
            startLocationUpdates();
            if (currentLocation == null) {
                // 定位还没出来时不能直接返回，否则按钮在用户看来就是“没反应”；
                // 记下意图，等首个定位回调到达后再居中。
                pendingRecenterOnFix = true;
                return;
            }
            recenterToCurrentLocation();
        });

        findViewById(R.id.btnList).setOnClickListener(view -> showBikeList());
        btnReminder.setOnClickListener(view -> showReminderEntry());
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
        findViewById(R.id.btnAdvancedBack).setOnClickListener(view -> hideAdvancedPage());
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
        tvMapCacheSize = findViewById(R.id.tvMapCacheSize);
        findViewById(R.id.rowClearMapCache).setOnClickListener(
                view -> showClearMapCacheDialog()
        );
        updateMapCacheSizeLabel();

        tvSettingsVersion.setText(versionNumberLabel());
        String theme = appPreferences
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

        SharedPreferences preferences = appPreferences;
        switchKeepHistory.setChecked(
                preferences.getBoolean(PREF_KEEP_QR_HISTORY, true)
        );
        switchKeepHistory.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(PREF_KEEP_QR_HISTORY, checked).apply();
            if (!checked) {
                clearQrHistory();
            }
        });
        switchFilterUnavailable.setChecked(
                preferences.getBoolean(PREF_FILTER_UNAVAILABLE, true)
        );
        switchFilterUnavailable.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(PREF_FILTER_UNAVAILABLE, checked).apply();
            // 立即按当前地图中心重查一次，让地图、列表和顶栏同步反映新设置。
            loadBikes(mapCenterGcj02(), true);
        });
        switchQrUrl.setChecked(preferences.getBoolean(PREF_QR_SHOW_URL, false));
        switchQrCopy.setChecked(preferences.getBoolean(PREF_QR_COPY_ENABLED, false));
        switchQrUrl.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(PREF_QR_SHOW_URL, checked).apply()
        );
        switchQrCopy.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(PREF_QR_COPY_ENABLED, checked).apply()
        );

        boolean vehicleAlertEnabled =
                preferences.getBoolean(PREF_VEHICLE_ALERT_ENABLED, false);
        switchVehicleAlert.setChecked(vehicleAlertEnabled);
        switchVehicleAlert.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit()
                    .putBoolean(PREF_VEHICLE_ALERT_ENABLED, checked)
                    .apply();
            if (!checked) {
                awaitingVehicleAlertNotificationPermission = false;
                preferences.edit()
                        .remove(PREF_VEHICLE_ALERT_WAITING_NOTIFICATION)
                        .apply();
                updateVehicleAlertStatus(0);
            }
            if (checked) {
                VehicleAlertService.ensureChannels(this);
            }
            applyVehicleAlertState(true);
            if (checked && !preferences.getBoolean(PREF_VEHICLE_ALERT_AUTOSTART_PROMPTED, false)) {
                preferences.edit()
                        .putBoolean(PREF_VEHICLE_ALERT_AUTOSTART_PROMPTED, true)
                        .apply();
                showVehicleAlertAutostartDialog();
            }
        });

        boolean advancedEnabled = preferences.getBoolean(PREF_ADVANCED_ENABLED, false);
        checkAdvanced.setChecked(advancedEnabled);
        advancedSection.setVisibility(advancedEnabled ? View.VISIBLE : View.GONE);
        // 高级选项是独立页面，进入设置时应保持关闭状态。
        hideAdvancedPage();
        if (!advancedEnabled) {
            switchQrUrl.setChecked(false);
            switchQrCopy.setChecked(false);
            // 车辆预警属于高级选项：高级设置已关闭时不允许它在后台继续运行。
            switchVehicleAlert.setChecked(false);
        }
        checkAdvanced.setOnCheckedChangeListener((button, checked) -> {
            advancedSection.setVisibility(checked ? View.VISIBLE : View.GONE);
            // 关闭高级设置时同时退出高级选项页面。
            hideAdvancedPage();
            if (!checked) {
                switchQrUrl.setChecked(false);
                switchQrCopy.setChecked(false);
                // 关闭高级设置时一并关闭车辆预警，避免开关被隐藏后功能仍在后台运行。
                switchVehicleAlert.setChecked(false);
            }
            preferences.edit()
                    .putBoolean(PREF_ADVANCED_ENABLED, checked)
                    .putBoolean(PREF_QR_SHOW_URL, checkAdvanced.isChecked()
                            && switchQrUrl.isChecked())
                    .putBoolean(PREF_QR_COPY_ENABLED, checkAdvanced.isChecked()
                            && switchQrCopy.isChecked())
                    .apply();
        });
        rowAdvanced.setOnClickListener(view -> checkAdvanced.toggle());
        // 「高级选项」打开独立页面，不再在设置页内联展开。
        findViewById(R.id.rowAdvancedOptions).setOnClickListener(view -> showAdvancedPage());
    }

    /** 高级选项独立页面，与设置页同级；返回键先退出该页面。 */
    private void showAdvancedPage() {
        if (advancedPanel != null) {
            advancedPanel.setVisibility(View.VISIBLE);
        }
    }

    private void hideAdvancedPage() {
        if (advancedPanel != null) {
            advancedPanel.setVisibility(View.GONE);
        }
    }

    /**
     * 车辆预警要跑起来需要两件事同时成立：高级设置里的功能开关打开，
     * 且提醒页里的计时开着。两者互不影响各自的开关状态。
     */
    private void applyVehicleAlertState() {
        applyVehicleAlertState(false);
    }

    /**
     * @param fromUserAction 只有用户在当前界面主动开关功能或改间隔时才允许弹窗提示；
     *                       回前台等被动路径静默处理，避免无关恢复时反复弹提示框。
     */
    private void applyVehicleAlertState(boolean fromUserAction) {
        boolean enabled = appPreferences.getBoolean(PREF_VEHICLE_ALERT_ENABLED, false);
        if (!enabled || !VehicleAlertService.isTimerRunning(this)) {
            VehicleAlertService.stop(this);
            updateVehicleAlertStatus(0);
            return;
        }
        if (!appPreferences.getBoolean(PREF_DISCLAIMER_ACCEPTED, false)
                || !appPreferences.getBoolean(PREF_USAGE_NOTICE_SHOWN, false)) {
            VehicleAlertService.stop(this);
            updateVehicleAlertStatus(0);
            return;
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            updateVehicleAlertStatus(R.string.vehicle_alert_paused_location);
            if (fromUserAction) {
                showPermissionExplanation(
                        R.string.permission_location_title,
                        R.string.vehicle_alert_precise_location_message,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_VEHICLE_ALERT_LOCATION
                );
            }
            return;
        }
        if (!VehicleAlertService.hasNotificationAccess(this)) {
            VehicleAlertService.stop(this);
            updateVehicleAlertStatus(R.string.vehicle_alert_paused_notification);
            if (fromUserAction) {
                showVehicleAlertNotificationDialog();
            }
            return;
        }
        try {
            VehicleAlertService.start(this);
        } catch (RuntimeException exception) {
            if (fromUserAction) {
                Toast.makeText(this, R.string.vehicle_alert_start_failed, Toast.LENGTH_LONG).show();
            }
            updateVehicleAlertStatus(0);
            return;
        }
        updateVehicleAlertStatus(0);
    }

    private void updateVehicleAlertStatus(int textRes) {
        if (tvVehicleAlertStatus == null) {
            return;
        }
        if (textRes == 0) {
            tvVehicleAlertStatus.setVisibility(View.GONE);
            return;
        }
        tvVehicleAlertStatus.setText(textRes);
        tvVehicleAlertStatus.setVisibility(View.VISIBLE);
    }

    private void showVehicleAlertAutostartDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.vehicle_alert_autostart_title)
                .setMessage(R.string.vehicle_alert_autostart_message)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.vehicle_alert_autostart_open,
                        (ignored, which) -> openVehicleAlertAutostartSettings()
                )
                .setNegativeButton(R.string.vehicle_alert_autostart_later, null)
                .create();
        dialog.show();
    }

    private void showVehicleAlertNotificationDialog() {
        if (vehicleAlertNotificationDialogShowing) {
            return;
        }
        vehicleAlertNotificationDialogShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.vehicle_alert_notification_title)
                .setMessage(R.string.vehicle_alert_notification_message)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.vehicle_alert_notification_open,
                        (ignored, which) -> {
                            awaitingVehicleAlertNotificationPermission = true;
                            appPreferences.edit()
                                    .putBoolean(
                                            PREF_VEHICLE_ALERT_WAITING_NOTIFICATION,
                                            true
                                    )
                                    .commit();
                            if (!openAppNotificationSettings()) {
                                awaitingVehicleAlertNotificationPermission = false;
                                appPreferences.edit()
                                        .remove(PREF_VEHICLE_ALERT_WAITING_NOTIFICATION)
                                        .apply();
                                updateVehicleAlertStatus(
                                        R.string.vehicle_alert_paused_notification
                                );
                            }
                        }
                )
                .setNegativeButton(
                        R.string.permission_not_now,
                        (ignored, which) -> {
                            awaitingVehicleAlertNotificationPermission = false;
                            appPreferences.edit()
                                    .remove(PREF_VEHICLE_ALERT_WAITING_NOTIFICATION)
                                    .apply();
                            updateVehicleAlertStatus(
                                    R.string.vehicle_alert_paused_notification
                            );
                        }
                )
                .create();
        dialog.setOnDismissListener(ignored -> vehicleAlertNotificationDialogShowing = false);
        dialog.show();
    }

    private void openVehicleAlertAutostartSettings() {
        // 受系统限制，第三方应用无法直接打开厂商的自启动页面；
        // 这里打开系统设置首页，由用户按提示手动进入「应用 → 自启动」。
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (RuntimeException ignored) {
            // Fall through to the app details page.
        }
        try {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            ));
        } catch (Exception ignored) {
            // Nothing else we can open.
        }
    }
    private void selectAlertInterval(RadioGroup group, int seconds) {
        int id = R.id.rbAlertInterval60;
        if (seconds == 10) {
            id = R.id.rbAlertInterval10;
        } else if (seconds == 30) {
            id = R.id.rbAlertInterval30;
        } else if (seconds == 300) {
            id = R.id.rbAlertInterval300;
        } else if (seconds == 180) {
            id = R.id.rbAlertInterval180;
        }
        group.check(id);
    }

    private int alertIntervalFromId(int checkedId) {
        if (checkedId == -1) {
            return 0;
        }
        if (checkedId == R.id.rbAlertInterval10) {
            return 10;
        }
        if (checkedId == R.id.rbAlertInterval30) {
            return 30;
        }
        if (checkedId == R.id.rbAlertInterval300) {
            return 300;
        }
        if (checkedId == R.id.rbAlertInterval180) {
            return 180;
        }
        return 60;
    }

    private void bindVehicleAlertInterval(View content) {
        TextView title = content.findViewById(R.id.tvVehicleAlertIntervalTitle);
        RadioGroup group = content.findViewById(R.id.rgVehicleAlertInterval);
        boolean enabled = appPreferences.getBoolean(PREF_VEHICLE_ALERT_ENABLED, false);
        title.setVisibility(enabled ? View.VISIBLE : View.GONE);
        group.setVisibility(enabled ? View.VISIBLE : View.GONE);
        // 这一行只是计时开关：有选中 = 计时开着，没选中 = 计时关着。
        // 功能开关始终只由高级设置决定，这里不会去改它。
        int saved = appPreferences.getInt(PREF_VEHICLE_ALERT_INTERVAL, 0);
        if (saved <= 0) {
            group.clearCheck();
            return;
        }
        selectAlertInterval(group, saved);
    }

    private void showSelectedTheme(String theme) {
        checkThemeSystem.setChecked(THEME_SYSTEM.equals(theme));
        checkThemeLight.setChecked(THEME_LIGHT.equals(theme));
        checkThemeDark.setChecked(THEME_DARK.equals(theme));
    }

    private void selectTheme(String newTheme) {
        SharedPreferences preferences = appPreferences;
        showSelectedTheme(newTheme);
        if (!newTheme.equals(preferences.getString(PREF_THEME, THEME_SYSTEM))) {
            preferences.edit().putString(PREF_THEME, newTheme).apply();
            recreate();
        }
    }

    private void showStartupFlow() {
        SharedPreferences preferences = appPreferences;
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
                    appPreferences
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
        SharedPreferences preferences = appPreferences;
        if (preferences.getBoolean(PREF_USAGE_NOTICE_SHOWN, false)) {
            startInitialPermissionFlow();
            return;
        }

        View content = createInfoContent(getString(R.string.usage_notice_message));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.usage_notice_title)
                .setView(content)
                .setCancelable(false)
                .setPositiveButton(R.string.usage_notice_confirm, (ignored, which) -> {
                    preferences.edit()
                            .putBoolean(PREF_USAGE_NOTICE_SHOWN, true)
                            .apply();
                    startInitialPermissionFlow();
                })
                .create();
        dialog.show();
        applyInfoDialogSize(dialog, content);
    }

    private void startInitialPermissionFlow() {
        if (hasLocationPermission()) {
            startLocationUpdates();
        } else {
            requestLocationPermission();
        }
    }

    private void showSettings() {
        updateMapCacheSizeLabel();
        settingsPanel.setVisibility(View.VISIBLE);
    }

    private void hideSettings() {
        mapCacheDialogGeneration++;
        settingsPanel.setVisibility(View.GONE);
    }

    /** 地图瓦片缓存（osmdroid）当前占用，字节。 */
    private long mapCacheSizeBytes() {
        return directorySizeBytes(osmdroidTileCacheDir());
    }

    private java.io.File osmdroidTileCacheDir() {
        try {
            return org.osmdroid.config.Configuration.getInstance()
                    .getOsmdroidTileCache(getApplicationContext());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static long directorySizeBytes(java.io.File dir) {
        if (dir == null || !dir.exists()) {
            return 0L;
        }
        java.io.File[] children = dir.listFiles();
        if (children == null) {
            return 0L;
        }
        long total = 0L;
        for (java.io.File child : children) {
            total += child.isDirectory()
                    ? directorySizeBytes(child)
                    : Math.max(0L, child.length());
        }
        return total;
    }

    private void updateMapCacheSizeLabel() {
        if (tvMapCacheSize == null) {
            return;
        }
        int generation = ++mapCacheSizeGeneration;
        tvMapCacheSize.setText(R.string.map_cache_calculating);
        new Thread(() -> {
            long bytes = mapCacheSizeBytes();
            runOnUiThread(() -> {
                if (isFinishing()
                        || isDestroyed()
                        || generation != mapCacheSizeGeneration) {
                    return;
                }
                tvMapCacheSize.setText(bytes <= 0L
                        ? getString(R.string.map_cache_empty)
                        : formatByteSize(bytes));
            });
        }, "map-cache-size").start();
    }

    private static String formatByteSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / 1024f / 1024f);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.CHINA, "%.0f KB", bytes / 1024f);
        }
        return bytes + " B";
    }

    private void showClearMapCacheDialog() {
        if (tvMapCacheSize != null) {
            tvMapCacheSize.setText(R.string.map_cache_calculating);
        }
        int requestGeneration = ++mapCacheDialogGeneration;
        new Thread(() -> {
            long bytes = mapCacheSizeBytes();
            runOnUiThread(() -> {
                if (isFinishing()
                        || isDestroyed()
                        || requestGeneration != mapCacheDialogGeneration
                        || settingsPanel.getVisibility() != View.VISIBLE) {
                    return;
                }
                showClearMapCacheDialog(bytes);
            });
        }, "map-cache-clear-size").start();
    }

    private void showClearMapCacheDialog(long bytes) {
        if (bytes <= 0L) {
            Toast.makeText(this, R.string.map_cache_empty, Toast.LENGTH_SHORT).show();
            updateMapCacheSizeLabel();
            return;
        }
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_map_cache, null);
        TextView message = content.findViewById(R.id.tvMapCacheMessage);
        message.setText(getString(
                R.string.map_cache_clear_message,
                formatByteSize(bytes)
        ));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.map_cache_clear_title)
                .setView(content)
                .setPositiveButton(
                        R.string.confirm,
                        (ignored, which) -> clearMapCache(bytes)
                )
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        applyWideDialogSize(dialog);
    }

    /**
     * 清除地图瓦片缓存。osmdroid 的 purgeCache() 只删除表内数据、不归还磁盘空间，
     * 因此随后对缓存库执行 VACUUM。两步都放在后台线程，避免大缓存卡住界面。
     */
    private void clearMapCache(long beforeBytes) {
        new Thread(() -> {
            boolean purged = false;
            try {
                org.osmdroid.tileprovider.modules.IFilesystemCache writer =
                        mapView.getTileProvider().getTileWriter();
                if (writer instanceof org.osmdroid.tileprovider.modules.SqlTileWriter) {
                    purged = ((org.osmdroid.tileprovider.modules.SqlTileWriter) writer)
                            .purgeCache();
                }
            } catch (RuntimeException ignored) {
                // 按清理后的实际占用判断结果。
            }
            boolean vacuumed = vacuumMapCacheDatabase();
            // 在后台线程紧接 VACUUM 后测量，避免把随后新下载的瓦片算进本次结果。
            long afterBytes = mapCacheSizeBytes();
            boolean purgedResult = purged;
            runOnUiThread(() -> {
                mapView.getTileProvider().clearTileCache();
                mapView.invalidate();
                updateMapCacheSizeLabel();
                boolean cleared = purgedResult
                        && (afterBytes <= MAP_CACHE_SMALL_REMAINDER_BYTES
                        || afterBytes <= beforeBytes / 4L);
                long released = Math.max(0L, beforeBytes - afterBytes);
                Toast.makeText(
                        this,
                        cleared
                                ? getString(
                                        R.string.map_cache_cleared,
                                        formatByteSize(released)
                                )
                                : getString(R.string.map_cache_clear_failed),
                        Toast.LENGTH_LONG
                ).show();
            });
        }, "clear-map-cache").start();
    }

    /** VACUUM 回收 sqlite 空间；数据库被占用时短暂重试。 */
    private boolean vacuumMapCacheDatabase() {
        java.io.File dir = osmdroidTileCacheDir();
        if (dir == null) {
            return false;
        }
        java.io.File database = new java.io.File(dir, "cache.db");
        if (!database.isFile()) {
            return false;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try (android.database.sqlite.SQLiteDatabase handle =
                         android.database.sqlite.SQLiteDatabase.openDatabase(
                                 database.getAbsolutePath(),
                                 null,
                                 android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                         )) {
                handle.execSQL("VACUUM");
                return true;
            } catch (RuntimeException exception) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private void showPrivacyDialog() {
        View content = createInfoContent(getString(R.string.privacy_content));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_privacy)
                .setView(content)
                .setCancelable(true)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.show();
        applyInfoDialogSize(dialog, content);
    }

    private void showInfoDialog(String title, String message) {
        View content = createInfoContent(message, true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.show();
        applyInfoDialogSize(dialog, content);
    }

    private View createInfoContent(String message) {
        return createInfoContent(message, false);
    }

    private View createInfoContent(String message, boolean strongHeadings) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_info, null);
        LinearLayout sections = content.findViewById(R.id.infoSections);

        String[] blocks = message.split("\n\n");
        for (String block : blocks) {
            String value = block.trim();
            if (value.isEmpty()) {
                continue;
            }
            boolean firstBlock = sections.getChildCount() == 0;
            String[] lines = value.split("\n", 2);
            String headingText = null;
            String bodyText;

            if (lines.length == 2 && isSectionHeading(lines[0].trim())) {
                headingText = lines[0].trim();
                bodyText = lines[1].trim();
            } else if (lines.length == 1 && isSectionHeading(value)) {
                headingText = value;
                bodyText = "";
            } else {
                bodyText = value;
            }

            // 许可鸣谢等文本用 "## " 前缀标记分组标题，渲染时去掉前缀，
            // 这样分组样式不再依赖 Java 里写死的标题文字。
            boolean groupHeading = false;
            if (headingText != null && headingText.startsWith(GROUP_HEADING_MARKER)) {
                groupHeading = true;
                headingText = headingText.substring(GROUP_HEADING_MARKER.length()).trim();
            }

            if (headingText != null) {
                TextView heading = textView(
                        headingText,
                        strongHeadings ? 16 : 15,
                        R.color.text_primary,
                        true
                );
                if (strongHeadings) {
                    heading.setTextSize(groupHeading ? 20 : 15);
                    heading.setTypeface(
                            android.graphics.Typeface.create(
                                    groupHeading ? "sans-serif-black" : "sans-serif-medium",
                                    android.graphics.Typeface.NORMAL
                            )
                    );
                    if (groupHeading) {
                        heading.setTextColor(getColor(R.color.settings_control_accent));
                        heading.setLetterSpacing(0.03f);
                    }
                    if (!firstBlock) {
                        heading.setPadding(
                                0,
                                dp(groupHeading ? 32 : 16),
                                0,
                                dp(groupHeading ? 2 : 0)
                        );
                    }
                } else if (!firstBlock) {
                    heading.setPadding(0, dp(14), 0, 0);
                }
                sections.addView(heading);
            }

            if (bodyText.isEmpty()) {
                continue;
            }
            TextView body = textView(
                    bodyText,
                    14,
                    R.color.text_secondary,
                    firstBlock && headingText == null
            );
            Linkify.addLinks(body, Linkify.WEB_URLS);
            body.setMovementMethod(LinkMovementMethod.getInstance());
            body.setLinkTextColor(getColor(R.color.control_accent_text));
            body.setLineSpacing(dp(3), 1.0f);
            body.setPadding(0, dp(4), 0, 0);
            sections.addView(body);
        }
        return content;
    }


    private boolean isSectionHeading(String value) {
        if (value.isEmpty()) {
            return false;
        }
        return !value.endsWith("。")
                && !value.endsWith("！")
                && !value.endsWith("？")
                && !value.endsWith("；");
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

    private void showReminderEntry() {
        SharedPreferences preferences = appPreferences;
        if (!preferences.getBoolean(PREF_REMINDER_SETUP_PROMPTED, false)
                || !hasRequiredReminderPermissions()) {
            beginReminderFirstUseFlow();
            return;
        }
        // The risk notice keeps appearing on every entry until the user ticks
        // "do not show again"; the reminder dialog opens once it is accepted.
        showReminderRiskNotice(
                this::showReminderDialog,
                this::resetReminderSetupState
        );
    }

    @SuppressLint("InlinedApi")
    private boolean hasRequiredReminderPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !ReminderScheduler.canScheduleExactAlarms(this)) {
            return false;
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return false;
        }
        String alertMode = appPreferences.getString(
                PREF_REMINDER_ALERT_MODE,
                ReminderScheduler.MODE_SOUND_VIBRATE
        );
        if (ReminderScheduler.isAlertChannelWeakened(this, alertMode)) {
            return false;
        }
        // 厂商的“允许后台活动/自启动”无法可靠读取，按一次性引导状态补走流程；
        // 标准电池优化则可以实际检查。
        return isIgnoringBatteryOptimizations()
                && appPreferences.getBoolean(PREF_REMINDER_OEM_GUIDE_SHOWN, false);
    }

    private void beginReminderFirstUseFlow() {
        reminderFlow = REMINDER_FLOW_FIRST_USE;
        showReminderRiskNotice(
                this::showReminderPermissionDialog,
                this::resetReminderSetupState
        );
    }

    private void showReminderPermissionDialog() {
        if (permissionDialogShowing) {
            return;
        }
        permissionDialogShowing = true;
        View content = LayoutInflater.from(this).inflate(
                R.layout.dialog_reminder_permission,
                null
        );
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_permission_title)
                .setView(content)
                .setCancelable(false)
                .setPositiveButton(R.string.permission_continue, (ignored, which) -> {
                    appPreferences
                            .edit()
                            .putBoolean(PREF_REMINDER_SETUP_PROMPTED, true)
                            .apply();
                    continueReminderFirstUsePermissions();
                })
                .setNegativeButton(R.string.permission_not_now, (ignored, which) -> {
                    appPreferences
                            .edit()
                            .putBoolean(PREF_REMINDER_SETUP_PROMPTED, true)
                            .apply();
                    resetReminderSetupState();
                    showReminderDialog();
                })
                .create();
        dialog.setOnDismissListener(ignored -> permissionDialogShowing = false);
        dialog.show();
        applyWideDialogSize(dialog);
    }

    @SuppressLint("InlinedApi")
    private void continueReminderFirstUsePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
            return;
        }
        continueReminderFirstUseExactAlarm();
    }

    private void continueReminderFirstUseExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !ReminderScheduler.canScheduleExactAlarms(this)) {
            showReminderExactAlarmIntroDialog();
            return;
        }
        continueReminderChannelCheck();
    }

    /**
     * Android 13+ allows each notification channel to be switched off on its
     * own. The app cannot turn them back on, so this surfaces the deepest
     * setting page for whatever category the reminder actually uses.
     */
    private void continueReminderChannelCheck() {
        continueReminderChannelCheck(true);
    }

    /**
     * @param allowSystemJump 从系统设置页返回时必须传 false：用户没有开启通知时再自动
     *                        跳转，会形成"回来→再跳走"的死循环，这里改为停止本次设置流程。
     */
    private void continueReminderChannelCheck(boolean allowSystemJump) {
        ReminderScheduler.ensureNotificationChannels(this);
        boolean masterOff = !NotificationManagerCompat.from(this).areNotificationsEnabled();
        boolean weakened = ReminderScheduler.isAlertChannelWeakened(
                this,
                pendingReminderAlertMode
        );
        boolean firstSetup = reminderFlow == REMINDER_FLOW_FIRST_USE
                && !appPreferences.getBoolean(PREF_REMINDER_CHANNEL_GUIDE_SHOWN, false);
        if (!masterOff && !weakened && !firstSetup) {
            continueAfterChannelCheck();
            return;
        }
        if (!allowSystemJump) {
            Toast.makeText(this, R.string.reminder_channel_still_off, Toast.LENGTH_LONG).show();
            resetReminderSetupState();
            return;
        }
        // Jump straight to the notification page and leave a short hint; the
        // system never lets an app switch these categories on by itself.
        Toast.makeText(this, R.string.reminder_channel_hint, Toast.LENGTH_LONG).show();
        // Keep this screen long enough for the hint to be readable before the
        // system page takes over.
        new Handler(Looper.getMainLooper()).postDelayed(
                this::openReminderChannelSettings,
                CHANNEL_HINT_DELAY_MS
        );
    }

    private void openReminderChannelSettings() {
        persistReminderFlowStep("channel");
        awaitingReminderChannelPermission = true;
        appPreferences.edit()
                .putBoolean(PREF_REMINDER_CHANNEL_GUIDE_SHOWN, true)
                .apply();
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        } catch (Exception ignored) {
            // Fall back to the application details page.
        }
        try {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            ));
        } catch (Exception ignored) {
            awaitingReminderChannelPermission = false;
            continueAfterChannelCheck();
        }
    }

    private void continueAfterChannelCheck() {
        if (reminderFlow == REMINDER_FLOW_FIRST_USE) {
            continueReminderFirstUseBatteryOptimization();
        } else if (reminderFlow == REMINDER_FLOW_SCHEDULE) {
            continueScheduleBatteryOptimization();
        } else {
            resetReminderSetupState();
        }
    }

    @SuppressLint("InlinedApi")
    private void showReminderExactAlarmIntroDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_exact_alarm_title)
                .setMessage(R.string.reminder_exact_alarm_message)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.reminder_exact_alarm_open,
                        (ignored, which) -> openExactAlarmSettings()
                )
                .setNegativeButton(
                        R.string.permission_not_now,
                        (ignored, which) -> showExactAlarmRequiredAndReturn()
                )
                .create();
        dialog.show();
    }

    @SuppressLint("BatteryLife")
    private void continueReminderFirstUseBatteryOptimization() {
        if (isIgnoringBatteryOptimizations()) {
            offerReminderOemBackgroundGuidance(this::finishReminderFirstUseFlow);
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_battery_optimization_title)
                .setMessage(R.string.reminder_battery_optimization_message)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.reminder_battery_optimization_open,
                        (ignored, which) -> openBatteryOptimizationSettings()
                )
                .setNegativeButton(
                        R.string.permission_not_now,
                        (ignored, which) -> {
                            awaitingBatteryOptimizationPermission = false;
                            finishReminderFirstUseFlow();
                        }
                )
                .create();
        dialog.show();
    }

    private void finishReminderFirstUseFlow() {
        appPreferences
                .edit()
                .putBoolean(PREF_REMINDER_SETUP_PROMPTED, true)
                .apply();
        resetReminderSetupState();
        showReminderDialog();
    }

    @SuppressLint("BatteryLife")
    private void openBatteryOptimizationSettings() {
        persistReminderFlowStep("battery");
        awaitingBatteryOptimizationPermission = true;
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
            return;
        } catch (Exception ignored) {
            // Some vendor builds do not expose the direct request screen.
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        } catch (Exception ignored) {
            // Fall back to the per-app detail page.
        }
        awaitingBatteryOptimizationPermission = false;
        showBackgroundSettingsRequiredDialog();
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager powerManager =
                (PowerManager) getSystemService(Context.POWER_SERVICE);
        return powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void showBackgroundSettingsRequiredDialog() {
        if (backgroundSettingsDialogShowing) {
            return;
        }
        backgroundSettingsDialogShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_background_settings_title)
                .setMessage(R.string.reminder_background_settings_message)
                .setPositiveButton(
                        R.string.reminder_background_settings_open,
                        (ignored, which) -> openAppDetailsSettings()
                )
                .setNegativeButton(
                        R.string.permission_not_now,
                        (ignored, which) -> continueAfterBackgroundSettings()
                )
                .create();
        dialog.setOnDismissListener(ignored -> backgroundSettingsDialogShowing = false);
        dialog.show();
    }

    private void openAppDetailsSettings() {
        // Re-check as soon as the user comes back from the system page so the
        // reminder flow continues by itself once background running is allowed.
        persistReminderFlowStep("battery");
        awaitingBatteryOptimizationPermission = true;
        try {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            ));
            return;
        } catch (Exception ignored) {
            // Fall back to the vendor battery page below.
        }
        if (openOemPowerSettings()) {
            return;
        }
        awaitingBatteryOptimizationPermission = false;
    }

    private boolean openOemPowerSettings() {
        String manufacturer = Build.MANUFACTURER == null
                ? ""
                : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        if (!manufacturer.contains("oneplus")
                && !manufacturer.contains("oppo")
                && !manufacturer.contains("realme")) {
            return false;
        }
        Intent intent = new Intent()
                .setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.fuelgauge.AdvancedPowerUsageDetailActivity"
                ))
                .putExtra("package_name", getPackageName());
        try {
            startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void continueAfterBackgroundSettings() {
        if (reminderFlow == REMINDER_FLOW_FIRST_USE) {
            offerReminderOemBackgroundGuidance(this::finishReminderFirstUseFlow);
        } else if (reminderFlow == REMINDER_FLOW_SCHEDULE) {
            offerReminderOemBackgroundGuidance(this::finishScheduleReminder);
        } else {
            resetReminderSetupState();
        }
    }

    private void showReminderDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_reminder, null);
        TextView countdown = content.findViewById(R.id.tvReminderCountdown);
        TextView status = content.findViewById(R.id.tvReminderStatus);
        TextView riskLink = content.findViewById(R.id.tvReminderRiskNotice);
        RadioGroup leadGroup = content.findViewById(R.id.rgReminderLead);
        RadioGroup modeGroup = content.findViewById(R.id.rgReminderMode);
        RadioGroup intervalGroup = content.findViewById(R.id.rgVehicleAlertInterval);
        boolean active = ReminderScheduler.isActive(this);
        int selectedLead = active
                ? ReminderScheduler.scheduledLeadMinutes(this)
                : appPreferences.getInt(PREF_REMINDER_LEAD_MINUTES, 0);
        String selectedMode = active
                ? ReminderScheduler.scheduledAlertMode(this)
                : appPreferences.getString(
                        PREF_REMINDER_ALERT_MODE,
                        ReminderScheduler.MODE_SOUND_VIBRATE
                );
        selectReminderLead(leadGroup, selectedLead);
        selectReminderMode(modeGroup, selectedMode);
        bindVehicleAlertInterval(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_dialog_title)
                .setView(content)
                .setPositiveButton(R.string.reminder_start, null)
                .setNegativeButton(R.string.cancel, null);
        AlertDialog dialog = builder.create();
        riskLink.setOnClickListener(view -> showReminderRiskNotice(
                true,
                null,
                dialog::dismiss
        ));
        dialog.setOnShowListener(ignored -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setText(active ? R.string.reminder_update : R.string.reminder_start);
            Runnable refreshPositive = () -> {
                persistReminderSelections(leadGroup);
                boolean hasLead = leadGroup.getCheckedRadioButtonId() != -1;
                boolean hasInterval = intervalGroup.getVisibility() == View.VISIBLE
                        && intervalGroup.getCheckedRadioButtonId() != -1;
                int selectedInterval = hasInterval
                        ? alertIntervalFromId(intervalGroup.getCheckedRadioButtonId())
                        : 0;
                int runningInterval = currentVehicleAlertInterval();
                boolean timerRowVisible = intervalGroup.getVisibility() == View.VISIBLE;
                boolean timerChanged = timerRowVisible
                        && (hasInterval
                        ? selectedInterval != runningInterval
                        : runningInterval > 0);
                // 倒计时进行中、或车辆预警计时有变化时按钮保持可点。
                boolean enabled = hasLead || active || timerChanged;
                positive.setEnabled(enabled);
                positive.setAlpha(enabled ? 1f : 0.4f);
                // 只改车辆预警计时的时候，按钮直接写明对应的计时动作。
                boolean timerOnly = timerChanged && !hasLead && !active;
                if (timerOnly) {
                    if (selectedInterval <= 0) {
                        positive.setText(R.string.vehicle_alert_timer_off);
                    } else if (runningInterval > 0) {
                        positive.setText(R.string.vehicle_alert_timer_update);
                    } else {
                        positive.setText(R.string.vehicle_alert_timer_on);
                    }
                } else {
                    positive.setText(active
                            ? R.string.reminder_update
                            : R.string.reminder_start);
                }
            };
            makeChipsDeselectable(leadGroup, refreshPositive);
            makeChipsDeselectable(intervalGroup, refreshPositive);
            refreshPositive.run();
            positive.setOnClickListener(view -> {
                int leadMinutes = selectedReminderLead(leadGroup);
                String alertMode = selectedReminderMode(modeGroup);
                int alertInterval = intervalGroup.getVisibility() == View.VISIBLE
                        ? alertIntervalFromId(intervalGroup.getCheckedRadioButtonId())
                        : 0;
                dialog.dismiss();
                if (leadMinutes > 0) {
                    if (active) {
                        updateActiveReminder(leadMinutes, alertMode);
                    } else {
                        beginReminderSchedule(leadMinutes, alertMode);
                    }
                } else if (active) {
                    // 提前提醒被取消 → 连同倒计时一起停止。
                    ReminderScheduler.cancel(this);
                    updateReminderButtonState();
                    Toast.makeText(this, R.string.reminder_cancelled, Toast.LENGTH_SHORT).show();
                }
                applyVehicleAlertSelection(alertInterval);
            });
            bindReminderCountdown(countdown, status);
        });
        dialog.setOnDismissListener(ignored -> cancelReminderDialogTimer());
        dialog.show();
    }

    /**
     * 记住提前提醒的选择，下次打开沿用（0 = 未选择）。
     * 车辆预警计时只在点确认时写入，点“取消”不会改动计时。
     */
    private void persistReminderSelections(RadioGroup leadGroup) {
        appPreferences
                .edit()
                .putInt(PREF_REMINDER_LEAD_MINUTES, Math.max(0, selectedReminderLead(leadGroup)))
                .apply();
    }

    /** Lets a chip be tapped again to clear the whole group. */
    private void makeChipsDeselectable(RadioGroup group, Runnable onChanged) {
        chipLastChecked.put(group.getId(), group.getCheckedRadioButtonId());
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            child.setOnClickListener(view -> {
                int id = child.getId();
                Integer last = chipLastChecked.get(group.getId());
                if (last != null && last == id) {
                    group.clearCheck();
                    chipLastChecked.put(group.getId(), -1);
                } else {
                    chipLastChecked.put(group.getId(), id);
                }
                onChanged.run();
            });
        }
    }

    /**
     * 应用提醒页对车辆预警计时的选择：选中间隔 = 开启/更新计时，
     * 清空（未选择）= 关闭计时。这里只改计时，
     * 功能开关始终由高级设置里的开关决定，不会被这里改动。
     */
    private void applyVehicleAlertSelection(int intervalSeconds) {
        if (!appPreferences.getBoolean(PREF_VEHICLE_ALERT_ENABLED, false)) {
            return;
        }
        int interval = Math.max(0, intervalSeconds);
        int current = currentVehicleAlertInterval();
        if (interval == current) {
            return;
        }
        appPreferences
                .edit()
                .putInt(PREF_VEHICLE_ALERT_INTERVAL, interval)
                .apply();
        applyVehicleAlertState(true);
        int message;
        if (interval <= 0) {
            message = R.string.vehicle_alert_timer_stopped;
        } else if (current > 0) {
            message = R.string.vehicle_alert_timer_updated;
        } else {
            message = R.string.vehicle_alert_timer_started;
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** 计时开关的当前值：0 表示计时关闭。 */
    private int currentVehicleAlertInterval() {
        return Math.max(
                0,
                appPreferences.getInt(PREF_VEHICLE_ALERT_INTERVAL, 0)
        );
    }

    private void updateActiveReminder(int leadMinutes, String alertMode) {
        long startAt = ReminderScheduler.scheduledStartAt(this);
        long endAt = ReminderScheduler.scheduledEndAt(this);
        // Re-arming with a new lead time must be able to alert again for the
        // same countdown (e.g. already alerted, user switches to 3 minutes).
        ReminderScheduler.resetAlertDelivered(this);
        boolean scheduled = ReminderScheduler.schedule(
                this,
                startAt,
                endAt,
                leadMinutes,
                alertMode
        );
        Toast.makeText(
                this,
                scheduled
                        ? R.string.reminder_updated
                        : reminderScheduleFailureMessage(endAt),
                Toast.LENGTH_LONG
        ).show();
        updateReminderButtonState();
    }

    private int reminderScheduleFailureMessage(long endAt) {
        if (endAt <= System.currentTimeMillis()) {
            return R.string.reminder_expired;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !ReminderScheduler.canScheduleExactAlarms(this)) {
            return R.string.reminder_exact_alarm_message;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            return R.string.reminder_notification_settings_message;
        }
        return R.string.reminder_schedule_failed;
    }

    private void bindReminderCountdown(TextView countdown, TextView status) {
        cancelReminderDialogTimer();
        long remaining = ReminderScheduler.remainingMs(this);
        if (remaining <= 0L) {
            countdown.setText(R.string.reminder_default_countdown);
            status.setText(R.string.reminder_timer_hint);
            return;
        }

        countdown.setText(formatReminderTime(remaining));
        status.setText(getString(
                R.string.reminder_active_status,
                ReminderScheduler.scheduledLeadMinutes(this)
        ));
        reminderDialogTimer = new CountDownTimer(remaining, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                countdown.setText(formatReminderTime(millisUntilFinished));
            }

            @Override
            public void onFinish() {
                countdown.setText(R.string.reminder_default_countdown_zero);
                status.setText(R.string.reminder_finished_status);
                updateReminderButtonState();
            }
        };
        reminderDialogTimer.start();
    }

    private String formatReminderTime(long remainingMs) {
        long totalSeconds = Math.max(0L, (remainingMs + 999L) / 1_000L);
        return String.format(
                Locale.CHINA,
                "%02d:%02d",
                totalSeconds / 60L,
                totalSeconds % 60L
        );
    }

    private void cancelReminderDialogTimer() {
        if (reminderDialogTimer != null) {
            reminderDialogTimer.cancel();
            reminderDialogTimer = null;
        }
    }

    private void showReminderRiskNotice() {
        showReminderRiskNotice(true, null, null);
    }

    private void showReminderRiskNotice(Runnable onAccepted, Runnable onCancelled) {
        showReminderRiskNotice(false, onAccepted, onCancelled);
    }

    private void showReminderRiskNotice(
            boolean force,
            Runnable onAccepted,
            Runnable onCancelled
    ) {
        SharedPreferences preferences = appPreferences;
        boolean muted = preferences.getBoolean(PREF_REMINDER_RISK_MUTED, false);
        if (muted && !force) {
            if (onAccepted != null) {
                onAccepted.run();
            }
            return;
        }

        View content = LayoutInflater.from(this).inflate(
                R.layout.dialog_reminder_notice,
                null
        );
        TextView message = content.findViewById(R.id.tvReminderNoticeMessage);
        message.setText(R.string.reminder_risk_message);
        CheckBox mute = content.findViewById(R.id.cbReminderRiskMute);
        mute.setChecked(muted);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_risk_title)
                .setView(content)
                .setCancelable(false)
                .setPositiveButton(R.string.reminder_risk_accept, (ignored, which) -> {
                    preferences.edit()
                            .putBoolean(PREF_REMINDER_RISK_MUTED, mute.isChecked())
                            .apply();
                    if (onAccepted != null) {
                        onAccepted.run();
                    }
                })
                .setNegativeButton(R.string.reminder_risk_exit, (ignored, which) -> {
                    if (onCancelled != null) {
                        onCancelled.run();
                    }
                })
                .create();
        dialog.show();
        applyWideDialogSize(dialog);
    }

    private void applyWideDialogSize(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setLayout(
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.92f),
                WindowManager.LayoutParams.WRAP_CONTENT
        );
    }

    private void selectReminderLead(RadioGroup group, int leadMinutes) {
        if (leadMinutes <= 0) {
            group.clearCheck();
            return;
        }
        int id;
        if (leadMinutes == 1) {
            id = R.id.rbReminderLead1;
        } else if (leadMinutes == 5) {
            id = R.id.rbReminderLead5;
        } else if (leadMinutes == 7) {
            id = R.id.rbReminderLead7;
        } else if (leadMinutes == 10) {
            id = R.id.rbReminderLead10;
        } else {
            id = R.id.rbReminderLead3;
        }
        group.check(id);
    }

    private int selectedReminderLead(RadioGroup group) {
        int checked = group.getCheckedRadioButtonId();
        if (checked == -1) {
            return -1;
        }
        if (checked == R.id.rbReminderLead1) {
            return 1;
        }
        if (checked == R.id.rbReminderLead5) {
            return 5;
        }
        if (checked == R.id.rbReminderLead7) {
            return 7;
        }
        if (checked == R.id.rbReminderLead10) {
            return 10;
        }
        return 3;
    }

    private void selectReminderMode(RadioGroup group, String alertMode) {
        int id = ReminderScheduler.MODE_VIBRATE.equals(alertMode)
                ? R.id.rbReminderVibrate
                : ReminderScheduler.MODE_SILENT.equals(alertMode)
                ? R.id.rbReminderSilent
                : R.id.rbReminderSound;
        group.check(id);
    }

    private String selectedReminderMode(RadioGroup group) {
        int checked = group.getCheckedRadioButtonId();
        if (checked == R.id.rbReminderVibrate) {
            return ReminderScheduler.MODE_VIBRATE;
        }
        if (checked == R.id.rbReminderSilent) {
            return ReminderScheduler.MODE_SILENT;
        }
        return ReminderScheduler.MODE_SOUND_VIBRATE;
    }

    @SuppressLint("InlinedApi")
    private void beginReminderSchedule(int leadMinutes, String alertMode) {
        long now = System.currentTimeMillis();
        long endAt = now + ReminderScheduler.DURATION_MS;
        pendingReminderLeadMinutes = leadMinutes;
        pendingReminderAlertMode = alertMode;
        pendingReminderStartAt = endAt - ReminderScheduler.DURATION_MS;
        pendingReminderEndAt = endAt;
        reminderFlow = REMINDER_FLOW_SCHEDULE;
        persistPendingReminderData();
        continueScheduleExactAlarm();
    }

    @SuppressLint("InlinedApi")
    private void continueScheduleExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            showReminderNotificationPermissionDialog();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !ReminderScheduler.canScheduleExactAlarms(this)) {
            showExactAlarmPermissionDialog();
            return;
        }
        continueReminderChannelCheck();
    }

    private void showExactAlarmPermissionDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_exact_alarm_title)
                .setMessage(R.string.reminder_exact_alarm_message)
                .setPositiveButton(
                        R.string.reminder_exact_alarm_open,
                        (ignored, which) -> openExactAlarmSettings()
                )
                .setNegativeButton(
                        R.string.permission_not_now,
                        (ignored, which) -> resetReminderSetupState()
                )
                .create();
        dialog.show();
    }

    @SuppressLint("InlinedApi")
    private void showReminderNotificationPermissionDialog() {
        if (permissionDialogShowing) {
            return;
        }
        permissionDialogShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_notification_permission_title)
                .setMessage(R.string.reminder_notification_permission_message)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.permission_continue,
                        (ignored, which) -> requestPermissions(
                                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                REQUEST_NOTIFICATIONS
                        )
                )
                .setNegativeButton(
                        R.string.permission_not_now,
                        (ignored, which) -> {
                            boolean firstUse = reminderFlow == REMINDER_FLOW_FIRST_USE;
                            resetReminderSetupState();
                            if (firstUse) {
                                showReminderDialog();
                            }
                        }
                )
                .create();
        dialog.setOnDismissListener(ignored -> permissionDialogShowing = false);
        dialog.show();
    }

    private void showNotificationPermissionRequiredDialog() {
        showNotificationPermissionRequiredDialog(reminderFlow);
    }

    private void showNotificationPermissionRequiredDialog(int pendingFlow) {
        if (pendingFlow == REMINDER_FLOW_FIRST_USE
                || pendingFlow == REMINDER_FLOW_SCHEDULE) {
            reminderFlow = pendingFlow;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_notification_settings_title)
                .setMessage(R.string.reminder_notification_settings_message)
                .setPositiveButton(
                        R.string.reminder_exact_alarm_open,
                        (ignored, which) -> openReminderChannelSettings()
                )
                .setNegativeButton(R.string.permission_not_now, null)
                .create();
        dialog.show();
    }

    private boolean openAppNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return true;
        } catch (RuntimeException ignored) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())
                ));
                return true;
            } catch (RuntimeException ignoredAgain) {
                // No system settings page is available on this device.
                return false;
            }
        }
    }

    @SuppressLint("InlinedApi")
    private void openExactAlarmSettings() {
        persistReminderFlowStep("exact_alarm");
        awaitingExactAlarmPermission = true;
        Intent packageIntent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(packageIntent);
            return;
        } catch (Exception ignored) {
            // Some vendor builds only accept the generic settings action.
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
        } catch (Exception ignored) {
            awaitingExactAlarmPermission = false;
            showExactAlarmRequiredAndReturn();
        }
    }

    private void resumeExactAlarmPermissionFlow() {
        if (!awaitingExactAlarmPermission) {
            return;
        }
        awaitingExactAlarmPermission = false;
        if (!ReminderScheduler.canScheduleExactAlarms(this)) {
            showExactAlarmRequiredAndReturn();
            return;
        }
        // 精确闹钟设置页返回后仍需检查通知类别，避免跳过后台运行申请。
        continueReminderChannelCheck();
    }

    private void showExactAlarmRequiredAndReturn() {
        ReminderScheduler.cancel(this);
        resetReminderSetupState();
        updateReminderButtonState();
        showExactAlarmPermissionDialog();
    }

    @SuppressLint("BatteryLife")
    private void continueScheduleBatteryOptimization() {
        if (isIgnoringBatteryOptimizations()) {
            offerReminderOemBackgroundGuidance(this::finishScheduleReminder);
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_battery_optimization_title)
                .setMessage(R.string.reminder_battery_optimization_message)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.reminder_battery_optimization_open,
                        (ignored, which) -> openBatteryOptimizationSettings()
                )
                .setNegativeButton(
                        R.string.permission_not_now,
                        (ignored, which) -> {
                            awaitingBatteryOptimizationPermission = false;
                            finishScheduleReminder();
                        }
                )
                .create();
        dialog.show();
    }

    private void finishScheduleReminder() {
        if (!ReminderScheduler.canScheduleExactAlarms(this)) {
            showExactAlarmRequiredAndReturn();
            return;
        }
        schedulePendingReminder();
    }

    private void schedulePendingReminder() {
        boolean scheduled = ReminderScheduler.schedule(
                this,
                pendingReminderStartAt,
                pendingReminderEndAt,
                pendingReminderLeadMinutes,
                pendingReminderAlertMode
        );
        if (scheduled) {
            Toast.makeText(
                    this,
                    getString(
                            R.string.reminder_started_exact,
                            pendingReminderLeadMinutes
                    ),
                    Toast.LENGTH_LONG
            ).show();
        } else {
            int failureMessage = reminderScheduleFailureMessage(pendingReminderEndAt);
            Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show();
        }
        resetReminderSetupState();
        updateReminderButtonState();
    }

    private void resetReminderSetupState() {
        pendingReminderLeadMinutes = ReminderScheduler.DEFAULT_LEAD_MINUTES;
        pendingReminderAlertMode = ReminderScheduler.MODE_SOUND_VIBRATE;
        pendingReminderStartAt = 0L;
        pendingReminderEndAt = 0L;
        awaitingExactAlarmPermission = false;
        awaitingBatteryOptimizationPermission = false;
        awaitingReminderChannelPermission = false;
        reminderFlow = REMINDER_FLOW_NONE;
        clearReminderFlowStep();
    }

    /** 记录当前等待用户操作的系统页面，便于 Activity/进程被重建后继续流程。 */
    private void persistReminderFlowStep(String step) {
        appPreferences.edit()
                .putInt(PREF_REMINDER_PENDING_FLOW, reminderFlow)
                .putString(PREF_REMINDER_PENDING_STEP, step == null ? "" : step)
                .putLong(PREF_REMINDER_PENDING_START_AT, pendingReminderStartAt)
                .putLong(PREF_REMINDER_PENDING_END_AT, pendingReminderEndAt)
                .putInt(PREF_REMINDER_PENDING_LEAD_MINUTES, pendingReminderLeadMinutes)
                .putString(PREF_REMINDER_PENDING_ALERT_MODE, pendingReminderAlertMode)
                .commit();
    }

    private void persistPendingReminderData() {
        appPreferences.edit()
                .putLong(PREF_REMINDER_PENDING_START_AT, pendingReminderStartAt)
                .putLong(PREF_REMINDER_PENDING_END_AT, pendingReminderEndAt)
                .putInt(PREF_REMINDER_PENDING_LEAD_MINUTES, pendingReminderLeadMinutes)
                .putString(PREF_REMINDER_PENDING_ALERT_MODE, pendingReminderAlertMode)
                .commit();
    }

    private void clearReminderFlowStep() {
        appPreferences.edit()
                .putInt(PREF_REMINDER_PENDING_FLOW, REMINDER_FLOW_NONE)
                .putString(PREF_REMINDER_PENDING_STEP, "")
                .remove(PREF_REMINDER_PENDING_START_AT)
                .remove(PREF_REMINDER_PENDING_END_AT)
                .remove(PREF_REMINDER_PENDING_LEAD_MINUTES)
                .remove(PREF_REMINDER_PENDING_ALERT_MODE)
                .apply();
    }

    /**
     * 跳系统设置页时应用可能被系统回收，导致权限流程只走了一半。
     * 这里把未完成的流程记在本机，重建后继续申请精确闹钟/后台运行等权限。
     */
    private void restoreReminderFlowStep() {
        int flow = appPreferences.getInt(PREF_REMINDER_PENDING_FLOW, REMINDER_FLOW_NONE);
        String step = appPreferences.getString(PREF_REMINDER_PENDING_STEP, "");
        if (flow == REMINDER_FLOW_NONE || step == null || step.isEmpty()) {
            return;
        }
        long startAt = appPreferences.getLong(PREF_REMINDER_PENDING_START_AT, 0L);
        long endAt = appPreferences.getLong(PREF_REMINDER_PENDING_END_AT, 0L);
        if (startAt <= 0L || endAt <= System.currentTimeMillis()) {
            resetReminderSetupState();
            return;
        }
        pendingReminderStartAt = startAt;
        pendingReminderEndAt = endAt;
        pendingReminderLeadMinutes = appPreferences.getInt(
                PREF_REMINDER_PENDING_LEAD_MINUTES,
                ReminderScheduler.DEFAULT_LEAD_MINUTES
        );
        pendingReminderAlertMode = appPreferences.getString(
                PREF_REMINDER_PENDING_ALERT_MODE,
                ReminderScheduler.MODE_SOUND_VIBRATE
        );
        reminderFlow = flow;
        if ("exact_alarm".equals(step)) {
            awaitingExactAlarmPermission = true;
        } else if ("channel".equals(step)) {
            awaitingReminderChannelPermission = true;
        } else if ("battery".equals(step)) {
            awaitingBatteryOptimizationPermission = true;
        }
    }

    /** 已放行电池优化时，部分机型还需厂商级「后台活动/自启动」才不会被冻结。 */
    private void offerReminderOemBackgroundGuidance(Runnable onDone) {
        if (appPreferences.getBoolean(PREF_REMINDER_OEM_GUIDE_SHOWN, false)) {
            onDone.run();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_background_settings_title)
                .setMessage(R.string.reminder_background_settings_message)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.reminder_background_settings_open,
                        (ignored, which) -> {
                            appPreferences.edit()
                                    .putBoolean(PREF_REMINDER_OEM_GUIDE_SHOWN, true)
                                    .apply();
                            openAppDetailsSettings();
                        }
                )
                .setNegativeButton(
                        R.string.permission_not_now,
                        (ignored, which) -> {
                            appPreferences.edit()
                                    .putBoolean(PREF_REMINDER_OEM_GUIDE_SHOWN, true)
                                    .apply();
                            onDone.run();
                        }
                )
                .create();
        dialog.show();
    }

    private void updateReminderButtonState() {
        if (btnReminder == null) {
            return;
        }
        boolean active = ReminderScheduler.isActive(this);
        btnReminder.setImageTintList(ColorStateList.valueOf(getColor(
                active ? R.color.control_accent : R.color.map_control_icon
        )));
        btnReminder.setContentDescription(getString(
                active ? R.string.reminder_button_active : R.string.reminder_button
        ));
    }

    private void exitApplication() {
        finishAffinity();
        System.exit(0);
    }

    private void handleMapBackgroundTap() {
        if (SystemClock.uptimeMillis() - lastMarkerTapAt < MARKER_TAP_GUARD_MS) {
            return;
        }
        // Tapping the map itself must dismiss any open marker callout, including
        // the user's own "my location" bubble; osmdroid leaves it open otherwise.
        InfoWindow.closeAllInfoWindowsOn(mapView);
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
        if (hasLocationPermission()) {
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
                .setCancelable(requestCode != REQUEST_VEHICLE_ALERT_LOCATION)
                .setPositiveButton(
                        R.string.permission_continue,
                        (ignored, which) -> requestPermissions(permissions, requestCode)
                )
                .setNegativeButton(
                        R.string.permission_not_now,
                        (ignored, which) -> handlePermissionExplanationDeclined(requestCode)
                )
                .create();
        dialog.setOnCancelListener(
                ignored -> handlePermissionExplanationDeclined(requestCode)
        );
        dialog.setOnDismissListener(ignored -> permissionDialogShowing = false);
        dialog.show();
    }

    private void handlePermissionExplanationDeclined(int requestCode) {
        if (requestCode == REQUEST_LOCATION) {
            tvStatus.setText(R.string.permission_location);
        } else if (requestCode == REQUEST_STORAGE) {
            pendingQrBitmap = null;
            pendingQrNumber = "";
            pendingOpenWeChatAfterQrSave = false;
        } else if (requestCode == REQUEST_VEHICLE_ALERT_LOCATION) {
            updateVehicleAlertStatus(R.string.vehicle_alert_paused_location);
        }
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
        DiagnosticLog.event("Net", "nearby request");

        repository.fetchNearby(
                center.getLatitude(),
                center.getLongitude(),
                new BikeRepository.Callback() {
                    @Override
                    public void onSuccess(List<Bike> result, long fetchedAt) {
                        DiagnosticLog.event("Net", "nearby ok n=" + result.size());
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        bikes.clear();
                        bikes.addAll(result);
                        lastNearbyRawCount = result.size();
                        filterUnavailableBikes();
                        mergeQueriedBike();
                        renderMap();
                        updateDataRefreshTime(fetchedAt);
                        finishLoadRequest();
                        updateViewportStatus();
                    }

                    @Override
                    public void onError(String message) {
                        DiagnosticLog.warn("Net", "nearby error: " + message);
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        tvStatus.setText(message);
                        finishLoadRequest();
                    }
                }
        );
    }

    /**
     * 设置里开启「自动过滤不可用车辆」时，把附近列表里的离线、停用和低电量
     * 车辆移出地图、列表和顶栏统计。按编号点名查到的车不在过滤范围内：
     * 用户主动查询的车辆仍然会显示，避免“查得到却看不到”。
     */
    private void filterUnavailableBikes() {
        if (!appPreferences.getBoolean(PREF_FILTER_UNAVAILABLE, true)) {
            return;
        }
        bikes.removeIf(bike -> !bike.available);
    }

    /**
     * 按编号点名查到的车不在附近列表里，把它并回列表，避免用户下一次移动地图
     * 触发重查后标记凭空消失。实时列表已经带回该车时改用实时数据，不再保留
     * 点名结果。
     */
    private void mergeQueriedBike() {
        if (queriedBike == null) {
            return;
        }
        for (Bike bike : bikes) {
            if (bike.number.equals(queriedBike.number)) {
                queriedBike = null;
                return;
            }
        }
        bikes.add(queriedBike);
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

    private void updateDataRefreshTime(long fetchedAt) {
        if (tvDataLabel == null) {
            return;
        }
        tvDataLabel.setText(getString(
                R.string.status_live_with_time,
                refreshTimeFormat.format(new Date(fetchedAt))
        ));
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
            // 过滤开启时附近可能有车但全部不可用，这时提示“无可用车辆”更准确。
            tvStatus.setText(lastNearbyRawCount > 0
                    ? R.string.status_no_available
                    : R.string.status_no_bikes);
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
        if (userLocationCallout == null) {
            userLocationCallout = new UserLocationCallout(mapView);
        }

        if (currentLocation != null) {
            if (userMarker == null) {
                userMarker = new Marker(mapView);
                userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                userMarker.setIcon(getDrawable(R.drawable.ic_user_location));
                userMarker.setTitle(getString(R.string.my_location));
            }
            userMarker.setInfoWindow(userLocationCallout);
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
                    selectedBike != null && selectedBike.number.equals(bike.number)
                            ? R.drawable.ic_bike_marker_selected
                            : R.drawable.ic_bike_marker
            ));
            marker.setTitle(bike.number);
            marker.setInfoWindow(bikeCallout);
            marker.setOnMarkerClickListener((clickedMarker, map) -> {
                lastMarkerTapAt = SystemClock.uptimeMillis();
                showBikeDetail(bike, false);
                return true;
            });
            bikeMarkers.put(bike.number, marker);
            mapView.getOverlays().add(marker);
        }

        bringSelectedMarkerToFront();
        updateMapLabels();
        updateMapCenterIndicator();
        mapView.invalidate();
    }

    /**
     * 把当前选中车辆的图标移到绘制列表末尾。osmdroid 按 overlay 列表顺序绘制，
     * 越靠后越在上层；按编号点名定位车辆后附近列表会再刷一次，此时选中的车辆
     * 在列表中的位置由接口返回顺序决定，不做处理就会被其它车辆图标盖住。
     */
    private void bringSelectedMarkerToFront() {
        if (selectedBike == null) {
            return;
        }
        Marker marker = bikeMarkers.get(selectedBike.number);
        if (marker != null && mapView.getOverlays().remove(marker)) {
            mapView.getOverlays().add(marker);
        }
    }

    /**
     * 把地图拉回当前定位并锁定到合适的缩放级别。
     *
     * <p>动画结束后还会再校验一次落点：动画被打断或最后一帧没有落到目标点上
     * 时，地图会停在半路，既没有回到自身定位中心，又会错误地弹出“拉远才有的”
     * 定位指示针。</p>
     */
    private void recenterToCurrentLocation() {
        recenterToCurrentLocation(RECENTER_ZOOM, true);
    }

    /**
     * @param targetZoom        居中后要达到的缩放级别。传入当前级别表示只居中、
     *                          不改变缩放（首次定位沿用原有缩放行为）。
     * @param startFollowWindow 只有用户主动定位/首次定位才重开跟随窗口，
     *                          跟随窗口自己触发的居中不再延长窗口。
     */
    private void recenterToCurrentLocation(double targetZoom, boolean startFollowWindow) {
        if (currentLocation == null) {
            pendingRecenterOnFix = true;
            return;
        }
        pendingRecenterOnFix = false;
        // 回到自身定位即结束“点名查车优先”，恢复默认的自身定位跟随。
        vehicleFocusActive = false;
        if (startFollowWindow) {
            recenterFollowUntilMs =
                    SystemClock.elapsedRealtime() + RECENTER_FOLLOW_WINDOW_MS;
            // 记录本次点击时刻：后台挂起后拿到的可能是很久以前的缓存点，
            // 直到收到比它更新的定位，跟随逻辑才算结束。
            recenterAwaitFixSinceElapsedMs = SystemClock.elapsedRealtime();
        }
        recenterZoom = targetZoom;
        GeoPoint target = currentLocationDisplayPoint();
        recenterInProgress = true;
        // 回到自身定位属于默认视角，取消点名定位期间的指示针抑制。
        suppressCenterIndicator = false;
        recenterSettlePending = true;
        recenterUserInterrupted = false;
        mapCenterIndicator.setVisibility(View.GONE);
        // 先停掉可能在跑的缩放/平移动画，避免它覆盖本次定位结果。
        mapView.getController().stopAnimation(false);
        mapView.getController().animateTo(
                target,
                targetZoom,
                RECENTER_ANIMATION_MS
        );
        loadBikes(target, true);
        mapView.postDelayed(this::settleRecenter, RECENTER_SETTLE_DELAY_MS);
    }

    private void settleRecenter() {
        if (!recenterSettlePending) {
            return;
        }
        recenterSettlePending = false;
        // 点名查车期间镜头属于目标车，不再按自身定位做落点校验，避免抢镜头。
        if (vehicleFocusActive) {
            recenterInProgress = false;
            updateMapCenterIndicator();
            return;
        }
        // 用户在动画期间自己拖动过地图时不要抢回镜头，只把状态收尾。
        if (!recenterUserInterrupted && currentLocation != null) {
            GeoPoint target = currentLocationDisplayPoint();
            if (distanceBetween(mapCenterGcj02(), target)
                    > RECENTER_SETTLE_TOLERANCE_METERS) {
                mapView.getController().stopAnimation(false);
                mapView.getController().setCenter(target);
                mapView.getController().setZoom(recenterZoom);
            }
        }
        recenterInProgress = false;
        updateMapCenterIndicator();
    }

    private void updateMapCenterIndicator() {
        if (mapCenterIndicator == null || currentLocation == null) {
            if (mapCenterIndicator != null) {
                mapCenterIndicator.setVisibility(View.GONE);
            }
            return;
        }
        if (recenterInProgress || suppressCenterIndicator) {
            mapCenterIndicator.setVisibility(View.GONE);
            return;
        }
        double distance = distanceBetween(
                mapCenterGcj02(),
                currentLocationDisplayPoint()
        );
        mapCenterIndicator.setVisibility(
                distance > CENTER_INDICATOR_DISTANCE_METERS
                        ? View.VISIBLE
                        : View.GONE
        );
    }

    private GeoPoint currentLocationDisplayPoint() {
        return CoordinateUtils.wgs84ToGcj02(
                currentLocation.getLatitude(),
                currentLocation.getLongitude()
        );
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
        showBikeDetail(bike, moveCamera, null);
    }

    /**
     * @param zoomLevel 非空时一并把缩放拉到该级别；为空表示只居中、保持当前缩放，
     *                  与点击地图标记时的原有行为一致。
     */
    private void showBikeDetail(Bike bike, boolean moveCamera, Double zoomLevel) {
        selectedBike = bike;
        updateMarkerSelection();
        bikePanel.setVisibility(View.VISIBLE);
        tvBikeNumber.setText(getString(R.string.number_format, bike.number));
        tvManualNotice.setVisibility(bike.locationKnown ? View.GONE : View.VISIBLE);

        if (!bike.locationKnown) {
            tvBikeMeta.setText(R.string.bike_meta_manual);
        } else {
            String detail = getString(bike.statusRes);
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

        if (!bike.locationKnown || bike.lastUploadAtMs <= 0L) {
            tvBikeFreshness.setVisibility(View.GONE);
        } else {
            long ageMs = Math.max(0L, System.currentTimeMillis() - bike.lastUploadAtMs);
            String uploadClock = ageMs < 24L * 60L * 60L * 1000L
                    ? refreshTimeFormat.format(new Date(bike.lastUploadAtMs))
                    : uploadTimeFormat.format(new Date(bike.lastUploadAtMs));
            tvBikeFreshness.setText(getString(
                    R.string.bike_last_upload,
                    uploadClock,
                    formatUploadAge(ageMs)
            ));
            tvBikeFreshness.setVisibility(View.VISIBLE);
        }

        Marker marker = bikeMarkers.get(bike.number);
        if (marker != null) {
            marker.showInfoWindow();
        }
        if (moveCamera && bike.locationKnown) {
            // 点名看车：镜头主动离开自身定位点，进入“车定位优先”态。取消已排期
            // 的居中落点校验并关闭 6 秒跟随窗口，否则自身定位回调（或 950ms 的
            // settleRecenter）会把镜头从目标车抢回自身定位点；同时抑制“偏离
            // 自身定位”的中心指示针，避免误导。回到自身定位时该态会被清除。
            vehicleFocusActive = true;
            recenterSettlePending = false;
            recenterInProgress = false;
            recenterFollowUntilMs = 0L;
            recenterAwaitFixSinceElapsedMs = 0L;
            suppressCenterIndicator = true;
            GeoPoint point = new GeoPoint(bike.latitude, bike.longitude);
            if (zoomLevel == null) {
                mapView.getController().animateTo(point);
            } else {
                mapView.getController().animateTo(
                        point,
                        zoomLevel,
                        RECENTER_ANIMATION_MS
                );
            }
            // 动画结束后按最终视口刷新顶栏，清除上一次查询留下的错误文案。
            mapView.postDelayed(this::updateViewportStatus, RECENTER_SETTLE_DELAY_MS);
        }
    }

    private void updateMarkerSelection() {
        for (Map.Entry<String, Marker> entry : bikeMarkers.entrySet()) {
            boolean selected = selectedBike != null
                    && selectedBike.number.equals(entry.getKey());
            entry.getValue().setIcon(getDrawable(
                    selected
                            ? R.drawable.ic_bike_marker_selected
                            : R.drawable.ic_bike_marker
            ));
        }
        bringSelectedMarkerToFront();
        mapView.invalidate();
    }

    private void showBikeList() {
        if (bikes.isEmpty()) {
            Toast.makeText(
                    this,
                    lastNearbyRawCount > 0
                            ? R.string.status_no_available
                            : R.string.status_no_bikes,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_sheet);
        content.setPadding(dp(18), dp(14), dp(18), dp(18) + systemBarsBottom);
        // 关闭按钮使用无边界水波纹，需放宽裁剪才能显示完整圆形。
        content.setClipToPadding(false);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setClipChildren(false); // 同上：表头高度等于按钮，否则水波纹会被上下裁平

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
        TypedValue closeBackground = new TypedValue();
        if (getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                closeBackground,
                true
        )) {
            close.setBackground(getDrawable(closeBackground.resourceId));
        }
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
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        );
        listParams.topMargin = dp(10);
        content.addView(scrollView, listParams);

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
                        + " · " + getString(bike.statusRes),
                11,
                R.color.text_tertiary,
                false
        ));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView action = textView(getString(R.string.action_view), 13, R.color.control_accent_text, true);
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
        showNumberInput("");
    }

    private void showNumberInput(String initialValue) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_number_input, null);
        EditText input = content.findViewById(R.id.inputTail);
        View previewPanel = content.findViewById(R.id.inputPreviewPanel);
        TextView previewNumber = content.findViewById(R.id.tvInputPreviewNumber);
        TextView previewSite = content.findViewById(R.id.tvInputPreviewSite);
        TextView error = content.findViewById(R.id.tvInputError);
        TextView numberSite = content.findViewById(R.id.tvNumberSite);
        TextView numberSeries = content.findViewById(R.id.tvNumberSeries);
        View numberRangeRow = content.findViewById(R.id.numberRangeRow);
        LinearLayout historySection = content.findViewById(R.id.historySection);
        LinearLayout historyContainer = content.findViewById(R.id.historyContainer);
        TextView clearHistory = content.findViewById(R.id.tvClearHistory);
        input.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(VehicleNumberCatalog.NUMBER_LENGTH),
                (source, start, end, destination, destinationStart, destinationEnd) -> {
                    for (int index = start; index < end; index++) {
                        if (!Character.isDigit(source.charAt(index))) {
                            return "";
                        }
                    }
                    return null;
                }
        });
        if (initialValue != null && !initialValue.isEmpty()) {
            input.setText(initialValue);
            input.setSelection(input.length());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton(R.string.confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        int initialSiteId = preferredNumberSiteId();
        String initialSeries = preferredNumberSeries(initialSiteId);
        int[] selectedSiteId = {initialSiteId};
        String[] selectedSeries = {initialSeries};
        String[] resolvedNumber = {null};
        List<List<VehicleNumberCatalog.NumberInfo>> candidateMatches = new ArrayList<>();
        Runnable[] updateResolutionRef = new Runnable[1];

        Runnable updateResolution = () -> {
            String raw = input.getText().toString().trim();
            if (vehicleNumberCatalogLoadFinished
                    && vehicleNumberCatalog.site(selectedSiteId[0]) == null) {
                selectedSiteId[0] = preferredNumberSiteId();
                selectedSeries[0] = preferredNumberSeries(selectedSiteId[0]);
            }
            VehicleNumberCatalog.Site selectedSite = vehicleNumberCatalog.site(selectedSiteId[0]);
            if (selectedSite != null) {
                if (!selectedSite.series.contains(selectedSeries[0])) {
                    selectedSeries[0] = preferredNumberSeries(selectedSiteId[0]);
                }
                numberSite.setText(selectedSite.name);
                numberSeries.setText(getString(
                        R.string.number_series_title_format,
                        selectedSeries[0]
                ));
            }

            resolvedNumber[0] = null;
            candidateMatches.clear();
            error.setVisibility(View.GONE);
            previewPanel.setVisibility(View.GONE);
            previewPanel.setClickable(false);
            previewPanel.setOnClickListener(null);
            previewSite.setVisibility(View.GONE);
            if (raw.isEmpty()) {
                setNumberConfirmEnabled(dialog, false);
                return;
            }
            if (!vehicleNumberCatalogLoadFinished
                    && raw.length() != VehicleNumberCatalog.NUMBER_LENGTH) {
                numberSite.setText(R.string.number_catalog_loading);
                numberSeries.setText("");
                error.setText(R.string.number_catalog_loading);
                error.setVisibility(View.VISIBLE);
                setNumberConfirmEnabled(dialog, false);
                return;
            }

            if (raw.length() == VehicleNumberCatalog.NUMBER_LENGTH) {
                String normalized = VehicleNumberCatalog.normalize(raw);
                VehicleNumberCatalog.NumberInfo info = vehicleNumberCatalog.find(normalized);
                if (info == null) {
                    numberSite.setText(R.string.input_number_direct_title);
                    numberSeries.setText(R.string.input_number_direct_desc);
                    resolvedNumber[0] = normalized;
                    previewNumber.setText(getString(
                            R.string.input_preview_number,
                            normalized
                    ));
                    previewSite.setVisibility(View.GONE);
                    previewPanel.setVisibility(View.VISIBLE);
                    setNumberConfirmEnabled(dialog, true);
                    return;
                }
                selectedSiteId[0] = info.siteId;
                selectedSeries[0] = info.series;
                resolvedNumber[0] = info.number;
                numberSite.setText(info.siteName);
                numberSeries.setText(getString(
                        R.string.number_series_title_format,
                        info.series
                ));
                previewNumber.setText(getString(
                        R.string.input_preview_number,
                        info.number
                ));
                previewSite.setVisibility(View.GONE);
                previewPanel.setVisibility(View.VISIBLE);
                setNumberConfirmEnabled(dialog, true);
                return;
            }

            if (raw.length() == VehicleNumberCatalog.TAIL_LENGTH || raw.length() == 4) {
                List<VehicleNumberCatalog.NumberInfo> seriesMatches =
                        vehicleNumberCatalog.matchesSuffix(
                                selectedSiteId[0],
                                selectedSeries[0],
                                raw
                        );
                List<VehicleNumberCatalog.NumberInfo> siteMatches =
                        vehicleNumberCatalog.matchesSuffix(
                                selectedSiteId[0],
                                null,
                                raw
                        );
                List<VehicleNumberCatalog.NumberInfo> matches = seriesMatches.isEmpty()
                        ? siteMatches
                        : seriesMatches;
                if (matches.size() == 1) {
                    VehicleNumberCatalog.NumberInfo info = matches.get(0);
                    selectedSiteId[0] = info.siteId;
                    selectedSeries[0] = info.series;
                    resolvedNumber[0] = info.number;
                    numberSite.setText(info.siteName);
                    numberSeries.setText(getString(
                            R.string.number_series_title_format,
                            info.series
                    ));
                    previewNumber.setText(getString(
                            R.string.input_preview_number,
                            info.number
                    ));
                    previewSite.setVisibility(View.GONE);
                    previewPanel.setVisibility(View.VISIBLE);
                    setNumberConfirmEnabled(dialog, true);
                    return;
                }
                if (matches.size() > 1) {
                    candidateMatches.add(matches);
                    previewNumber.setText(getString(
                            R.string.input_number_candidates,
                            matches.size()
                    ));
                    previewSite.setText(R.string.input_number_candidates_hint);
                    previewSite.setVisibility(View.VISIBLE);
                    previewPanel.setClickable(true);
                    previewPanel.setFocusable(true);
                    previewPanel.setOnClickListener(view -> showNumberCandidates(
                            candidateMatches.get(0),
                            selectedSiteId,
                            selectedSeries,
                            updateResolutionRef[0]
                    ));
                    previewPanel.setVisibility(View.VISIBLE);
                    setNumberConfirmEnabled(dialog, false);
                    return;
                }

                error.setText(R.string.input_number_not_found);
                error.setVisibility(View.VISIBLE);
                setNumberConfirmEnabled(dialog, false);
                return;
            }

            error.setText(R.string.input_number_incomplete);
            error.setVisibility(View.VISIBLE);
            setNumberConfirmEnabled(dialog, false);
        };
        updateResolutionRef[0] = updateResolution;
        numberCatalogRefresh = updateResolution;

        numberRangeRow.setOnClickListener(view -> showNumberRangePicker(
                selectedSiteId,
                selectedSeries,
                updateResolution
        ));

        historyContainer.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        historyContainer.setDividerDrawable(new ColorDrawable(getColor(R.color.divider)));
        historyContainer.setDividerPadding(dp(6));

        List<QrHistoryEntry> history = loadQrHistory();
        if (!history.isEmpty()) {
            historySection.setVisibility(View.VISIBLE);
            clearHistory.setOnClickListener(view -> {
                clearQrHistory();
                historyContainer.removeAllViews();
                historySection.setVisibility(View.GONE);
                Toast.makeText(
                        this,
                        R.string.history_cleared,
                        Toast.LENGTH_SHORT
                ).show();
            });
            for (QrHistoryEntry entry : history) {
                View row = createHistoryRow(entry);
                row.setOnClickListener(view -> {
                    dialog.dismiss();
                    openQrHistoryEntry(entry);
                });
                historyContainer.addView(row);
            }
        }

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
                // No-op.
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateResolution.run();
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
                updateResolution.run();
                String number = resolvedNumber[0];
                if (number == null) {
                    return;
                }
                appPreferences.edit()
                        .putInt(PREF_NUMBER_SITE_ID, selectedSiteId[0])
                        .putString(PREF_NUMBER_SERIES, selectedSeries[0])
                        .apply();
                Bike bike = findOrCreateBike(number);
                dialog.dismiss();
                showBikeDetail(bike, true);
                if (!bike.locationKnown) {
                    queryBikeByNumber(number);
                }
            });
            updateResolution.run();
            input.requestFocus();
        });
        dialog.setOnDismissListener(ignored -> {
            if (numberCatalogRefresh == updateResolution) {
                numberCatalogRefresh = null;
            }
        });
        dialog.show();
    }

    private void showNumberCandidates(
            List<VehicleNumberCatalog.NumberInfo> candidates,
            int[] selectedSiteId,
            String[] selectedSeries,
            Runnable onChanged
    ) {
        String[] labels = new String[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            VehicleNumberCatalog.NumberInfo candidate = candidates.get(index);
            labels[index] = candidate.number + " · " + candidate.siteName;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.number_candidates_title)
                .setItems(labels, (dialog, which) -> {
                    VehicleNumberCatalog.NumberInfo info = candidates.get(which);
                    selectedSiteId[0] = info.siteId;
                    selectedSeries[0] = info.series;
                    onChanged.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showNumberRangePicker(
            int[] selectedSiteId,
            String[] selectedSeries,
            Runnable onChanged
    ) {
        List<VehicleNumberCatalog.Site> sites = vehicleNumberCatalog.sites();
        String[] titles = new String[sites.size()];
        String[] subtitles = new String[sites.size()];
        for (int index = 0; index < sites.size(); index++) {
            VehicleNumberCatalog.Site site = sites.get(index);
            titles[index] = site.name;
            subtitles[index] = getString(
                    R.string.number_site_series_count,
                    site.series.size()
            );
        }
        showNumberOptions(
                R.string.number_range_site_title,
                titles,
                subtitles,
                which -> {
                    VehicleNumberCatalog.Site site = sites.get(which);
                    selectedSiteId[0] = site.id;
                    if (!site.series.contains(selectedSeries[0])) {
                        selectedSeries[0] = site.series.get(0);
                    }
                    if (site.series.size() == 1) {
                        selectedSeries[0] = site.series.get(0);
                        onChanged.run();
                        return;
                    }
                    onChanged.run();
                    showNumberSeriesPicker(site, selectedSeries, onChanged);
                }
        );
    }

    private void showNumberSeriesPicker(
            VehicleNumberCatalog.Site site,
            String[] selectedSeries,
            Runnable onChanged
    ) {
        String[] titles = new String[site.series.size()];
        String[] subtitles = new String[site.series.size()];
        for (int index = 0; index < site.series.size(); index++) {
            String series = site.series.get(index);
            titles[index] = getString(
                    R.string.number_series_title_format,
                    series
            );
            subtitles[index] = getString(
                    R.string.number_series_subtitle_format,
                    series
            );
        }
        showNumberOptions(
                R.string.number_range_series_title,
                titles,
                subtitles,
                which -> {
                    selectedSeries[0] = site.series.get(which);
                    onChanged.run();
                }
        );
    }

    private interface NumberOptionListener {
        void onSelected(int index);
    }

    private void showNumberOptions(
            int titleRes,
            String[] titles,
            String[] subtitles,
            NumberOptionListener listener
    ) {
        View content = LayoutInflater.from(this).inflate(
                R.layout.dialog_number_options,
                null
        );
        LinearLayout container = content.findViewById(R.id.numberOptionsContainer);
        LayoutInflater inflater = LayoutInflater.from(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .create();
        for (int index = 0; index < titles.length; index++) {
            View row = inflater.inflate(R.layout.item_number_option, container, false);
            TextView title = row.findViewById(R.id.tvNumberOptionTitle);
            TextView subtitle = row.findViewById(R.id.tvNumberOptionSubtitle);
            title.setText(titles[index]);
            if (subtitles[index] == null || subtitles[index].isEmpty()) {
                subtitle.setVisibility(View.GONE);
            } else {
                subtitle.setText(subtitles[index]);
                subtitle.setVisibility(View.VISIBLE);
            }
            int selectedIndex = index;
            row.setOnClickListener(view -> {
                dialog.dismiss();
                listener.onSelected(selectedIndex);
            });
            container.addView(row);
        }
        dialog.show();
    }

    private int preferredNumberSiteId() {
        if (selectedBike != null) {
            VehicleNumberCatalog.NumberInfo info = vehicleNumberCatalog.find(selectedBike.number);
            if (info != null) {
                return info.siteId;
            }
        }
        int stored = appPreferences.getInt(PREF_NUMBER_SITE_ID, -1);
        if (vehicleNumberCatalog.site(stored) != null) {
            return stored;
        }
        List<VehicleNumberCatalog.Site> sites = vehicleNumberCatalog.sites();
        // 目录不可用时没有任何可选站点，返回 -1 交由调用方走空目录降级路径，避免越界。
        return sites.isEmpty() ? -1 : sites.get(0).id;
    }

    private String preferredNumberSeries(int siteId) {
        VehicleNumberCatalog.Site site = vehicleNumberCatalog.site(siteId);
        if (site == null || site.series.isEmpty()) {
            return "";
        }
        String stored = appPreferences.getString(PREF_NUMBER_SERIES, "");
        if (stored != null && site.series.contains(stored)) {
            return stored;
        }
        return site.series.get(0);
    }

    private void setNumberConfirmEnabled(AlertDialog dialog, boolean enabled) {
        if (dialog.isShowing()) {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (confirm != null) {
                confirm.setEnabled(enabled);
            }
        }
    }

    private View createHistoryRow(QrHistoryEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(5), dp(10), dp(5));
        row.setBackgroundResource(R.drawable.bg_list_row);
        row.setMinimumHeight(dp(38));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);

        TextView numberView = textView(
                entry.displayNumber(),
                15,
                R.color.text_primary,
                true
        );
        numberView.setSingleLine(true);
        labels.addView(numberView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        String siteName = entry.siteName;
        if (siteName.isEmpty()) {
            VehicleNumberCatalog.NumberInfo info = vehicleNumberCatalog.find(entry.number);
            siteName = info == null || info.siteName == null ? "" : info.siteName;
        }
        siteName = BikeRepository.correctSiteName(siteName);
        if (!siteName.isEmpty()) {
            TextView siteView = textView(
                    siteName,
                    12,
                    R.color.text_secondary,
                    false
            );
            siteView.setMaxLines(2);
            labels.addView(siteView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
        row.addView(labels, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        ));

        TextView action = textView(
                getString(entry.number.isEmpty()
                        ? R.string.history_action_complete
                        : R.string.history_action),
                12,
                R.color.control_accent_text,
                true
        );
        action.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        action.setSingleLine(true);
        action.setMinWidth(dp(76));
        action.setPadding(dp(8), 0, 0, 0);
        row.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return row;
    }

    private List<QrHistoryEntry> loadQrHistory() {
        if (!appPreferences
                .getBoolean(PREF_KEEP_QR_HISTORY, true)) {
            return new ArrayList<>();
        }
        String raw = appPreferences
                .getString(PREF_QR_HISTORY, "");
        List<QrHistoryEntry> history = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return history;
        }
        for (String value : raw.split(",")) {
            String stored = value.trim();
            String storedSite = "";
            int separator = stored.indexOf('|');
            if (separator >= 0) {
                storedSite = stored.substring(separator + 1).trim();
                stored = stored.substring(0, separator).trim();
            }
            if (stored.isEmpty()) {
                continue;
            }
            String number = "";
            String tail = "";
            VehicleNumberCatalog.NumberInfo info = null;
            if (stored.matches("\\d{1,4}")) {
                tail = stored;
                if (tail.length() >= VehicleNumberCatalog.TAIL_LENGTH) {
                    info = resolveUniqueHistoryTail(storedSite, tail);
                }
                if (info != null) {
                    number = info.number;
                    storedSite = info.siteName == null ? storedSite : info.siteName;
                }
            } else {
                number = VehicleNumberCatalog.normalize(stored);
                if (!number.matches("\\d{9}")) {
                    continue;
                }
                info = vehicleNumberCatalog.find(number);
            }
            if (storedSite.isEmpty()
                    && info != null
                    && info.siteName != null) {
                storedSite = info.siteName;
            }
            storedSite = BikeRepository.correctSiteName(storedSite);
            QrHistoryEntry entry = new QrHistoryEntry(number, tail, storedSite);
            if (containsHistoryEntry(history, entry)) {
                continue;
            }
            history.add(entry);
            if (history.size() >= QR_HISTORY_LIMIT) {
                break;
            }
        }
        saveQrHistory(history);
        return history;
    }

    private void rememberQrHistory(String number, String siteName) {
        if (!appPreferences
                .getBoolean(PREF_KEEP_QR_HISTORY, true)) {
            clearQrHistory();
            return;
        }
        String normalized = VehicleNumberCatalog.normalize(number);
        String resolvedSite = siteName == null ? "" : siteName.trim();
        if (resolvedSite.isEmpty()) {
            VehicleNumberCatalog.NumberInfo info = vehicleNumberCatalog.find(normalized);
            resolvedSite = info == null || info.siteName == null ? "" : info.siteName;
        }
        resolvedSite = BikeRepository.correctSiteName(resolvedSite);
        List<QrHistoryEntry> history = loadQrHistory();
        for (int index = history.size() - 1; index >= 0; index--) {
            if (history.get(index).number.equals(normalized)) {
                history.remove(index);
            }
        }
        history.add(0, new QrHistoryEntry(normalized, "", resolvedSite));
        while (history.size() > QR_HISTORY_LIMIT) {
            history.remove(history.size() - 1);
        }
        saveQrHistory(history);
    }

    private void saveQrHistory(List<QrHistoryEntry> history) {
        if (!appPreferences
                .getBoolean(PREF_KEEP_QR_HISTORY, true)) {
            clearQrHistory();
            return;
        }
        StringBuilder raw = new StringBuilder();
        for (int index = 0; index < history.size(); index++) {
            if (index > 0) {
                raw.append(',');
            }
            QrHistoryEntry entry = history.get(index);
            raw.append(entry.persistenceValue());
            if (!entry.siteName.isEmpty()) {
                raw.append('|').append(entry.siteName);
            }
        }
        appPreferences
                .edit()
                .putString(PREF_QR_HISTORY, raw.toString())
                .apply();
    }

    private static boolean containsHistoryEntry(
            List<QrHistoryEntry> history,
            QrHistoryEntry candidate
    ) {
        for (QrHistoryEntry entry : history) {
            if (entry.persistenceValue().equals(candidate.persistenceValue())) {
                return true;
            }
        }
        return false;
    }

    private VehicleNumberCatalog.NumberInfo resolveUniqueHistoryTail(
            String siteName,
            String tail
    ) {
        VehicleNumberCatalog.NumberInfo unique = null;
        String expectedSite = BikeRepository.correctSiteName(siteName);
        for (VehicleNumberCatalog.Site site : vehicleNumberCatalog.sites()) {
            for (VehicleNumberCatalog.NumberInfo candidate
                    : vehicleNumberCatalog.matchesSuffix(site.id, null, tail)) {
                if (!expectedSite.isEmpty()
                        && !expectedSite.equals(BikeRepository.correctSiteName(
                        candidate.siteName
                ))) {
                    continue;
                }
                if (unique != null && !unique.number.equals(candidate.number)) {
                    return null;
                }
                unique = candidate;
            }
        }
        return unique;
    }

    private void clearQrHistory() {
        appPreferences
                .edit()
                .remove(PREF_QR_HISTORY)
                .apply();
        // “清除痕迹”同时清掉本地自学习的编号→校园提示，避免残留用户痕迹。
        LearnedVehicleNumberStore.get(this).clear();
    }

    private Bike findOrCreateBike(String number) {
        String normalized = VehicleNumberCatalog.normalize(number);
        for (Bike bike : bikes) {
            if (bike.number.equals(normalized)) {
                return bike;
            }
        }
        VehicleNumberCatalog.NumberInfo info = vehicleNumberCatalog.find(normalized);
        return Bike.manual(normalized, info == null ? "" : info.siteName);
    }

    /**
     * 当前实时车辆列表里没有这个编号时，按车号向车辆服务点名查询一次
     * （queryOneCar，与官方扫码后 loadOneCar 同款）：查到就把坐标补进地图并定位
     * 过去，查不到保留原来的“仅生成二维码”提示。不轮询，一次查不到就到此为止。
     */
    private void queryBikeByNumber(String number) {
        String normalized = VehicleNumberCatalog.normalize(number);
        if (queryingBikeNumber != null) {
            Toast.makeText(
                    this,
                    R.string.input_bike_lookup_in_progress,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        if (!normalized.matches("\\d{9}")) {
            return;
        }
        GeoPoint center = currentLocation == null
                ? mapCenterGcj02()
                : currentLocationDisplayPoint();
        if (center == null) {
            return;
        }
        queryingBikeNumber = normalized;
        // 编号属于用户数据，只记后三位用于对时间线。
        String maskedNumber = "***" + normalized.substring(6);
        DiagnosticLog.event("Query", "lookup " + maskedNumber);
        tvStatus.setText(R.string.status_querying_bike);
        repository.queryOneCar(
                normalized,
                center.getLatitude(),
                center.getLongitude(),
                new BikeRepository.BikeCallback() {
                    @Override
                    public void onSuccess(Bike bike) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        queryingBikeNumber = null;
                        if (bike == null) {
                            DiagnosticLog.event("Query", "miss " + maskedNumber);
                            Toast.makeText(
                                    MainActivity.this,
                                    R.string.input_bike_lookup_failed,
                                    Toast.LENGTH_SHORT
                            ).show();
                            updateViewportStatus();
                            return;
                        }
                        DiagnosticLog.event("Query", "hit " + maskedNumber);
                        queriedBike = bike;
                        mergeQueriedBike();
                        // 本地自学习：只有“按编号点名查到车”这一条路径才写入，
                        // 记录编号对应的校园名，供以后补全显示；内置目录优先，
                        // 学习项只补未知编号。与“保留最近车辆编号”联动，关闭时不记录。
                        if (appPreferences.getBoolean(PREF_KEEP_QR_HISTORY, true)) {
                            String learnedSite = historySiteNameFor(bike);
                            if (!learnedSite.isEmpty()) {
                                LearnedVehicleNumberStore.get(MainActivity.this)
                                        .remember(bike.number, learnedSite);
                            }
                        }
                        // 点名定位单车：进入“车定位优先”态（取消居中落点校验、
                        // 关闭 6 秒跟随窗口、抑制中心指示针）的逻辑统一在
                        // showBikeDetail(moveCamera=true) 内处理，按编号查车与
                        // 最近生成列表“查看”等路径共用同一套规则。
                        renderMap();
                        showBikeDetail(bike, true, BIKE_FOCUS_ZOOM);
                        updateViewportStatus();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        queryingBikeNumber = null;
                        DiagnosticLog.warn("Query", "error " + maskedNumber + ": " + message);
                        tvStatus.setText(message);
                    }
                }
        );
    }

    /**
     * Campus name shown under a stored vehicle number. Prefer the name the
     * vehicle service reported for that bike, then fall back to the bundled
     * catalog so the history row always has a name when one is known.
     */
    private String historySiteNameFor(Bike bike) {
        if (bike == null) {
            return "";
        }
        String campus = bike.serviceSiteName == null ? "" : bike.serviceSiteName.trim();
        if (!campus.isEmpty()) {
            return BikeRepository.correctSiteName(campus);
        }
        String point = bike.siteName == null ? "" : bike.siteName.trim();
        if (!point.isEmpty()) {
            return BikeRepository.correctSiteName(point);
        }
        VehicleNumberCatalog.NumberInfo info = vehicleNumberCatalog.find(bike.number);
        return info == null || info.siteName == null
                ? ""
                : BikeRepository.correctSiteName(info.siteName);
    }

    /** One "recently generated" entry: the vehicle number plus its site name. */
    private static final class QrHistoryEntry {
        final String number;
        final String tail;
        final String siteName;

        QrHistoryEntry(String number, String siteName) {
            this(number, "", siteName);
        }

        QrHistoryEntry(String number, String tail, String siteName) {
            this.number = number;
            this.tail = tail;
            this.siteName = siteName == null ? "" : siteName;
        }

        String displayNumber() {
            return number.isEmpty() ? tail : number;
        }

        String persistenceValue() {
            return number.isEmpty() ? tail : number;
        }
    }

    private void showQrDialog(Bike bike) {
        Bitmap qrBitmap = createQrBitmap(bike);
        if (qrBitmap == null) {
            return;
        }
        rememberQrHistory(bike.number, historySiteNameFor(bike));

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_qr, null);
        ImageView imageView = content.findViewById(R.id.qrImage);
        TextView urlView = content.findViewById(R.id.tvQrUrl);
        Button copyButton = content.findViewById(R.id.btnCopyQr);
        Button wechatButton = content.findViewById(R.id.btnQrWechat);
        View qrActions = content.findViewById(R.id.qrActions);
        SharedPreferences preferences = appPreferences;
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
                .setTitle(getString(R.string.qr_dialog_title, bike.number))
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

    private Bitmap createQrBitmap(Bike bike) {
        int size = 720;
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    bike.qrUrl(),
                    BarcodeFormat.QR_CODE,
                    size,
                    size
            );
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                int row = y * size;
                for (int x = 0; x < size; x++) {
                    pixels[row + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        } catch (WriterException exception) {
            Toast.makeText(this, R.string.qr_generate_failed, Toast.LENGTH_LONG)
                    .show();
            return null;
        }
    }

    private void openQrHistoryEntry(QrHistoryEntry entry) {
        if (entry.number.matches("\\d{9}")) {
            openQrInWeChat(entry.number);
        } else if (entry.tail.length() < VehicleNumberCatalog.TAIL_LENGTH) {
            Toast.makeText(
                    this,
                    R.string.history_tail_incomplete_hint,
                    Toast.LENGTH_LONG
            ).show();
            showNumberInput("");
        } else if (!entry.tail.isEmpty()) {
            showNumberInput(entry.tail);
        }
    }

    private void openQrInWeChat(String number) {
        Bike bike = findOrCreateBike(number);
        Bitmap qrBitmap = createQrBitmap(bike);
        if (qrBitmap == null) {
            return;
        }
        rememberQrHistory(number, historySiteNameFor(bike));
        saveQrAndOpenWeChat(qrBitmap, number);
    }

    private void saveQrAndOpenWeChat(Bitmap bitmap, String number) {
        pendingOpenWeChatAfterQrSave = true;
        saveQrToGallery(bitmap, number);
    }

    private void saveQrToGallery(Bitmap bitmap, String number) {
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

    private boolean performQrSave(Bitmap bitmap, String number) {
        boolean openWeChatAfterSave = pendingOpenWeChatAfterQrSave;
        QrGalleryStore.SavedQr pending = qrGalleryStore.save(bitmap, number);
        if (pending == null) {
            Toast.makeText(this, R.string.qr_save_failed, Toast.LENGTH_LONG).show();
            return false;
        }
        scheduleQrDeletion(pending);
        if (!openWeChatAfterSave) {
            Toast.makeText(this, R.string.qr_saved, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private void openWeChatAfterQrSaveIfNeeded(boolean saved) {
        boolean shouldOpenWeChat = pendingOpenWeChatAfterQrSave;
        pendingOpenWeChatAfterQrSave = false;
        if (saved && shouldOpenWeChat) {
            openWeChatScanner();
        }
    }

    private void scheduleQrDeletion(QrGalleryStore.SavedQr pending) {
        qrGalleryStore.remember(pending);
        uiHandler.postDelayed(
                () -> deletePendingQr(pending, true),
                QrGalleryStore.AUTO_DELETE_MS
        );
    }

    private void cleanupExpiredQrSaves() {
        long now = System.currentTimeMillis();
        for (QrGalleryStore.SavedQr pending : qrGalleryStore.loadPending()) {
            long delay = Math.max(1_000L, pending.expiresAt - now);
            uiHandler.postDelayed(
                    () -> deletePendingQr(pending, false),
                    delay
            );
        }
    }

    private void deletePendingQr(
            QrGalleryStore.SavedQr pending,
            boolean showFailure
    ) {
        if (!qrGalleryStore.delete(pending) && showFailure) {
            Toast.makeText(this, R.string.qr_delete_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.qr_clipboard_label), text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    /**
     * Matches the two WeChat scan entry points used by the reference
     * shortcut flow, then falls back to opening WeChat's home screen.
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
            return getString(R.string.distance_meters_format, Math.round(distanceMeters));
        }
        return getString(R.string.distance_kilometers_format, distanceMeters / 1000f);
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

    private String formatUploadAge(long ageMs) {
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void startLocationUpdates() {
        DiagnosticLog.event("Loc", "start updates");
        // Re-registering is idempotent: drop any previous registration first so
        // repeated calls cannot stack duplicate listeners.
        stopLocationUpdates();

        boolean hasFineLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean hasCoarseLocation = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!hasFineLocation && !hasCoarseLocation) {
            tvStatus.setText(R.string.permission_location);
            return;
        }

        Location bestLastKnown = null;
        String[] providers = hasFineLocation
                ? new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
        }
                : new String[]{LocationManager.NETWORK_PROVIDER};
        for (String provider : providers) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null
                        && (bestLastKnown == null
                        || candidate.getAccuracy() < bestLastKnown.getAccuracy())) {
                    bestLastKnown = candidate;
                }
            } catch (IllegalArgumentException | SecurityException exception) {
                DiagnosticLog.detail("Loc", "provider unavailable " + provider);
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

        boolean registered = false;
        if (hasFineLocation) {
            registered |= requestLocationUpdatesSafe(
                    LocationManager.GPS_PROVIDER,
                    4000L,
                    8f
            );
        }
        registered |= requestLocationUpdatesSafe(
                LocationManager.NETWORK_PROVIDER,
                8000L,
                20f
        );
        if (!registered) {
            tvStatus.setText(R.string.permission_location);
        }
    }

    private boolean requestLocationUpdatesSafe(
            String provider,
            long minTimeMs,
            float minDistanceMeters
    ) {
        try {
            locationManager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceMeters,
                    this,
                    Looper.getMainLooper()
            );
            return true;
        } catch (IllegalArgumentException | SecurityException exception) {
            DiagnosticLog.detail("Loc", "request unavailable " + provider);
            return false;
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
        DiagnosticLog.detail("Loc", "stop updates");
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
            // The permission may already be gone; the registration is dropped
            // either way.
        }
    }

    /**
     * 判断刚到达的定位是否比最近一次“重新定位”点击更新。
     *
     * <p>后台挂起较久再回到前台时，系统给到的往往是挂起前缓存的旧点，而真正的新
     * 定位要等 GPS 重新捕获，可能超过固定的跟随窗口。这里用定位自带的
     * elapsedRealtimeNanos 比较新旧：只有比这次点击更新的定位才算落地，否则一直
     * 保留等待状态（最长 {@link #RECENTER_FRESH_FIX_TIMEOUT_MS}）。</p>
     *
     * @return true 表示本次回调就是等待中的新定位
     */
    private boolean resolveRecenterFreshFix(Location location) {
        if (recenterAwaitFixSinceElapsedMs == 0L) {
            return false;
        }
        long fixElapsedMs = location.getElapsedRealtimeNanos() / 1_000_000L;
        boolean updated = fixElapsedMs > 0L
                ? fixElapsedMs >= recenterAwaitFixSinceElapsedMs
                // 个别机型不填 elapsedRealtimeNanos：退化为按到达时间判断，
                // 至少等到请求后 2 秒，避免注册监听时立刻回吐的缓存点被当成新定位。
                : SystemClock.elapsedRealtime() - recenterAwaitFixSinceElapsedMs >= 2000L;
        if (updated) {
            recenterAwaitFixSinceElapsedMs = 0L;
            return true;
        }
        if (SystemClock.elapsedRealtime() - recenterAwaitFixSinceElapsedMs
                > RECENTER_FRESH_FIX_TIMEOUT_MS) {
            // 长时间等不到新定位就放弃等待，避免一直跟随。
            recenterAwaitFixSinceElapsedMs = 0L;
        }
        return false;
    }

    @Override
    public void onLocationChanged(Location location) {
        // 每次回前台 startLocationUpdates() 都会先把系统缓存的旧定位喂进来。
        // 若它比已经显示的点还旧，直接忽略，避免蓝点往回跳。
        if (currentLocation != null
                && location.getElapsedRealtimeNanos() > 0L
                && currentLocation.getElapsedRealtimeNanos() > 0L
                && location.getElapsedRealtimeNanos()
                        < currentLocation.getElapsedRealtimeNanos()) {
            DiagnosticLog.event("Loc", "stale fix ignored");
            return;
        }
        currentLocation = location;
        DiagnosticLog.detail("Loc", "fix acc=" + Math.round(location.getAccuracy()));
        GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
        GeoPoint displayPoint = CoordinateUtils.wgs84ToGcj02(
                point.getLatitude(),
                point.getLongitude()
        );

        if (userMarker == null) {
            userMarker = new Marker(mapView);
            userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            userMarker.setIcon(getDrawable(R.drawable.ic_user_location));
            userMarker.setTitle(getString(R.string.my_location));
        }
        if (userLocationCallout == null) {
            userLocationCallout = new UserLocationCallout(mapView);
        }
        userMarker.setInfoWindow(userLocationCallout);
        // Never leave the position marker detached from the overlay list: a
        // rebuild without a known location used to drop the dot for good.
        if (!mapView.getOverlays().contains(userMarker)) {
            mapView.getOverlays().add(userMarker);
        }
        userMarker.setPosition(displayPoint);
        updateMapCenterIndicator();
        mapView.invalidate();

        // 用户点过“重新定位”后，只有比这次点击更新的定位才算真正落地。
        boolean freshFixForRecenter = resolveRecenterFreshFix(location);

        if (!initialLocationCentered || pendingRecenterOnFix) {
            boolean firstFix = !initialLocationCentered && !pendingRecenterOnFix;
            initialLocationCentered = true;
            DiagnosticLog.event("Loc", firstFix ? "recenter first" : "recenter pending");
            if (firstFix) {
                // 首次定位沿用原有缩放级别，只把地图移到定位点；
                // 但仍复用落点校验，避免动画没跑完就误显示定位指示针。
                recenterToCurrentLocation(mapView.getZoomLevelDouble(), true);
            } else {
                // 用户在首个定位到达前就按了“重新定位”：等定位一到就按定位
                // 按钮的逻辑居中，回到自身定位点并恢复常用缩放级别。
                recenterToCurrentLocation();
            }
        } else if (!vehicleFocusActive
                && !recenterUserInterrupted
                && (freshFixForRecenter
                        || recenterAwaitFixSinceElapsedMs != 0L
                        || SystemClock.elapsedRealtime() < recenterFollowUntilMs)
                && distanceBetween(mapCenterGcj02(), displayPoint)
                        > CENTER_INDICATOR_DISTANCE_METERS) {
            DiagnosticLog.event("Loc", "recenter follow");
            // 刚居中时的定位可能是系统缓存的旧点，新定位稍后到达。此时地图
            // 仍停在旧点上（既没回到自身定位中心，又会显示指示针），窗口内
            // 跟随新定位修正一次；用户自己操作过地图就不再抢回镜头。后台挂起
            // 较久时新定位可能超过固定窗口才到，recenterAwaitFixSinceElapsedMs
            // 会让等待一直持续到新定位到达（最多 RECENTER_FRESH_FIX_TIMEOUT_MS）。
            recenterToCurrentLocation(recenterZoom, false);
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

        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (reminderFlow == REMINDER_FLOW_FIRST_USE) {
                    continueReminderFirstUseExactAlarm();
                } else if (reminderFlow == REMINDER_FLOW_SCHEDULE) {
                    continueScheduleExactAlarm();
                }
            } else {
                int pendingFlow = reminderFlow;
                resetReminderSetupState();
                updateReminderButtonState();
                showNotificationPermissionRequiredDialog(pendingFlow);
            }
            return;
        }

        if (requestCode == REQUEST_LOCATION) {
            if (hasLocationPermission()) {
                startLocationUpdates();
            } else {
                tvStatus.setText(R.string.permission_location);
            }
            return;
        }

        if (requestCode == REQUEST_VEHICLE_ALERT_LOCATION) {
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startLocationUpdates();
                applyVehicleAlertState(true);
            } else {
                updateVehicleAlertStatus(R.string.vehicle_alert_paused_location);
                Toast.makeText(this, R.string.permission_location, Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQUEST_STORAGE) {
                pendingQrBitmap = null;
                pendingQrNumber = "";
                pendingOpenWeChatAfterQrSave = false;
                Toast.makeText(this, R.string.permission_storage_denied, Toast.LENGTH_LONG)
                        .show();
            }
            return;
        }

        if (requestCode == REQUEST_STORAGE
                && pendingQrBitmap != null
                && !pendingQrNumber.isEmpty()) {
            Bitmap bitmap = pendingQrBitmap;
            String number = pendingQrNumber;
            pendingQrBitmap = null;
            pendingQrNumber = "";
            openWeChatAfterQrSaveIfNeeded(performQrSave(bitmap, number));
        }
    }

    /**
     * 提醒到时的小方块：只在应用处于前台、且当前页面就是主页面时显示。
     * 由 ReminderScheduler 在提醒触发时调用；不在前台时什么也不弹，只留系统通知。
     */
    void showReminderAlertSquare(int leadMinutes) {
        runOnUiThread(() -> {
            if (foregroundInstance != this) {
                // 不在前台：只留系统通知，回到前台后也不再补弹。
                return;
            }
            View root = findViewById(R.id.root);
            if (!(root instanceof ViewGroup)) {
                return;
            }
            if (reminderAlertOverlay == null) {
                reminderAlertOverlay = LayoutInflater.from(this).inflate(
                        R.layout.activity_reminder_alert,
                        (ViewGroup) root,
                        false
                );
                reminderAlertOverlay.findViewById(R.id.reminderAlertRoot)
                        .setOnClickListener(view -> dismissReminderAlertSquare());
                reminderAlertOverlay.findViewById(R.id.reminderAlertCard)
                        .setOnClickListener(view -> openWeChatFromReminderAlert());
                ((ViewGroup) root).addView(reminderAlertOverlay);
            }
            TextView message = reminderAlertOverlay.findViewById(R.id.tvReminderAlertMessage);
            if (message != null) {
                message.setText(getString(R.string.reminder_alert_short, leadMinutes));
            }
            slideReminderAlertCardIn();
        });
    }

    private void slideReminderAlertCardIn() {
        if (reminderAlertOverlay == null) {
            return;
        }
        View card = reminderAlertOverlay.findViewById(R.id.reminderAlertCard);
        card.post(() -> {
            float offset = card.getWidth()
                    + getResources().getDisplayMetrics().density * 16f;
            card.setTranslationX(offset);
            card.setAlpha(0f);
            card.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(REMINDER_ALERT_SLIDE_MS)
                    .start();
        });
    }

    private void dismissReminderAlertSquare() {
        View overlay = reminderAlertOverlay;
        reminderAlertOverlay = null;
        if (overlay == null) {
            return;
        }
        ViewGroup parent = overlay.getParent() instanceof ViewGroup
                ? (ViewGroup) overlay.getParent()
                : null;
        if (parent != null) {
            parent.removeView(overlay);
        }
    }

    private void openWeChatFromReminderAlert() {
        dismissReminderAlertSquare();
        openWeChatHome();
    }

    /**
     * 提醒小方块点击后跳转微信主页面。
     * 微信的小程序面板/小程序列表属于非导出组件（EXT_OPEN_APPBRAND_LAUNCHER_UI
     * 是签名级权限），第三方应用无法直接打开，所以这里只负责把微信带到主页面。
     */
    private void openWeChatHome() {
        Intent dispatch = new Intent("com.tencent.mm.ui.ShortCutDispatchAction")
                .setPackage("com.tencent.mm");
        if (tryLaunchWeChat(dispatch)) {
            return;
        }
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launchIntent);
            } catch (RuntimeException exception) {
                Toast.makeText(this, R.string.wechat_not_installed, Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, R.string.wechat_not_installed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        DiagnosticLog.event("Main", "resume");
        foregroundInstance = this;
        mapView.onResume();
        if (awaitingVehicleAlertNotificationPermission) {
            awaitingVehicleAlertNotificationPermission = false;
            appPreferences.edit()
                    .remove(PREF_VEHICLE_ALERT_WAITING_NOTIFICATION)
                    .apply();
            if (!VehicleAlertService.hasNotificationAccess(this)) {
                updateVehicleAlertStatus(
                        R.string.vehicle_alert_paused_notification
                );
            }
        }
        if (reminderFlow == REMINDER_FLOW_NONE && ReminderScheduler.isActive(this)) {
            ReminderScheduler.rescheduleActive(this);
        }
        resumeReminderPermissionFlow();
        updateReminderButtonState();
        applyVehicleAlertState();
        // 回前台后，如果暂停前镜头本来就在自身定位上，就等一个比本次恢复更新的
        // 定位再把镜头带回自身位置。否则用户期间移动后，地图会停在旧位置、
        // 蓝点却已经移到别处，看起来“视角和定位对不上”。
        if (currentLocation != null
                && !vehicleFocusActive
                && distanceBetween(mapCenterGcj02(), currentLocationDisplayPoint())
                        <= CENTER_INDICATOR_DISTANCE_METERS) {
            recenterAwaitFixSinceElapsedMs = SystemClock.elapsedRealtime();
        }
        // Foreground-only location: re-register on every resume, otherwise the
        // position stays frozen after the app returns from the background.
        if (hasLocationPermission()) {
            startLocationUpdates();
        }
    }

    private void resumeReminderPermissionFlow() {
        // 已经从系统设置页回来，保存的待办步骤可以清掉。
        if (awaitingExactAlarmPermission
                || awaitingReminderChannelPermission
                || awaitingBatteryOptimizationPermission) {
            clearReminderFlowStep();
        }
        if (awaitingExactAlarmPermission) {
            resumeExactAlarmPermissionFlow();
            return;
        }
        if (awaitingReminderChannelPermission) {
            awaitingReminderChannelPermission = false;
            // 从通知设置页返回后必须重新验证总开关和提醒类别，
            // 否则用户未真正开启时会被错误地继续到后台运行流程。
            continueReminderChannelCheck(false);
            return;
        }
        if (awaitingBatteryOptimizationPermission) {
            awaitingBatteryOptimizationPermission = false;
            if (!isIgnoringBatteryOptimizations()) {
                showBackgroundSettingsRequiredDialog();
            } else {
                continueAfterBackgroundSettings();
            }
            return;
        }
    }

    @Override
    protected void onPause() {
        DiagnosticLog.event("Main", "pause");
        mapCacheDialogGeneration++;
        if (foregroundInstance == this) {
            foregroundInstance = null;
        }
        // 不可见时不再执行地图移动触发的查询，避免在后台发网络请求。
        uiHandler.removeCallbacks(refreshFromMapCenterRunnable);
        stopLocationUpdates();
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(refreshFromMapCenterRunnable);
        cancelReminderDialogTimer();
        stopLocationUpdates();
        if (repository != null) {
            repository.shutdown();
        }
        super.onDestroy();
    }

}
