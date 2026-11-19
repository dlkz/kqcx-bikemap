package com.kqcx.bikemap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class DiagnosticLogInstrumentedTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        DiagnosticLog.init(context);
        assertTrue(DiagnosticLog.clearAll(context));
    }

    @After
    public void tearDown() {
        DiagnosticLog.clearAll(context);
    }

    @Test
    public void clearInvalidatesQueuedExport() {
        long staleGeneration = DiagnosticLog.captureExportGeneration();
        assertTrue(DiagnosticLog.clearAll(context));

        try {
            DiagnosticLog.exportReport(context, staleGeneration);
            fail("A queued export must not start after clear");
        } catch (DiagnosticLog.ExportCancelledException expected) {
            // Expected.
        } catch (Exception exception) {
            fail("Unexpected export failure: " + exception);
        }
        assertFalse(hasExportFiles());
    }

    @Test
    public void clearDeletesCompletedExport() throws Exception {
        long generation = DiagnosticLog.captureExportGeneration();
        File report = DiagnosticLog.exportReport(context, generation);
        assertTrue(report.isFile());
        assertTrue(DiagnosticLog.isExportCurrent(generation, report));

        assertTrue(DiagnosticLog.clearAll(context));
        assertFalse(report.exists());
        assertFalse(DiagnosticLog.isExportCurrent(generation, report));
    }

    @Test
    public void exportStartedAfterClearRemainsValid() throws Exception {
        assertTrue(DiagnosticLog.clearAll(context));
        long generation = DiagnosticLog.captureExportGeneration();
        File report = DiagnosticLog.exportReport(context, generation);

        assertTrue(report.isFile());
        assertTrue(DiagnosticLog.isExportCurrent(generation, report));
    }

    @Test
    public void concurrentClearAndExportCannotLeaveReportBehind() throws Exception {
        long generation = DiagnosticLog.captureExportGeneration();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<File> export = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return DiagnosticLog.exportReport(context, generation);
                } catch (DiagnosticLog.ExportCancelledException expected) {
                    return null;
                }
            });
            Future<Boolean> clear = executor.submit(() -> {
                ready.countDown();
                start.await();
                return DiagnosticLog.clearAll(context);
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            assertTrue(clear.get(10, TimeUnit.SECONDS));
            File report = export.get(10, TimeUnit.SECONDS);
            if (report != null) {
                assertFalse(report.exists());
            }
            assertFalse(hasExportFiles());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void reportsCreatedInSameSecondUseDifferentFiles() throws Exception {
        long generation = DiagnosticLog.captureExportGeneration();
        File first = DiagnosticLog.exportReport(context, generation);
        File second = DiagnosticLog.exportReport(context, generation);
        try {
            assertNotNull(first);
            assertNotNull(second);
            assertFalse(first.getAbsolutePath().equals(second.getAbsolutePath()));
            assertTrue(first.isFile());
            assertTrue(second.isFile());
        } finally {
            first.delete();
            second.delete();
        }
    }

    private boolean hasExportFiles() {
        File[] files = new File(context.getCacheDir(), "diagnose-export").listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile()
                    && name.startsWith("bikemap-log-")
                    && name.endsWith(".txt")) {
                return true;
            }
        }
        return false;
    }
}
