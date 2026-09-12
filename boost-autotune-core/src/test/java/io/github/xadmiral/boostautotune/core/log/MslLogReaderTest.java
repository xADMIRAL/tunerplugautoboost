package io.github.xadmiral.boostautotune.core.log;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class MslLogReaderTest {
    @Test
    void readsMslWithPreambleAndUnitsRow() throws IOException {
        String msl = "\"MS3 Format DM00.22f\"\n"
                + "\"Capture Date: Mon Feb 03 18:24:43 VLAT 2025\"\n"
                + "Time\tRPM\tMAP\tTPS\tCLT\tBoost duty\tBoost target 1\tGear\n"
                + "s\tRPM\tkPa\t%\t°C\t%\tkPa\t\n"
                + "0.000\t2500\t95.3\t12.0\t88.1\t0\t100\t3\n"
                + "0.050\t3100\t150.2\t100.0\t88.2\t45\t160\t3\n"
                + "0.100\t3200\t155.9\t100.0\t88.2\t47\t160\t3\n";
        MslLogReader.Result r = MslLogReader.read(new StringReader(msl), LogColumnMapping.ms3());
        assertEquals(3, r.samples.size());
        assertEquals(3100, r.samples.get(1).rpm, 1e-9);
        assertEquals(150.2, r.samples.get(1).map, 1e-9);
        assertEquals(160, r.samples.get(1).target, 1e-9);
        assertEquals(45, r.samples.get(1).duty, 1e-9);
        assertEquals(3, r.samples.get(1).gear);
        assertEquals(0.05, r.samples.get(1).timeSec, 1e-9);
        assertTrue(r.warnings.isEmpty());
        assertEquals(8, r.columns.size());
    }

    @Test
    void readsCsvWithMissingOptionalColumns() throws IOException {
        String csv = "Time,RPM,MAP,TPS\n0,2000,100,10\n0.1,2100,101,12\n";
        MslLogReader.Result r = MslLogReader.read(new StringReader(csv), LogColumnMapping.ms3());
        assertEquals(2, r.samples.size());
        assertTrue(Double.isNaN(r.samples.get(0).target));
        assertEquals(2, r.warnings.size());
    }

    @Test
    void failsWithoutRequiredColumns() {
        assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
            public void execute() throws Throwable {
                MslLogReader.read(new StringReader("Time,RPM\n0,1\n"), LogColumnMapping.ms3());
            }
        });
    }
}
