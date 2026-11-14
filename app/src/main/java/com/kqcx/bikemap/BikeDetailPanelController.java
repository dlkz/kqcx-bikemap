package com.kqcx.bikemap;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class BikeDetailPanelController {
    interface Host {
        String batteryValue(Bike bike);

        String distanceLabel(Bike bike);

        String uploadAgeLabel(long ageMs);

        void showQr(Bike bike);

        void findVehicle(Bike bike);
    }

    private final Context context;
    private final LinearLayout panel;
    private final TextView number;
    private final TextView meta;
    private final TextView freshness;
    private final TextView manualNotice;
    private final Button findVehicle;
    private final Button showQr;
    private final Host host;
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private final SimpleDateFormat dayClockFormat =
            new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    private Bike currentBike;

    BikeDetailPanelController(Context context, View root, Host host) {
        this.context = context;
        this.host = host;
        panel = root.findViewById(R.id.bikePanel);
        number = root.findViewById(R.id.tvBikeNumber);
        meta = root.findViewById(R.id.tvBikeMeta);
        freshness = root.findViewById(R.id.tvBikeFreshness);
        manualNotice = root.findViewById(R.id.tvManualNotice);
        findVehicle = root.findViewById(R.id.btnFindVehicle);
        showQr = root.findViewById(R.id.btnShowQr);
    }

    void setup() {
        findVehicle.setOnClickListener(view -> {
            if (currentBike != null && currentBike.locationKnown) {
                host.findVehicle(currentBike);
            }
        });
        showQr.setOnClickListener(view -> {
            if (currentBike != null) {
                host.showQr(currentBike);
            }
        });
    }

    boolean isVisible() {
        return panel.getVisibility() == View.VISIBLE;
    }

    void show(Bike bike) {
        if (bike == null) {
            hide();
            return;
        }
        currentBike = bike;
        panel.setVisibility(View.VISIBLE);
        bind(bike);
    }

    void update(Bike bike) {
        if (bike == null) {
            hide();
            return;
        }
        currentBike = bike;
        if (isVisible()) {
            bind(bike);
        }
    }

    void hide() {
        currentBike = null;
        panel.setVisibility(View.GONE);
    }

    private void bind(Bike bike) {
        number.setText(context.getString(R.string.number_format, bike.number));
        findVehicle.setVisibility(bike.locationKnown ? View.VISIBLE : View.GONE);
        manualNotice.setVisibility(bike.locationKnown ? View.GONE : View.VISIBLE);

        if (!bike.locationKnown) {
            meta.setText(R.string.bike_meta_manual);
        } else {
            String detail = context.getString(bike.statusRes);
            if (bike.model != null && !bike.model.isEmpty()) {
                detail += " · " + bike.model;
            }
            meta.setText(context.getString(
                    R.string.bike_meta,
                    host.batteryValue(bike),
                    host.distanceLabel(bike),
                    detail
            ));
        }

        if (!bike.locationKnown || bike.lastUploadAtMs <= 0L) {
            freshness.setVisibility(View.GONE);
            return;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - bike.lastUploadAtMs);
        String uploadClock = ageMs < 24L * 60L * 60L * 1000L
                ? clockFormat.format(new Date(bike.lastUploadAtMs))
                : dayClockFormat.format(new Date(bike.lastUploadAtMs));
        freshness.setText(context.getString(
                R.string.bike_last_upload,
                uploadClock,
                host.uploadAgeLabel(ageMs)
        ));
        freshness.setVisibility(View.VISIBLE);
    }
}
