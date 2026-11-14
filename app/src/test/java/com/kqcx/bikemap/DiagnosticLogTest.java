package com.kqcx.bikemap;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class DiagnosticLogTest {
    @Test
    public void countsOnlyTimestampedDetailRecords() throws Exception {
        File file = File.createTempFile("bikemap-diagnostic", ".log");
        try {
            String content = "11-09 12:00:00.000 D FinderLoc: detail\n"
                    + "11-09 12:00:00.001 I FinderLoc: event\n"
                    + "11-09 12:00:00.002 W FinderLoc: warning\n"
                    + "    at D.example(Source.java:1)\n"
                    + "11-09 12:00:00.003 D FinderBle: band=very_near\n";
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));

            assertEquals(2, DiagnosticLog.countDetailRecords(file));
        } finally {
            file.delete();
        }
    }
}
