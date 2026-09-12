package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class EcuAdapterTest {
    @Test
    void readsAndWritesTablesRoundTrip() throws Exception {
        SimEcuPort port = new SimEcuPort();
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        EcuAdapter a = new EcuAdapter(port, b);
        List<String> warnings = new ArrayList<String>();
        EcuState s = a.read(warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(8, s.targetTable.width());
        assertEquals(1500, s.targetTable.xAxis().min(), 1e-9);
        assertEquals(100, s.biasTable.yAxis().min(), 1e-9);
        assertEquals(100, s.pid.p, 1e-9);
        assertFalse(s.closedLoop);
        Grid t = s.targetTable.copy();
        t.set(2, 5, 155);
        s.targetTable = t;
        s.closedLoop = true;
        a.write(s, true, true, true);
        double[][] raw = port.readArray2D(SimEcuPort.CONFIG, b.targetTable);
        assertEquals(155, raw[5][2], 1e-9);
        assertEquals("Closed-loop", port.readOption(SimEcuPort.CONFIG, b.modeParam));
        assertEquals("Advanced Mode", port.readOption(SimEcuPort.CONFIG, b.closedLoopExtraParam));
        assertEquals(210, a.readOverboostLimit(), 1e-9);
    }

    @Test
    void transposedLayoutIsHonoured() {
        TableLayout l = new TableLayout(3, 2, false); // raw rows = X (3), cols = Y (2)
        double[][] raw = {{1, 2}, {3, 4}, {5, 6}};
        double[][] yx = l.toYX(raw);
        assertEquals(2, yx.length);
        assertEquals(3, yx[0].length);
        assertEquals(5, yx[0][2], 1e-9);
        Grid g = new Grid(io.github.xadmiral.boostautotune.core.model.Axis.of(1, 2, 3),
                io.github.xadmiral.boostautotune.core.model.Axis.of(10, 20), yx);
        double[][] back = l.fromGrid(g);
        assertArrayEquals(raw[2], back[2], 1e-9);
    }

    @Test
    void bindingRoundTripsThroughProperties() {
        EcuBinding b = EcuPresets.create(EcuPresets.SPEEDUINO);
        b.boostCutMask = 5;
        b.orientation = TableOrientation.ROWS_ARE_X;
        Properties p = new Properties();
        b.store(p, "x.");
        EcuBinding c = new EcuBinding();
        c.load(p, "x.");
        assertEquals("boostTable", c.targetTable);
        assertEquals(5, c.boostCutMask);
        assertEquals(TableOrientation.ROWS_ARE_X, c.orientation);
        assertFalse(c.hasOpenLoopTable());
        assertTrue(c.hasBiasTable());
    }

    @Test
    void presetDetectionUsesSignatureThenNames() {
        assertEquals(EcuPresets.STEALTH_PCM, EcuPresets.detect("MS3 Format DM00.22f", null, null));
        assertEquals(EcuPresets.MS3, EcuPresets.detect("MS3 Format 1.5.2", null, null));
        assertEquals(EcuPresets.SPEEDUINO, EcuPresets.detect("speeduino 202402", null, null));
        assertEquals(EcuPresets.RUSEFI, EcuPresets.detect("rusEFI master.2024", null, null));
        SimEcuPort port = new SimEcuPort();
        assertEquals(EcuPresets.STEALTH_PCM, EcuPresets.detect("unknown", port.parameterNames(SimEcuPort.CONFIG),
                port.channelNames(SimEcuPort.CONFIG)));
        assertEquals(EcuPresets.CUSTOM, EcuPresets.detect("unknown", new ArrayList<String>(), new ArrayList<String>()));
    }

    @Test
    void validateReportsMissingNames() {
        SimEcuPort port = new SimEcuPort();
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        assertTrue(b.validate(port.channelNames(SimEcuPort.CONFIG), port.parameterNames(SimEcuPort.CONFIG)).isEmpty());
        b.targetTable = "nope";
        b.dutyChannel = "";
        List<String> problems = b.validate(port.channelNames(SimEcuPort.CONFIG), port.parameterNames(SimEcuPort.CONFIG));
        assertEquals(2, problems.size(), problems.toString());
    }
}
