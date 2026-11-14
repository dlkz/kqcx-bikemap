package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class VehicleNumberDialogHistoryPanelInstrumentedTest {
    @Test
    public void rendersBothSectionsAndRoutesActions() {
        Context context = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(),
                R.style.AppTheme
        );
        String preferencesName = "history_panel_test_" + System.nanoTime();
        SharedPreferences preferences = context.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
        );
        preferences.edit().clear().commit();
        String snapshotPreferencesName = "snapshot_panel_test_" + System.nanoTime();
        FinderSnapshotStore snapshotStore = new FinderSnapshotStore(
                context,
                snapshotPreferencesName
        );
        snapshotStore.clear();
        try {
            QrHistoryStore historyStore = new QrHistoryStore(preferences);
            historyStore.remember("900000001", "测试校区", VehicleNumberCatalog.empty());
            Bike bike = new Bike(
                    "900000002",
                    30d,
                    120d,
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
            assertTrue(snapshotStore.saveInitial(bike, "测试校区", 2_000L));

            View content = LayoutInflater.from(context).inflate(
                    R.layout.dialog_number_input,
                    null
            );
            AtomicInteger dismissCount = new AtomicInteger();
            AtomicInteger snapshotOpenCount = new AtomicInteger();
            AtomicInteger historyOpenCount = new AtomicInteger();
            AtomicInteger clearHistoryCount = new AtomicInteger();
            AtomicReference<FinderSnapshotStore.Snapshot> openedSnapshot =
                    new AtomicReference<>();
            AtomicReference<QrHistoryStore.Entry> openedHistory = new AtomicReference<>();
            VehicleNumberDialogHistoryPanel panel =
                    new VehicleNumberDialogHistoryPanel(
                            context,
                            content,
                            snapshotStore,
                            historyStore,
                            new VehicleNumberDialogHistoryPanel.Host() {
                                @Override
                                public void clearHistory() {
                                    clearHistoryCount.incrementAndGet();
                                }

                                @Override
                                public void openSnapshot(FinderSnapshotStore.Snapshot snapshot) {
                                    snapshotOpenCount.incrementAndGet();
                                    openedSnapshot.set(snapshot);
                                }

                                @Override
                                public void openHistory(QrHistoryStore.Entry entry) {
                                    historyOpenCount.incrementAndGet();
                                    openedHistory.set(entry);
                                }
                            }
                    );

            panel.render(dismissCount::incrementAndGet, VehicleNumberCatalog.empty());

            assertEquals(
                    View.VISIBLE,
                    content.findViewById(R.id.finderSnapshotSection).getVisibility()
            );
            assertEquals(
                    View.VISIBLE,
                    content.findViewById(R.id.historySection).getVisibility()
            );
            LinearLayout snapshotRows = content.findViewById(R.id.finderSnapshotContainer);
            LinearLayout historyRows = content.findViewById(R.id.historyContainer);
            assertEquals(1, snapshotRows.getChildCount());
            assertEquals(1, historyRows.getChildCount());

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                snapshotRows.getChildAt(0).performClick();
                historyRows.getChildAt(0).performClick();
            });
            assertEquals(2, dismissCount.get());
            assertEquals(1, snapshotOpenCount.get());
            assertEquals(1, historyOpenCount.get());
            assertEquals("900000002", openedSnapshot.get().number);
            assertEquals("900000001", openedHistory.get().number);

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    content.findViewById(R.id.tvClearFinderSnapshots).performClick()
            );
            assertEquals(
                    View.GONE,
                    content.findViewById(R.id.finderSnapshotSection).getVisibility()
            );
            assertTrue(snapshotStore.load().isEmpty());

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    content.findViewById(R.id.tvClearHistory).performClick()
            );
            assertEquals(1, clearHistoryCount.get());
            assertEquals(
                    View.GONE,
                    content.findViewById(R.id.historySection).getVisibility()
            );
            assertEquals(
                    View.GONE,
                    content.findViewById(R.id.finderSnapshotSection).getVisibility()
            );
        } finally {
            snapshotStore.clear();
            preferences.edit().clear().commit();
        }
    }
}
