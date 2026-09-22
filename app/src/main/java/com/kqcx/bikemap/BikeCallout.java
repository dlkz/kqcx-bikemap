package com.kqcx.bikemap;

import android.widget.TextView;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.infowindow.InfoWindow;

/**
 * Replaces osmdroid's default grey bubble with the app's own card style.
 */
public final class BikeCallout extends InfoWindow {
    public BikeCallout(MapView mapView) {
        super(R.layout.map_callout, mapView);
    }

    @Override
    public void onOpen(Object item) {
        if (!(item instanceof Marker)) {
            return;
        }
        String title = ((Marker) item).getTitle();
        TextView number = mView.findViewById(R.id.calloutNumber);
        number.setText(mMapView.getContext().getString(
                R.string.map_callout_number,
                title == null ? "" : title
        ));
    }

    @Override
    public void onClose() {
        // Nothing to release: the view is reused for every marker.
    }
}
