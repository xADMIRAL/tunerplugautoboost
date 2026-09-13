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
    void vvtAndSparkRoundTrip() throws Exception {
        SimEcuPort port = new SimEcuPort();
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        EcuAdapter a = new EcuAdapter(port, b);
        Grid vvt = a.readVvtTable();
        assertEquals(8, vvt.width());
        assertEquals(45, vvt.get(1, 4), 1e-9); // 1000 rpm / 150 kPa in the base tune
        Grid v2 = vvt.copy();
        v2.set(3, 5, 33);
        a.writeVvtTable(v2);
        assertEquals(33, port.readArray2D(SimEcuPort.CONFIG, b.vvtTable)[5][3], 1e-9);
        assertEquals(70, a.readVvtGains().p, 1e-9);
        a.writeVvtGains(new io.github.xadmiral.boostautotune.core.model.PidGains(60, 10, 30));
        assertEquals(60, port.readScalar(SimEcuPort.CONFIG, b.vvtPidP), 1e-9);
        Grid spark = a.readSparkTable();
        assertEquals(16, spark.width());
        assertEquals(16, spark.height());
        assertEquals(500, spark.xAxis().min(), 1e-9);
        assertEquals(280, spark.yAxis().max(), 1e-9);
        Grid s2 = spark.copy();
        s2.set(8, 9, 12.5);
        a.writeSparkTable(s2);
        assertEquals(12.5, port.readArray2D(SimEcuPort.CONFIG, b.sparkTable)[9][8], 1e-9);
        assertTrue(b.validateVvt(port.channelNames(SimEcuPort.CONFIG), port.parameterNames(SimEcuPort.CONFIG), true).isEmpty());
        assertTrue(b.validateIgnition(port.channelNames(SimEcuPort.CONFIG), port.parameterNames(SimEcuPort.CONFIG)).isEmpty());
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

    @Test
    void antilagRoundTripAndGenericAccess() throws Exception {
        SimEcuPort port = new SimEcuPort();
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        EcuAdapter a = new EcuAdapter(port, b);
        assertTrue(b.validateAntilag(port.channelNames(SimEcuPort.CONFIG), port.parameterNames(SimEcuPort.CONFIG)).isEmpty());
        Grid t = a.readAlsTiming();
        assertEquals(6, t.width());
        assertEquals(6, t.height());
        assertEquals(1300, t.xAxis().min(), 1e-9);
        assertEquals(20, t.yAxis().max(), 1e-9);
        Grid mod = t.copy();
        mod.set(2, 0, -20);
        a.writeAlsTiming(mod);
        assertEquals(-20, port.readArray2D(SimEcuPort.CONFIG, b.alsTimingTable)[0][2], 1e-9);
        assertEquals("als_iac_steps", a.alsAirParam());
        assertEquals(150, a.readAlsAir(), 1e-9);
        a.writeAlsAir(120);
        assertEquals(120, port.readScalar(SimEcuPort.CONFIG, b.alsAirStepsParam), 1e-9);
        assertEquals("Off", a.readAlsEnable());
        a.writeAlsEnable("Always ON");
        assertEquals("Always ON", port.readOption(SimEcuPort.CONFIG, b.alsEnableParam));
        // generic access used by the drift presets
        assertEquals("30", a.readAny("als_maxtps"));
        a.writeAny("als_maxtps", "12");
        assertEquals(12, port.readScalar(SimEcuPort.CONFIG, "als_maxtps"), 1e-9);
        a.writeAny("als_opt_sc", "On");
        assertEquals("On", a.readAny("als_opt_sc"));
        a.writeAny(b.alsXBins, "2000 3000 4000 5000 6000 7000");
        assertEquals(2000, a.readAlsTiming().xAxis().min(), 1e-9);
        assertEquals(7000, a.readAlsTiming().xAxis().max(), 1e-9);
        a.writeAny(b.alsTimingTable, "-16");
        for (double[] row : port.readArray2D(SimEcuPort.CONFIG, b.alsTimingTable)) {
            for (double v : row) {
                assertEquals(-16, v, 1e-9);
            }
        }
        assertTrue(a.readAny(b.alsTimingTable).startsWith("-16 -16"));
        assertTrue(a.hasParameter("flats_arm"));
        assertFalse(a.hasParameter("no_such_param"));
        // a PWM idle valve switches the air knob to the duty parameter
        port.writeOption(SimEcuPort.CONFIG, b.idleTypeParam, "PWM valve (2 or 3 wire)");
        assertEquals("als_iac_duty", a.alsAirParam());
    }
}
