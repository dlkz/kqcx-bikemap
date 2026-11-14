package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class BikeDetailPanelControllerInstrumentedTest {
    @Test
    public void bindsManualAndKnownBikesAndRoutesActions() {
        Context context = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(),
                R.style.AppTheme
        );
        FrameLayout root = new FrameLayout(context);
        LinearLayout panel = new LinearLayout(context);
        panel.setId(R.id.bikePanel);
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        TextView number = addText(root, context, R.id.tvBikeNumber);
        TextView meta = addText(root, context, R.id.tvBikeMeta);
        TextView freshness = addText(root, context, R.id.tvBikeFreshness);
        TextView manualNotice = addText(root, context, R.id.tvManualNotice);
        Button find = addButton(root, context, R.id.btnFindVehicle);
        Button qr = addButton(root, context, R.id.btnShowQr);

        AtomicInteger findCount = new AtomicInteger();
        AtomicInteger qrCount = new AtomicInteger();
        BikeDetailPanelController controller = new BikeDetailPanelController(
                context,
                root,
                new BikeDetailPanelController.Host() {
                    @Override
                    public String batteryValue(Bike bike) {
                        return "80";
                    }

                    @Override
                    public String distanceLabel(Bike bike) {
                        return "约 10 米";
                    }

                    @Override
                    public String uploadAgeLabel(long ageMs) {
                        return "刚刚";
                    }

                    @Override
                    public void showQr(Bike bike) {
                        qrCount.incrementAndGet();
                    }

                    @Override
                    public void findVehicle(Bike bike) {
                        findCount.incrementAndGet();
                    }
                }
        );
        controller.setup();

        Bike manual = bike("900000001", false);
        controller.show(manual);
        assertTrue(controller.isVisible());
        assertEquals("900000001", number.getText().toString());
        assertEquals(View.GONE, find.getVisibility());
        assertEquals(View.VISIBLE, manualNotice.getVisibility());

        Bike known = bike("900000002", true);
        controller.show(known);
        assertEquals(View.VISIBLE, find.getVisibility());
        assertEquals(View.GONE, manualNotice.getVisibility());
        assertEquals(View.VISIBLE, freshness.getVisibility());
        assertFalse(freshness.getText().toString().isEmpty());
        assertFalse(meta.getText().toString().isEmpty());

        find.performClick();
        qr.performClick();
        assertEquals(1, findCount.get());
        assertEquals(1, qrCount.get());

        controller.hide();
        assertFalse(controller.isVisible());
        assertEquals(View.GONE, root.findViewById(R.id.bikePanel).getVisibility());
    }

    private static TextView addText(FrameLayout root, Context context, int id) {
        TextView view = new TextView(context);
        view.setId(id);
        root.addView(view, new FrameLayout.LayoutParams(1, 1));
        return view;
    }

    private static Button addButton(FrameLayout root, Context context, int id) {
        Button view = new Button(context);
        view.setId(id);
        root.addView(view, new FrameLayout.LayoutParams(1, 1));
        return view;
    }

    private static Bike bike(String number, boolean locationKnown) {
        return new Bike(
                number,
                locationKnown ? 30d : 0d,
                locationKnown ? 120d : 0d,
                80d,
                R.string.bike_status_available,
                "M1",
                "测试校区",
                "测试站点",
                12.3f,
                true,
                locationKnown,
                locationKnown ? System.currentTimeMillis() : 0L,
                ""
        );
    }

}
