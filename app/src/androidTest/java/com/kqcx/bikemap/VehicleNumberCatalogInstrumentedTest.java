package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class VehicleNumberCatalogInstrumentedTest {
    @Test
    public void prioritizesBundledExactThenLearnedExactThenSeed() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(
                BaseActivity.PREFS_APP,
                Context.MODE_PRIVATE
        );
        String originalLearned = preferences.getString(
                LearnedVehicleNumberStore.PREF_KEY,
                null
        );
        try {
            preferences.edit().remove(LearnedVehicleNumberStore.PREF_KEY).commit();
            LearnedVehicleNumberStore learnedStore =
                    new LearnedVehicleNumberStore(preferences);
            learnedStore.remember("990001234", "自学习测试校区");
            learnedStore.remember("990000777", "自学习测试校区");

            VehicleNumberCatalog catalog = VehicleNumberCatalog.fromJson(
                    "{"
                            + "\"sites\":["
                            + "{\"id\":7,\"name\":\"号段测试校区\","
                            + "\"series\":[\"990000\",\"990001\"]},"
                            + "{\"id\":8,\"name\":\"自学习测试校区\","
                            + "\"series\":[\"880000\"]}"
                            + "],"
                            + "\"numbers\":{"
                            + "\"990000777\":[7,\"990000\"]"
                            + "},"
                            + "\"seed\":{"
                            + "\"990000\":[7],"
                            + "\"990001\":[7],"
                            + "\"880000\":[8]"
                            + "}"
                            + "}",
                    context
            );

            VehicleNumberCatalog.NumberInfo learned =
                    catalog.find("990001234");
            assertNotNull(learned);
            assertEquals(8, learned.siteId);
            assertEquals("自学习测试校区", learned.siteName);

            VehicleNumberCatalog.NumberInfo exact =
                    catalog.find("990000777");
            assertNotNull(exact);
            assertEquals(7, exact.siteId);
            assertEquals("号段测试校区", exact.siteName);

            VehicleNumberCatalog.NumberInfo seedOnly =
                    catalog.find("990001999");
            assertNotNull(seedOnly);
            assertEquals(7, seedOnly.siteId);
            assertEquals("号段测试校区", seedOnly.siteName);
        } finally {
            if (originalLearned == null) {
                preferences.edit()
                        .remove(LearnedVehicleNumberStore.PREF_KEY)
                        .commit();
            } else {
                preferences.edit()
                        .putString(
                                LearnedVehicleNumberStore.PREF_KEY,
                                originalLearned
                        )
                        .commit();
            }
        }
    }
}
