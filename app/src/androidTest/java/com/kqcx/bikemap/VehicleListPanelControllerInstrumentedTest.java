package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public final class VehicleListPanelControllerInstrumentedTest {
    @Test
    public void showsExpandsRefreshesAndHidesPanel() {
        Context context = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(),
                R.style.AppTheme
        );
        FrameLayout root = new FrameLayout(context);
        LayoutInflater.from(context).inflate(R.layout.view_vehicle_list_panel, root, true);
        addControl(root, context, R.id.leftControls);
        addControl(root, context, R.id.btnList);
        addControl(root, context, R.id.btnRefresh);
        Bike bike = new Bike(
                "900000001",
                1d,
                1d,
                80d,
                R.string.bike_status_available,
                "",
                "测试校区",
                "测试站点",
                12.3f,
                true,
                true,
                1_000L,
                ""
        );
        VehicleListPanelController controller = new VehicleListPanelController(
                context,
                root,
                new VehicleListPanelController.Host() {
                    @Override
                    public boolean isSelected(Bike candidate) {
                        return false;
                    }

                    @Override
                    public String siteName(Bike candidate) {
                        return candidate.serviceSiteName;
                    }

                    @Override
                    public String distanceLabel(Bike candidate) {
                        return "约 10 米";
                    }

                    @Override
                    public String batteryLabel(Bike candidate) {
                        return "80%";
                    }

                    @Override
                    public void locate(Bike candidate) {
                    }

                    @Override
                    public void select(Bike candidate) {
                    }
                }
        );

        controller.setup();
        controller.setBottomInset(24);
        controller.show(Collections.singletonList(bike), false);

        assertTrue(controller.isVisible());
        assertFalse(controller.isExpanded());
        assertEquals(View.VISIBLE, root.findViewById(R.id.leftControls).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.btnList).getVisibility());

        controller.setExpanded(true, false);

        assertTrue(controller.isExpanded());
        assertEquals(View.GONE, root.findViewById(R.id.leftControls).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.vehicleListScroll).getVisibility());

        controller.refresh(Collections.emptyList());

        assertFalse(controller.isVisible());
        assertEquals(View.VISIBLE, root.findViewById(R.id.leftControls).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.btnList).getVisibility());
        controller.release();
    }

    private static void addControl(FrameLayout root, Context context, int id) {
        View view = new View(context);
        view.setId(id);
        root.addView(view, new FrameLayout.LayoutParams(1, 1));
    }
}
