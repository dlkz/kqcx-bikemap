package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FinderSnapshotStoreInstrumentedTest {
    @Test
    public void clearGenerationIsSharedAcrossStoreInstances() {
        Context context = ApplicationProvider.getApplicationContext();
        String preferencesName = "finder_snapshots_instrumented_"
                + System.nanoTime();
        try {
            FinderSnapshotStore first = new FinderSnapshotStore(
                    context,
                    preferencesName
            );
            FinderSnapshotStore second = new FinderSnapshotStore(
                    context,
                    preferencesName
            );

            long before = first.clearGeneration();
            first.clear();
            assertEquals(before + 1L, second.clearGeneration());
            Bike bike = new Bike(
                    "900000001",
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
            assertTrue(second.saveInitial(bike, "测试校区", 2_000L));
            assertEquals(before + 1L, first.clearGeneration());
            second.clear();
            assertEquals(before + 2L, first.clearGeneration());
        } finally {
            context.deleteSharedPreferences(preferencesName);
        }
    }
}
