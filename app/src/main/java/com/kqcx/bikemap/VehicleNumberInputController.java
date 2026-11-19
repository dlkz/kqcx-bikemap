package com.kqcx.bikemap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

final class VehicleNumberInputController {
    interface Host {
        String selectedBikeNumber();

        void openNumber(String number);

        void clearHistory();

        void openSnapshot(FinderSnapshotStore.Snapshot snapshot);

        void openHistory(QrHistoryStore.Entry entry);
    }

    interface NumberOptionListener {
        void onSelected(int index);
    }

    private static final String PREF_NUMBER_SITE_ID = "number_site_id";
    private static final String PREF_NUMBER_SERIES = "number_series";

    private final Activity activity;
    private final SharedPreferences preferences;
    private final FinderSnapshotStore snapshotStore;
    private final QrHistoryStore historyStore;
    private final Host host;

    private VehicleNumberCatalog catalog;
    private boolean catalogLoadFinished;
    private Session activeSession;

    VehicleNumberInputController(
            Activity activity,
            SharedPreferences preferences,
            FinderSnapshotStore snapshotStore,
            QrHistoryStore historyStore,
            VehicleNumberCatalog catalog,
            boolean catalogLoadFinished,
            Host host
    ) {
        this.activity = activity;
        this.preferences = preferences;
        this.snapshotStore = snapshotStore;
        this.historyStore = historyStore;
        this.catalog = catalog == null ? VehicleNumberCatalog.empty() : catalog;
        this.catalogLoadFinished = catalogLoadFinished;
        this.host = host;
    }

    void setCatalog(VehicleNumberCatalog catalog, boolean loadFinished) {
        this.catalog = catalog == null ? VehicleNumberCatalog.empty() : catalog;
        catalogLoadFinished = loadFinished;
        if (activeSession != null) {
            activeSession.refreshCatalog();
        }
    }

    void show(String initialValue) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (activeSession != null) {
            Session previous = activeSession;
            activeSession = null;
            previous.dismiss();
        }
        Session session = new Session(initialValue);
        activeSession = session;
        session.show();
    }

    void release() {
        if (activeSession == null) {
            return;
        }
        Session session = activeSession;
        activeSession = null;
        session.dismiss();
    }

    private int preferredNumberSiteId() {
        String selectedNumber = host.selectedBikeNumber();
        if (selectedNumber != null && !selectedNumber.isEmpty()) {
            VehicleNumberCatalog.NumberInfo info = catalog.find(selectedNumber);
            if (info != null) {
                return info.siteId;
            }
        }
        int stored = preferences.getInt(PREF_NUMBER_SITE_ID, -1);
        if (catalog.site(stored) != null) {
            return stored;
        }
        List<VehicleNumberCatalog.Site> sites = catalog.sites();
        return sites.isEmpty() ? -1 : sites.get(0).id;
    }

    private String preferredNumberSeries(int siteId) {
        VehicleNumberCatalog.Site site = catalog.site(siteId);
        if (site == null || site.series.isEmpty()) {
            return "";
        }
        String stored = preferences.getString(PREF_NUMBER_SERIES, "");
        if (stored != null && site.series.contains(stored)) {
            return stored;
        }
        return site.series.get(0);
    }

    private final class Session {
        private final EditText input;
        private final View previewPanel;
        private final TextView previewNumber;
        private final TextView previewSite;
        private final TextView error;
        private final TextView numberSite;
        private final TextView numberSeries;
        private final View numberRangeRow;
        private final AlertDialog dialog;

        private VehicleNumberInputResolver resolver;
        private String resolvedNumber = "";
        private List<VehicleNumberCatalog.NumberInfo> candidateMatches;

        Session(String initialValue) {
            ViewGroup dialogRoot = activity.findViewById(android.R.id.content);
            View content = LayoutInflater.from(activity).inflate(
                    R.layout.dialog_number_input,
                    dialogRoot,
                    false
            );
            input = content.findViewById(R.id.inputTail);
            previewPanel = content.findViewById(R.id.inputPreviewPanel);
            previewNumber = content.findViewById(R.id.tvInputPreviewNumber);
            previewSite = content.findViewById(R.id.tvInputPreviewSite);
            error = content.findViewById(R.id.tvInputError);
            numberSite = content.findViewById(R.id.tvNumberSite);
            numberSeries = content.findViewById(R.id.tvNumberSeries);
            numberRangeRow = content.findViewById(R.id.numberRangeRow);
            dialog = new AlertDialog.Builder(activity)
                    .setView(content)
                    .setPositiveButton(R.string.confirm, null)
                    .setNegativeButton(R.string.cancel, null)
                    .create();

            int initialSiteId = preferredNumberSiteId();
            resolver = new VehicleNumberInputResolver(
                    catalog,
                    catalogLoadFinished,
                    initialSiteId,
                    preferredNumberSeries(initialSiteId)
            );
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
            numberRangeRow.setOnClickListener(
                    view -> showNumberRangePicker()
            );
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(
                        CharSequence value,
                        int start,
                        int count,
                        int after
                ) {
                }

                @Override
                public void onTextChanged(
                        CharSequence value,
                        int start,
                        int before,
                        int count
                ) {
                    updateResolution();
                }

                @Override
                public void afterTextChanged(Editable value) {
                }
            });
            new VehicleNumberDialogHistoryPanel(
                    activity,
                    content,
                    snapshotStore,
                    historyStore,
                    new VehicleNumberDialogHistoryPanel.Host() {
                        @Override
                        public void clearHistory() {
                            host.clearHistory();
                        }

                        @Override
                        public void openSnapshot(FinderSnapshotStore.Snapshot snapshot) {
                            host.openSnapshot(snapshot);
                        }

                        @Override
                        public void openHistory(QrHistoryStore.Entry entry) {
                            host.openHistory(entry);
                        }
                    }
            ).render(dialog::dismiss, catalog);
        }

        void show() {
            dialog.setOnShowListener(ignored -> {
                Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                confirm.setEnabled(false);
                confirm.setOnClickListener(view -> confirmSelection());
                updateResolution();
                input.requestFocus();
            });
            dialog.setOnDismissListener(ignored -> {
                if (activeSession == this) {
                    activeSession = null;
                }
            });
            dialog.show();
        }

        void dismiss() {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        }

        void refreshCatalog() {
            int siteId = resolver.siteId();
            String series = resolver.series();
            resolver = new VehicleNumberInputResolver(
                    catalog,
                    catalogLoadFinished,
                    siteId,
                    series
            );
            if (dialog.isShowing()) {
                updateResolution();
            }
        }

        private void confirmSelection() {
            updateResolution();
            if (resolvedNumber.isEmpty()) {
                return;
            }
            preferences.edit()
                    .putInt(PREF_NUMBER_SITE_ID, resolver.siteId())
                    .putString(PREF_NUMBER_SERIES, resolver.series())
                    .apply();
            String number = resolvedNumber;
            dialog.dismiss();
            host.openNumber(number);
        }

        private void updateResolution() {
            if (catalogLoadFinished && catalog.site(resolver.siteId()) == null) {
                int preferredSiteId = preferredNumberSiteId();
                resolver.select(
                        preferredSiteId,
                        preferredNumberSeries(preferredSiteId)
                );
            } else {
                VehicleNumberCatalog.Site selectedSite =
                        catalog.site(resolver.siteId());
                if (selectedSite != null
                        && !selectedSite.series.contains(resolver.series())) {
                    resolver.select(
                            selectedSite.id,
                            preferredNumberSeries(selectedSite.id)
                    );
                }
            }

            VehicleNumberCatalog.Site selectedSite =
                    catalog.site(resolver.siteId());
            boolean catalogAvailable = !catalog.sites().isEmpty();
            numberRangeRow.setEnabled(catalogAvailable);
            numberRangeRow.setAlpha(catalogAvailable ? 1f : 0.5f);
            if (!catalogLoadFinished) {
                numberSite.setText(R.string.number_catalog_loading);
                numberSeries.setText("");
            } else if (!catalogAvailable) {
                numberSite.setText(R.string.number_catalog_unavailable);
                numberSeries.setText("");
            } else if (selectedSite != null) {
                numberSite.setText(selectedSite.name);
                numberSeries.setText(activity.getString(
                        R.string.number_series_title_format,
                        resolver.series()
                ));
            }

            resolvedNumber = "";
            candidateMatches = null;
            error.setVisibility(View.GONE);
            previewPanel.setVisibility(View.GONE);
            previewPanel.setClickable(false);
            previewPanel.setOnClickListener(null);
            previewSite.setVisibility(View.GONE);

            VehicleNumberInputResolver.Result result =
                    resolver.resolve(input.getText().toString());
            switch (result.status) {
                case EMPTY:
                    setConfirmEnabled(false);
                    return;
                case CATALOG_LOADING:
                    numberSite.setText(R.string.number_catalog_loading);
                    numberSeries.setText("");
                    showError(R.string.number_catalog_loading);
                    return;
                case CATALOG_UNAVAILABLE:
                    numberSite.setText(R.string.number_catalog_unavailable);
                    numberSeries.setText("");
                    showError(R.string.number_catalog_unavailable);
                    return;
                case DIRECT:
                    numberSite.setText(R.string.input_number_direct_title);
                    numberSeries.setText(R.string.input_number_direct_desc);
                    resolvedNumber = result.number;
                    showPreview(result.number);
                    setConfirmEnabled(true);
                    return;
                case RESOLVED:
                    showResolved(result.info);
                    return;
                case CANDIDATES:
                    candidateMatches = result.candidates;
                    previewNumber.setText(activity.getString(
                            R.string.input_number_candidates,
                            candidateMatches.size()
                    ));
                    previewSite.setText(R.string.input_number_candidates_hint);
                    previewSite.setVisibility(View.VISIBLE);
                    previewPanel.setClickable(true);
                    previewPanel.setFocusable(true);
                    previewPanel.setOnClickListener(
                            view -> showNumberCandidates(candidateMatches)
                    );
                    previewPanel.setVisibility(View.VISIBLE);
                    setConfirmEnabled(false);
                    return;
                case INCOMPLETE:
                    showError(R.string.input_number_incomplete);
                    return;
                case NOT_FOUND:
                default:
                    showError(R.string.input_number_not_found);
            }
        }

        private void showResolved(VehicleNumberCatalog.NumberInfo info) {
            if (info == null) {
                showError(R.string.input_number_not_found);
                return;
            }
            numberSite.setText(info.siteName);
            numberSeries.setText(activity.getString(
                    R.string.number_series_title_format,
                    info.series
            ));
            resolvedNumber = info.number;
            showPreview(info.number);
            setConfirmEnabled(true);
        }

        private void showPreview(String number) {
            previewNumber.setText(activity.getString(
                    R.string.input_preview_number,
                    number
            ));
            previewSite.setVisibility(View.GONE);
            previewPanel.setVisibility(View.VISIBLE);
        }

        private void showError(int messageRes) {
            error.setText(messageRes);
            error.setVisibility(View.VISIBLE);
            setConfirmEnabled(false);
        }

        private void setConfirmEnabled(boolean enabled) {
            if (!dialog.isShowing()) {
                return;
            }
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (confirm != null) {
                confirm.setEnabled(enabled);
            }
        }

        private void showNumberCandidates(
                List<VehicleNumberCatalog.NumberInfo> candidates
        ) {
            String[] labels = new String[candidates.size()];
            for (int index = 0; index < candidates.size(); index++) {
                VehicleNumberCatalog.NumberInfo candidate = candidates.get(index);
                labels[index] = candidate.number + " · " + candidate.siteName;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.number_candidates_title)
                    .setItems(labels, (dialog, which) -> {
                        VehicleNumberCatalog.NumberInfo info = candidates.get(which);
                        resolver.select(info.siteId, info.series);
                        updateResolution();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void showNumberRangePicker() {
            List<VehicleNumberCatalog.Site> sites = catalog.sites();
            if (sites.isEmpty()) {
                return;
            }
            String[] titles = new String[sites.size()];
            String[] subtitles = new String[sites.size()];
            for (int index = 0; index < sites.size(); index++) {
                VehicleNumberCatalog.Site site = sites.get(index);
                titles[index] = site.name;
                subtitles[index] = activity.getString(
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
                        String series = site.series.contains(resolver.series())
                                ? resolver.series()
                                : site.series.get(0);
                        resolver.select(site.id, series);
                        updateResolution();
                        if (site.series.size() > 1) {
                            showNumberSeriesPicker(site);
                        }
                    }
            );
        }

        private void showNumberSeriesPicker(VehicleNumberCatalog.Site site) {
            String[] titles = new String[site.series.size()];
            String[] subtitles = new String[site.series.size()];
            for (int index = 0; index < site.series.size(); index++) {
                String series = site.series.get(index);
                titles[index] = activity.getString(
                        R.string.number_series_title_format,
                        series
                );
                subtitles[index] = activity.getString(
                        R.string.number_series_subtitle_format,
                        series
                );
            }
            showNumberOptions(
                    R.string.number_range_series_title,
                    titles,
                    subtitles,
                    which -> {
                        resolver.select(site.id, site.series.get(which));
                        updateResolution();
                    }
            );
        }

        private void showNumberOptions(
                int titleRes,
                String[] titles,
                String[] subtitles,
                NumberOptionListener listener
        ) {
            View optionsContent = LayoutInflater.from(activity).inflate(
                    R.layout.dialog_number_options,
                    null
            );
            LinearLayout container =
                    optionsContent.findViewById(R.id.numberOptionsContainer);
            LayoutInflater inflater = LayoutInflater.from(activity);
            AlertDialog optionsDialog = new AlertDialog.Builder(activity)
                    .setTitle(titleRes)
                    .setView(optionsContent)
                    .setNegativeButton(R.string.cancel, null)
                    .create();
            for (int index = 0; index < titles.length; index++) {
                View row = inflater.inflate(
                        R.layout.item_number_option,
                        container,
                        false
                );
                TextView title = row.findViewById(R.id.tvNumberOptionTitle);
                TextView subtitle =
                        row.findViewById(R.id.tvNumberOptionSubtitle);
                title.setText(titles[index]);
                if (subtitles[index] == null || subtitles[index].isEmpty()) {
                    subtitle.setVisibility(View.GONE);
                } else {
                    subtitle.setText(subtitles[index]);
                    subtitle.setVisibility(View.VISIBLE);
                }
                int selectedIndex = index;
                row.setOnClickListener(view -> {
                    optionsDialog.dismiss();
                    listener.onSelected(selectedIndex);
                });
                container.addView(row);
            }
            optionsDialog.show();
        }
    }
}
