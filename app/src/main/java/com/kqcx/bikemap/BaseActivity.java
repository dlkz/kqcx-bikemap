package com.kqcx.bikemap;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;

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
}
