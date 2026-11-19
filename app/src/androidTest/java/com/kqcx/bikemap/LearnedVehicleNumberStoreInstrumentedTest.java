package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LearnedVehicleNumberStoreInstrumentedTest {
    @Test
    public void delayedLookupCannotRepopulateClearedStore() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(
                "learned_number_test_" + System.nanoTime(),
                Context.MODE_PRIVATE
        );
        try {
            LearnedVehicleNumberStore store =
                    new LearnedVehicleNumberStore(preferences);
            long staleGeneration = store.clearGeneration();

            store.clear();
            store.rememberIfCurrent(staleGeneration, "900000001", "测试校区");
            assertTrue(store.entries().isEmpty());

            store.rememberIfCurrent(
                    store.clearGeneration(),
                    "900000001",
                    "测试校区"
            );
            assertEquals(1, store.entries().size());
        } finally {
            preferences.edit().clear().commit();
        }
    }
}
