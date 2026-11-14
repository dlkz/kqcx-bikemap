package com.kqcx.bikemap;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class VehicleNumberDialogHistoryPanel {
    interface Host {
        void clearHistory();

        void openSnapshot(FinderSnapshotStore.Snapshot snapshot);

        void openHistory(QrHistoryStore.Entry entry);
    }

    private final Context context;
    private final LinearLayout historySection;
    private final LinearLayout historyContainer;
    private final LinearLayout snapshotSection;
    private final LinearLayout snapshotContainer;
    private final FinderSnapshotStore snapshotStore;
    private final QrHistoryStore historyStore;
    private final Host host;
    private final SimpleDateFormat uploadTimeFormat =
            new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    VehicleNumberDialogHistoryPanel(
            Context context,
            View root,
            FinderSnapshotStore snapshotStore,
            QrHistoryStore historyStore,
            Host host
    ) {
        this.context = context;
        this.snapshotStore = snapshotStore;
        this.historyStore = historyStore;
        this.host = host;
        historySection = root.findViewById(R.id.historySection);
        historyContainer = root.findViewById(R.id.historyContainer);
        snapshotSection = root.findViewById(R.id.finderSnapshotSection);
        snapshotContainer = root.findViewById(R.id.finderSnapshotContainer);
        configureDividers(historyContainer);
        configureDividers(snapshotContainer);
    }

    void render(
            Runnable dismissDialog,
            VehicleNumberCatalog catalog
    ) {
        historyContainer.removeAllViews();
        historySection.setVisibility(View.GONE);
        snapshotContainer.removeAllViews();
        snapshotSection.setVisibility(View.GONE);

        if (!historyStore.retentionEnabled()) {
            snapshotStore.clear();
        }
        List<FinderSnapshotStore.Snapshot> snapshots = snapshotStore.load();
        if (!snapshots.isEmpty()) {
            snapshotSection.setVisibility(View.VISIBLE);
            snapshotSection.findViewById(R.id.tvClearFinderSnapshots)
                    .setOnClickListener(view -> clearSnapshots());
            for (FinderSnapshotStore.Snapshot snapshot : snapshots) {
                View row = createSnapshotRow(snapshot);
                row.setOnClickListener(view -> {
                    dismissDialog.run();
                    host.openSnapshot(snapshot);
                });
                snapshotContainer.addView(row);
            }
        }

        List<QrHistoryStore.Entry> history = historyStore.load(catalog);
        if (!history.isEmpty()) {
            historySection.setVisibility(View.VISIBLE);
            historySection.findViewById(R.id.tvClearHistory)
                    .setOnClickListener(view -> clearHistory());
            for (QrHistoryStore.Entry entry : history) {
                View row = createHistoryRow(entry, catalog);
                row.setOnClickListener(view -> {
                    dismissDialog.run();
                    host.openHistory(entry);
                });
                historyContainer.addView(row);
            }
        }
    }

    private void clearSnapshots() {
        snapshotStore.clear();
        snapshotContainer.removeAllViews();
        snapshotSection.setVisibility(View.GONE);
        Toast.makeText(
                context,
                R.string.finder_snapshot_cleared,
                Toast.LENGTH_SHORT
        ).show();
    }

    private void clearHistory() {
        host.clearHistory();
        historyContainer.removeAllViews();
        historySection.setVisibility(View.GONE);
        snapshotContainer.removeAllViews();
        snapshotSection.setVisibility(View.GONE);
        Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show();
    }

    private View createSnapshotRow(FinderSnapshotStore.Snapshot snapshot) {
        String savedAt = uploadTimeFormat.format(new Date(snapshot.capturedAtMs));
        StringBuilder detail = new StringBuilder();
        if (!snapshot.siteName.isEmpty()) {
            detail.append(snapshot.siteName).append(" · ");
        }
        detail.append(context.getString(
                R.string.finder_snapshot_saved_at,
                savedAt
        ));
        return createRecentVehicleRow(
                snapshotContainer,
                snapshot.number,
                detail.toString(),
                R.string.finder_snapshot_action,
                48,
                56,
                6
        );
    }

    private View createHistoryRow(
            QrHistoryStore.Entry entry,
            VehicleNumberCatalog catalog
    ) {
        String siteName = entry.siteName;
        if (siteName.isEmpty() && catalog != null) {
            VehicleNumberCatalog.NumberInfo info = catalog.find(entry.number);
            siteName = info == null || info.siteName == null ? "" : info.siteName;
        }
        return createRecentVehicleRow(
                historyContainer,
                entry.displayNumber(),
                BikeRepository.correctSiteName(siteName),
                entry.number.isEmpty()
                        ? R.string.history_action_complete
                        : R.string.history_action,
                38,
                76,
                5
        );
    }

    private View createRecentVehicleRow(
            ViewGroup parent,
            String title,
            String detail,
            int actionRes,
            int minimumHeightDp,
            int actionMinimumWidthDp,
            int verticalPaddingDp
    ) {
        View row = LayoutInflater.from(context).inflate(
                R.layout.item_recent_vehicle_row,
                parent,
                false
        );
        row.setMinimumHeight(dp(minimumHeightDp));
        row.setPadding(dp(10), dp(verticalPaddingDp), dp(10), dp(verticalPaddingDp));

        ((TextView) row.findViewById(R.id.tvRecentVehicleTitle)).setText(title);
        TextView detailView = row.findViewById(R.id.tvRecentVehicleDetail);
        detailView.setText(detail);
        detailView.setVisibility(detail == null || detail.isEmpty()
                ? View.GONE
                : View.VISIBLE);

        TextView action = row.findViewById(R.id.tvRecentVehicleAction);
        action.setText(actionRes);
        action.setMinWidth(dp(actionMinimumWidthDp));
        return row;
    }

    private void configureDividers(LinearLayout container) {
        container.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        container.setDividerDrawable(
                new ColorDrawable(context.getColor(R.color.divider))
        );
        container.setDividerPadding(dp(6));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
