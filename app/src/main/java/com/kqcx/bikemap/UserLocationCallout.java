package com.kqcx.bikemap;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.infowindow.InfoWindow;

/** Compact callout for the user's own position marker. */
public final class UserLocationCallout extends InfoWindow {
    public UserLocationCallout(MapView mapView) {
        super(R.layout.map_user_callout, mapView);
    }

    @Override
    public void onOpen(Object item) {
        // Content is static; the view is reused for the single user marker.
    }

    @Override
    public void onClose() {
        // Nothing to release.
    }
}
