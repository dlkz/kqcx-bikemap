package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class VehicleNumberInputResolverInstrumentedTest {
    @Test
    public void resolvesFullSuffixLoadingDirectAndCandidateInputs() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        VehicleNumberCatalog catalog = VehicleNumberCatalog.fromJson(
                "{"
                        + "\"sites\":[{"
                        + "\"id\":7,"
                        + "\"name\":\"测试校区\","
                        + "\"series\":[\"990000\",\"990001\",\"990002\"]"
                        + "}],"
                        + "\"numbers\":{"
                        + "\"990000151\":[7,\"990000\"],"
                        + "\"990001331\":[7,\"990001\"],"
                        + "\"990002331\":[7,\"990002\"]"
                        + "},"
                        + "\"seed\":{}"
                        + "}",
                context
        );
        assertNotNull(catalog.find("990000151"));
        assertNull(catalog.find("999999999"));

        VehicleNumberInputResolver loadedResolver =
                new VehicleNumberInputResolver(catalog, true, 7, "990001");
        VehicleNumberInputResolver.Result resolved =
                loadedResolver.resolve("990000151");
        assertEquals(VehicleNumberInputResolver.Status.RESOLVED, resolved.status);
        assertEquals("990000151", resolved.number);

        loadedResolver.select(7, "990001");
        resolved = loadedResolver.resolve("331");
        assertEquals(VehicleNumberInputResolver.Status.RESOLVED, resolved.status);
        assertEquals("990001331", resolved.number);

        loadedResolver.select(7, "990000");
        VehicleNumberInputResolver.Result candidates = loadedResolver.resolve("331");
        assertEquals(VehicleNumberInputResolver.Status.CANDIDATES, candidates.status);
        assertEquals(2, candidates.candidates.size());

        VehicleNumberInputResolver.Result direct = loadedResolver.resolve("999999999");
        assertEquals(VehicleNumberInputResolver.Status.DIRECT, direct.status);
        assertEquals("999999999", direct.number);

        VehicleNumberInputResolver.Result incomplete = loadedResolver.resolve("12");
        assertEquals(VehicleNumberInputResolver.Status.INCOMPLETE, incomplete.status);

        VehicleNumberInputResolver loadingResolver = new VehicleNumberInputResolver(
                VehicleNumberCatalog.empty(),
                false,
                -1,
                ""
        );
        VehicleNumberInputResolver.Result loading = loadingResolver.resolve("331");
        assertEquals(VehicleNumberInputResolver.Status.CATALOG_LOADING, loading.status);
        direct = loadingResolver.resolve("999999999");
        assertEquals(VehicleNumberInputResolver.Status.DIRECT, direct.status);

        VehicleNumberInputResolver unavailableResolver =
                new VehicleNumberInputResolver(
                        VehicleNumberCatalog.empty(),
                        true,
                        -1,
                        ""
                );
        VehicleNumberInputResolver.Result unavailable =
                unavailableResolver.resolve("331");
        assertEquals(
                VehicleNumberInputResolver.Status.CATALOG_UNAVAILABLE,
                unavailable.status
        );
        direct = unavailableResolver.resolve("999999999");
        assertEquals(VehicleNumberInputResolver.Status.DIRECT, direct.status);
        VehicleNumberInputResolver.Result stillIncomplete =
                unavailableResolver.resolve("12");
        assertEquals(VehicleNumberInputResolver.Status.INCOMPLETE, stillIncomplete.status);
    }
}
