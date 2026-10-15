package com.kqcx.bikemap;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AboutActivity extends BaseActivity {
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btnAboutBack).setOnClickListener(view -> finish());
        findViewById(R.id.rowAboutRepository).setOnClickListener(view -> openRepository());
        TextView versionView = findViewById(R.id.tvAboutVersion);
        versionView.setText(versionNumberLabel());
        bindDiagnostics();
        applySystemBarInsets();
    }

    private void bindDiagnostics() {
        findViewById(R.id.rowAboutDiagnosticsExport)
                .setOnClickListener(view -> shareDiagnostics());
        Switch detailSwitch = findViewById(R.id.switchAboutDiagnosticsDetail);
        detailSwitch.setChecked(DiagnosticLog.isDetailEnabled());
        detailSwitch.setOnCheckedChangeListener((button, checked) ->
                DiagnosticLog.setDetailEnabled(this, checked));
        findViewById(R.id.rowAboutDiagnosticsDetail)
                .setOnClickListener(view -> detailSwitch.toggle());
        findViewById(R.id.rowAboutDiagnosticsClear)
                .setOnClickListener(view -> confirmClearDiagnostics());
        refreshDiagnosticsSummary();
    }

    private void refreshDiagnosticsSummary() {
        TextView summary = findViewById(R.id.tvAboutDiagnosticsSummary);
        summary.setText(DiagnosticLog.summary());
    }

    /**
     * 导出：把事件日志与崩溃记录合成一份报告，交给系统分享。应用自己不经手网络，
     * 去哪里由用户选（微信 / 邮件 / 文件管理）。
     */
    private void shareDiagnostics() {
        exportExecutor.execute(() -> {
            File report;
            try {
                report = DiagnosticLog.exportReport(this);
            } catch (IOException | RuntimeException exception) {
                runOnUiThread(() -> showDiagnosticsExportFailed());
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                shareDiagnosticsReport(report);
            });
        });
    }

    private void shareDiagnosticsReport(File report) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    report
            );
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(
                    share,
                    getString(R.string.about_diagnostics_export)
            ));
        } catch (ActivityNotFoundException | IllegalArgumentException exception) {
            showDiagnosticsExportFailed();
        }
        refreshDiagnosticsSummary();
    }

    private void showDiagnosticsExportFailed() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Toast.makeText(this, R.string.about_diagnostics_export_failed, Toast.LENGTH_SHORT)
                .show();
    }

    @Override
    protected void onDestroy() {
        exportExecutor.shutdown();
        super.onDestroy();
    }

    private void confirmClearDiagnostics() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_diagnostics_clear)
                .setMessage(R.string.about_diagnostics_clear_message)
                .setPositiveButton(
                        R.string.about_diagnostics_clear_action,
                        (dialog, which) -> {
                            boolean cleared = DiagnosticLog.clearAll(this);
                            refreshDiagnosticsSummary();
                            Toast.makeText(
                                    this,
                                    cleared
                                            ? R.string.about_diagnostics_cleared
                                            : R.string.about_diagnostics_clear_failed,
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                )
                .setNegativeButton(R.string.cancel, null)
                .show();
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
