package com.kqcx.bikemap;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.ComponentActivity;

public abstract class BaseActivity extends ComponentActivity {
    protected static final String PREFS_APP = "app_settings";
    protected static final String PREF_THEME = "theme_mode";
    protected static final String THEME_SYSTEM = "system";
    protected static final String THEME_LIGHT = "light";
    protected static final String THEME_DARK = "dark";

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences preferences = newBase.getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        String mode = preferences.getString(PREF_THEME, THEME_SYSTEM);
        if (THEME_SYSTEM.equals(mode)) {
            super.attachBaseContext(newBase);
            return;
        }

        Configuration configuration = new Configuration(
                newBase.getResources().getConfiguration()
        );
        int nightMode = THEME_DARK.equals(mode)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | nightMode;
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySelectedTheme();
        super.onCreate(savedInstanceState);
    }

    private void applySelectedTheme() {
        SharedPreferences preferences = getSharedPreferences(PREFS_APP, MODE_PRIVATE);
        String mode = preferences.getString(PREF_THEME, THEME_SYSTEM);
        boolean systemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = THEME_DARK.equals(mode) || (THEME_SYSTEM.equals(mode) && systemDark);
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme);
    }

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    protected boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    protected boolean hasLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    protected String formatUploadAge(long ageMs) {
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

    protected void applyHeaderInsets(View header, int baseHeight, int topInset) {
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

    protected String versionNumberLabel() {
        return getString(
                R.string.version_number_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.BUILD_NUMBER
        );
    }
}
