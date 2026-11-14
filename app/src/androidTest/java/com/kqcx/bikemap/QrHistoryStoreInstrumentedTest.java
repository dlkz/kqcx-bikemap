package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class QrHistoryStoreInstrumentedTest {
    @Test
    public void keepsThreeNewestEntriesAndHonorsRetention() {
        Context context = ApplicationProvider.getApplicationContext();
        String preferencesName = "qr_history_test_" + System.nanoTime();
        SharedPreferences preferences = context.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
        );
        preferences.edit().clear().commit();
        try {
            QrHistoryStore store = new QrHistoryStore(preferences);
            VehicleNumberCatalog catalog = VehicleNumberCatalog.empty();

            store.remember("900000001", "一校区", catalog);
            store.remember("900000002", "二校区", catalog);
            store.remember("900000003", "三校区", catalog);
            store.remember("900000004", "四校区", catalog);

            List<QrHistoryStore.Entry> history = new QrHistoryStore(preferences)
                    .load(catalog);
            assertEquals(3, history.size());
            assertEquals("900000004", history.get(0).number);
            assertEquals("900000003", history.get(1).number);
            assertEquals("900000002", history.get(2).number);

            store.remember("900000003", "三校区", catalog);
            history = store.load(catalog);
            assertEquals("900000003", history.get(0).number);
            assertEquals("900000004", history.get(1).number);

            store.clear();
            assertTrue(store.load(catalog).isEmpty());

            store.setRetentionEnabled(false);
            assertFalse(store.retentionEnabled());
            assertTrue(store.load(catalog).isEmpty());
        } finally {
            preferences.edit().clear().commit();
        }
    }
}
