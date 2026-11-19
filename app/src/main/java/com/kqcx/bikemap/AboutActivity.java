package com.kqcx.bikemap;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
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
import java.util.concurrent.RejectedExecutionException;

public final class AboutActivity extends BaseActivity {
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();
    private boolean exportInProgress;
    private boolean clearInProgress;

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
        updateDiagnosticsActions();
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
        if (exportInProgress || clearInProgress || isFinishing() || isDestroyed()) {
            return;
        }
        long exportGeneration = DiagnosticLog.captureExportGeneration();
        exportInProgress = true;
        updateDiagnosticsActions();
        try {
            exportExecutor.execute(() -> {
                File report;
                try {
                    report = DiagnosticLog.exportReport(this, exportGeneration);
                } catch (DiagnosticLog.ExportCancelledException exception) {
                    runOnUiThread(() -> finishDiagnosticsExport(true, false));
                    return;
                } catch (IOException | RuntimeException exception) {
                    runOnUiThread(() -> finishDiagnosticsExport(false, true));
                    return;
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    exportInProgress = false;
                    updateDiagnosticsActions();
                    if (!DiagnosticLog.isExportCurrent(exportGeneration, report)) {
                        refreshDiagnosticsSummary();
                        showDiagnosticsExportCancelled();
                        return;
                    }
                    shareDiagnosticsReport(report, exportGeneration);
                });
            });
        } catch (RejectedExecutionException exception) {
            exportInProgress = false;
            updateDiagnosticsActions();
            showDiagnosticsExportFailed();
        }
    }

    private void shareDiagnosticsReport(File report, long exportGeneration) {
        if (!DiagnosticLog.isExportCurrent(exportGeneration, report)) {
            showDiagnosticsExportCancelled();
            refreshDiagnosticsSummary();
            return;
        }
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

    private void finishDiagnosticsExport(boolean cancelled, boolean failed) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        exportInProgress = false;
        updateDiagnosticsActions();
        if (cancelled) {
            refreshDiagnosticsSummary();
            showDiagnosticsExportCancelled();
        } else if (failed) {
            showDiagnosticsExportFailed();
        }
    }

    private void showDiagnosticsExportFailed() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Toast.makeText(this, R.string.about_diagnostics_export_failed, Toast.LENGTH_SHORT)
                .show();
    }

    private void showDiagnosticsExportCancelled() {
        Toast.makeText(
                this,
                R.string.about_diagnostics_export_cancelled,
                Toast.LENGTH_SHORT
        ).show();
    }

    private void updateDiagnosticsActions() {
        boolean enabled = !exportInProgress && !clearInProgress;
        View export = findViewById(R.id.rowAboutDiagnosticsExport);
        View clear = findViewById(R.id.rowAboutDiagnosticsClear);
        export.setEnabled(enabled);
        export.setAlpha(enabled ? 1f : 0.5f);
        clear.setEnabled(enabled);
        clear.setAlpha(enabled ? 1f : 0.5f);
    }

    @Override
    protected void onDestroy() {
        exportExecutor.shutdownNow();
        super.onDestroy();
    }

    private void confirmClearDiagnostics() {
        if (exportInProgress || clearInProgress) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_diagnostics_clear)
                .setMessage(R.string.about_diagnostics_clear_message)
                .setPositiveButton(
                        R.string.about_diagnostics_clear_action,
                        (dialog, which) -> {
                            clearInProgress = true;
                            updateDiagnosticsActions();
                            boolean cleared;
                            try {
                                cleared = DiagnosticLog.clearAll(this);
                            } finally {
                                clearInProgress = false;
                                updateDiagnosticsActions();
                            }
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
            applyHeaderInsets(header, baseHeight, systemBars.top);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
