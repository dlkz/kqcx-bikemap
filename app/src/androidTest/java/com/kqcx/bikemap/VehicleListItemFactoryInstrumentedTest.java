package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class VehicleListItemFactoryInstrumentedTest {
    @Test
    public void buildsCardAndRowAndRoutesActions() {
        Context context = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(),
                R.style.AppTheme
        );
        AtomicInteger selectCount = new AtomicInteger();
        AtomicInteger locateCount = new AtomicInteger();
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
        VehicleListItemFactory factory = new VehicleListItemFactory(
                context,
                new VehicleListItemFactory.Host() {
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
                    public int cardWidth() {
                        return 900;
                    }

                    @Override
                    public void locate(Bike candidate) {
                        locateCount.incrementAndGet();
                    }

                    @Override
                    public void select(Bike candidate) {
                        selectCount.incrementAndGet();
                    }
                }
        );

        View card = factory.createCard(bike);
        View row = factory.createRow(bike);
        assertNotNull(card);
        assertNotNull(row);
        assertTrue(card instanceof LinearLayout);

        LinearLayout titleRow = (LinearLayout) ((LinearLayout) card).getChildAt(0);
        titleRow.getChildAt(1).performClick();
        assertEquals(1, locateCount.get());
        card.performClick();
        row.performClick();
        assertEquals(2, selectCount.get());
    }
}
