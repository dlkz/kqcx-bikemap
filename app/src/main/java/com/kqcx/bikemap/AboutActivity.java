package com.kqcx.bikemap;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public final class AboutActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btnAboutBack).setOnClickListener(view -> finish());
        TextView versionView = findViewById(R.id.tvAboutVersion);
        versionView.setText(getString(R.string.version_format, versionName()));
        applySystemBarInsets();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException exception) {
            return "1.0.1";
        }
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.aboutRoot);
        View header = findViewById(R.id.aboutHeader);
        int baseHeight = dp(64);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.LayoutParams params = header.getLayoutParams();
            params.height = baseHeight + systemBars.top;
            header.setLayoutParams(params);
            header.setPadding(
                    header.getPaddingLeft(),
                    systemBars.top,
                    header.getPaddingRight(),
                    0
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
