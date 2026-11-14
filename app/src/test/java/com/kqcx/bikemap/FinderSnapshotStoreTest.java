package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FinderSnapshotStoreTest {
    @Test
    public void replacesDuplicateNumberAndMovesItToFront() {
        FinderSnapshotStore.Snapshot first = snapshot("900000002", 1L);
        FinderSnapshotStore.Snapshot second = snapshot("900000003", 2L);
        FinderSnapshotStore.Snapshot replacement = snapshot("900000002", 3L);

        List<FinderSnapshotStore.Snapshot> merged = FinderSnapshotStore.mergeForStorage(
                Arrays.asList(first, second),
                replacement
        );

        assertEquals(2, merged.size());
        assertEquals("900000002", merged.get(0).number);
        assertEquals(3L, merged.get(0).capturedAtMs);
        assertEquals("900000003", merged.get(1).number);
    }

    @Test
    public void keepsOnlyThreeMostRecentSnapshots() {
        List<FinderSnapshotStore.Snapshot> existing = new ArrayList<>();
        existing.add(snapshot("900000004", 3L));
        existing.add(snapshot("900000003", 2L));
        existing.add(snapshot("900000002", 1L));

        List<FinderSnapshotStore.Snapshot> merged = FinderSnapshotStore.mergeForStorage(
                existing,
                snapshot("900000005", 4L)
        );

        assertEquals(3, merged.size());
        assertEquals("900000005", merged.get(0).number);
        assertEquals("900000004", merged.get(1).number);
        assertEquals("900000003", merged.get(2).number);
    }

    @Test
    public void repeatedCaptureKeepsPreviouslyResolvedBluetoothIdentity() {
        FinderSnapshotStore.Snapshot existing = snapshot("900000002", 1L);
        FinderSnapshotStore.Snapshot withoutBluetooth = new FinderSnapshotStore.Snapshot(
                "900000002",
                1.0,
                1.0,
                2_000L,
                2L,
                "",
                ""
        );

        List<FinderSnapshotStore.Snapshot> merged = FinderSnapshotStore.mergeForStorage(
                Arrays.asList(existing),
                withoutBluetooth
        );

        assertEquals(1, merged.size());
        assertEquals("xlc-900000001", merged.get(0).bluetoothName);
        assertEquals("测试校区", merged.get(0).siteName);
        assertEquals(2L, merged.get(0).capturedAtMs);
    }

    @Test
    public void unknownQueryTimeCannotOverwriteKnownSnapshotLocation() {
        assertFalse(VehicleLocationFreshness.canReplace(
                2_000L,
                true,
                0L
        ));
        assertTrue(VehicleLocationFreshness.canReplace(
                2_000L,
                true,
                2_000L
        ));
        assertFalse(VehicleLocationFreshness.canReplace(
                2_000L,
                true,
                1_999L
        ));
        assertTrue(VehicleLocationFreshness.canReplace(
                0L,
                true,
                0L
        ));
        assertFalse(VehicleLocationFreshness.canReplace(
                2_000L,
                false,
                3_000L
        ));
    }

    @Test
    public void staleCaptureDoesNotOverwriteNewerSnapshotLocation() {
        FinderSnapshotStore.Snapshot existing = new FinderSnapshotStore.Snapshot(
                "900000002",
                1.0,
                1.0,
                2_000L,
                1L,
                "xlc-900000001",
                "测试校区"
        );
        FinderSnapshotStore.Snapshot stale = new FinderSnapshotStore.Snapshot(
                "900000002",
                1.02,
                1.02,
                1_000L,
                2L,
                "",
                ""
        );

        List<FinderSnapshotStore.Snapshot> merged = FinderSnapshotStore.mergeForStorage(
                Arrays.asList(existing),
                stale
        );

        assertEquals(1.0, merged.get(0).latitude, 0.000001);
        assertEquals(1.0, merged.get(0).longitude, 0.000001);
        assertEquals(2_000L, merged.get(0).lastUploadAtMs);
        assertEquals(2L, merged.get(0).capturedAtMs);
    }

    @Test
    public void missingCaptureIsNotRecreatedByDelayedLookup() {
        assertNull(FinderSnapshotStore.updateMatchingCapture(
                new ArrayList<>(),
                "900000002",
                2L,
                new Bike(
                        "900000002",
                        1.02,
                        1.02,
                        80,
                        R.string.bike_status_available,
                        "",
                        "",
                        "",
                        0f,
                        true,
                        true,
                        3_000L,
                        "xlc-900000001"
                )
        ));
    }

    @Test
    public void matchingCaptureUsesNewerQueriedLocation() {
        FinderSnapshotStore.Snapshot existing = snapshot("900000002", 2L);
        Bike resolved = new Bike(
                "900000002",
                1.02,
                1.02,
                80,
                R.string.bike_status_available,
                "",
                "测试校区",
                "",
                0f,
                true,
                true,
                3_000L,
                "xlc-900000001"
        );

        List<FinderSnapshotStore.Snapshot> updated =
                FinderSnapshotStore.updateMatchingCapture(
                        Arrays.asList(existing),
                        "900000002",
                        2L,
                        resolved
                );

        assertEquals(1, updated.size());
        assertEquals(1.02, updated.get(0).latitude, 0.000001);
        assertEquals(1.02, updated.get(0).longitude, 0.000001);
        assertEquals(3_000L, updated.get(0).lastUploadAtMs);
        assertEquals(2L, updated.get(0).capturedAtMs);
    }

    @Test
    public void delayedCaptureStaysInCaptureTimeOrder() {
        List<FinderSnapshotStore.Snapshot> merged = FinderSnapshotStore.mergeForStorage(
                Arrays.asList(
                        snapshot("900000004", 3L),
                        snapshot("900000003", 2L)
                ),
                snapshot("900000002", 1L)
        );

        assertEquals("900000004", merged.get(0).number);
        assertEquals("900000003", merged.get(1).number);
        assertEquals("900000002", merged.get(2).number);
    }

    @Test
    public void delayedOldCaptureCannotMoveNewerCaptureBackward() {
        FinderSnapshotStore.Snapshot newer = new FinderSnapshotStore.Snapshot(
                "900000002",
                1.0,
                1.0,
                2_000L,
                3L,
                "xlc-newer",
                "测试校区"
        );
        FinderSnapshotStore.Snapshot older = new FinderSnapshotStore.Snapshot(
                "900000002",
                1.02,
                1.02,
                3_000L,
                1L,
                "",
                ""
        );

        List<FinderSnapshotStore.Snapshot> merged = FinderSnapshotStore.mergeForStorage(
                Arrays.asList(newer),
                older
        );

        assertEquals(3L, merged.get(0).capturedAtMs);
        assertEquals("xlc-newer", merged.get(0).bluetoothName);
        assertEquals("测试校区", merged.get(0).siteName);
    }

    private static FinderSnapshotStore.Snapshot snapshot(String number, long capturedAtMs) {
        return new FinderSnapshotStore.Snapshot(
                number,
                1.0,
                1.0,
                1_000L,
                capturedAtMs,
                "xlc-900000001",
                "测试校区"
        );
    }
}
