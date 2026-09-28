package com.kqcx.bikemap;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public final class AboutActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btnAboutBack).setOnClickListener(view -> finish());
        findViewById(R.id.rowAboutRepository).setOnClickListener(view -> openRepository());
        TextView versionView = findViewById(R.id.tvAboutVersion);
        versionView.setText(versionNumberLabel());
        applySystemBarInsets();
    }

    private void openRepository() {
        Intent intent = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.about_repository_url))
        );
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(
                    this,
                    R.string.about_repository_open_failed,
                    Toast.LENGTH_SHORT
            ).show();
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
