package io.github.xadmiral.boostautotune.plugin;

import io.github.xadmiral.boostautotune.core.als.AlsConfig;
import io.github.xadmiral.boostautotune.core.als.AlsSession;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.mode.AntilagPresets;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.sweep.SweepConfig;
import io.github.xadmiral.boostautotune.core.sweep.SweepReport;
import io.github.xadmiral.boostautotune.core.vvt.VvtPidConfig;
import io.github.xadmiral.boostautotune.core.vvt.VvtPidSession;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPresets;
import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import io.github.xadmiral.boostautotune.plugin.mode.TuneMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** VVT sweep, ignition sweep and VVT PID modes end to end through the controller and the demo ECU. */
class ModesDemoIntegrationTest {

    private static final class Collector implements TuneController.Listener {
        final List<String> log = new ArrayList<String>();
        volatile Object last;

        public void log(String line) { log.add(line); }
        public void stateChanged() { }
        public void sample(Sample s, SampleState state) { }
        public void reportReady(Object report) { last = report; }
    }

    private static void waitForStream(SimEcuPort port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30000;
        Thread.sleep(50);
        while (port.isPulling() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(port.isPulling(), "simulated stream did not finish");
    }

    private static EcuBinding demoBinding() {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        b.timeChannel = "seconds";
        return b;
    }

    @Test
    void vvtSweepOptimisesTheCamTable() throws Exception {
        SimEcuPort port = new SimEcuPort();
        Collector listener = new Collector();
        TuneController ctl = new TuneController(listener);
        ctl.setPort(port);
        SweepConfig cfg = SweepConfig.vvtDefaults();
        cfg.minRpm = 2500;
        cfg.maxRpm = 6500;
        cfg.gear = 3;
        cfg.autoEndRunIdleSec = 0;
        EcuBinding b = demoBinding();
        ctl.startSweepSession(cfg, b);
        assertEquals(TuneMode.VVT_SWEEP, ctl.mode());
        double[][] before = port.readArray2D(SimEcuPort.CONFIG, b.vvtTable);
        int runs = 0;
        while (ctl.state() != SessionState.DONE && runs < 8) {
            ctl.writePlanToEcu();
            ctl.startRun();
            port.simulatePull(3, 1000);
            waitForStream(port);
            port.simulatePull(3, 1000);
            waitForStream(port);
            assertNotEquals(SessionState.ABORTED, ctl.state(), ctl.abortReason());
            SweepReport r = (SweepReport) ctl.endRun();
            assertSame(r, listener.last);
            ctl.applyAndPrepareNext();
            runs++;
        }
        assertEquals(SessionState.DONE, ctl.state(), String.join("\n", listener.log));
        assertEquals(5, runs);
        double[][] after = port.readArray2D(SimEcuPort.CONFIG, b.vvtTable);
        // low load rows untouched, some WOT-row cell changed
        assertArrayEquals(before[0], after[0], 1e-9);
        boolean changed = false;
        for (int yi = 2; yi < 8; yi++) {
            for (int xi = 0; xi < 8; xi++) {
                if (Math.abs(before[yi][xi] - after[yi][xi]) > 0.5) {
                    changed = true;
                }
            }
        }
        assertTrue(changed, "expected the sweep to change WOT rows");
        assertFalse(ctl.tables().isEmpty());
        assertTrue(ctl.analysisText().contains("Decisions per RPM bin"));
        ctl.restoreOriginal();
        assertArrayEquals(before[4], port.readArray2D(SimEcuPort.CONFIG, b.vvtTable)[4], 1e-9);
    }

    @Test
    void ignitionSweepStaysWithinLimitsAndUndoWorks() throws Exception {
        SimEcuPort port = new SimEcuPort();
        Collector listener = new Collector();
        TuneController ctl = new TuneController(listener);
        ctl.setPort(port);
        SweepConfig cfg = SweepConfig.ignitionDefaults();
        cfg.passes = 1;
        cfg.minRpm = 2500;
        cfg.maxRpm = 6500;
        cfg.gear = 3;
        cfg.autoEndRunIdleSec = 0;
        EcuBinding b = demoBinding();
        ctl.startSweepSession(cfg, b);
        assertEquals(TuneMode.IGNITION_SWEEP, ctl.mode());
        double[][] before = port.readArray2D(SimEcuPort.CONFIG, b.sparkTable);
        int runs = 0;
        while (ctl.state() != SessionState.DONE && runs < 8) {
            ctl.writePlanToEcu();
            // the written table never exceeds original + 4 degrees anywhere
            double[][] now = port.readArray2D(SimEcuPort.CONFIG, b.sparkTable);
            for (int yi = 0; yi < 16; yi++) {
                for (int xi = 0; xi < 16; xi++) {
                    assertTrue(now[yi][xi] <= before[yi][xi] + 4.0001, "cell " + yi + "," + xi);
                }
            }
            ctl.startRun();
            port.simulatePull(3, 1000);
            waitForStream(port);
            port.simulatePull(3, 1000);
            waitForStream(port);
            assertNotEquals(SessionState.ABORTED, ctl.state(), ctl.abortReason());
            ctl.endRun();
            ctl.applyAndPrepareNext();
            runs++;
        }
        assertEquals(SessionState.DONE, ctl.state(), String.join("\n", listener.log));
        double[][] after = port.readArray2D(SimEcuPort.CONFIG, b.sparkTable);
        assertArrayEquals(before[0], after[0], 1e-9); // 20 kPa row untouched
        boolean advanced = false;
        for (int yi = 6; yi < 16; yi++) {
            for (int xi = 0; xi < 16; xi++) {
                assertTrue(after[yi][xi] <= before[yi][xi] + 4.0001);
                if (after[yi][xi] > before[yi][xi] + 1) {
                    advanced = true;
                }
            }
        }
        assertTrue(advanced, "expected some advance in the boost rows");
        ctl.restoreOriginal();
        assertArrayEquals(before[8], port.readArray2D(SimEcuPort.CONFIG, b.sparkTable)[8], 1e-9);
    }

    @Test
    void vvtPidSessionSettlesWithSimulatedDriving() throws Exception {
        SimEcuPort port = new SimEcuPort();
        Collector listener = new Collector();
        TuneController ctl = new TuneController(listener);
        ctl.setPort(port);
        EcuBinding b = demoBinding();
        port.writeScalar(SimEcuPort.CONFIG, b.vvtPidP, 200);
        port.writeScalar(SimEcuPort.CONFIG, b.vvtPidI, 120);
        port.writeScalar(SimEcuPort.CONFIG, b.vvtPidD, 0);
        VvtPidConfig cfg = new VvtPidConfig();
        ctl.startVvtPidSession(cfg, b);
        assertEquals(TuneMode.VVT_PID, ctl.mode());
        int runs = 0;
        while (ctl.state() != SessionState.DONE && runs < 10) {
            ctl.writePlanToEcu();
            ctl.startRun();
            port.simulateDrive(60, 2000);
            waitForStream(port);
            VvtPidSession.Report r = (VvtPidSession.Report) ctl.endRun();
            assertNotNull(r.metrics);
            ctl.applyAndPrepareNext();
            runs++;
        }
        assertEquals(SessionState.DONE, ctl.state(), String.join("\n", listener.log));
        assertTrue(port.readScalar(SimEcuPort.CONFIG, b.vvtPidP) < 200);
        ctl.restoreOriginal();
        assertEquals(200, port.readScalar(SimEcuPort.CONFIG, b.vvtPidP), 1e-9);
    }

    @Test
    void antilagSessionRetardsTimingToTheOffThrottleTarget() throws Exception {
        SimEcuPort port = new SimEcuPort();
        Collector listener = new Collector();
        TuneController ctl = new TuneController(listener);
        ctl.setPort(port);
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        b.timeChannel = "seconds";
        // a drift preset switches the anti-lag on and sets its axes / thresholds
        EcuAdapter setup = new EcuAdapter(port, b);
        int written = 0;
        for (AntilagPresets.Setting s : AntilagPresets.create(AntilagPresets.DRIFT_MILD)) {
            if (setup.hasParameter(s.param)) {
                setup.writeAny(s.param, s.value);
                written++;
            }
        }
        assertTrue(written >= 20, "preset parameters written: " + written);
        assertEquals("Always ON", port.readOption(SimEcuPort.CONFIG, b.alsEnableParam));
        assertEquals(2000, port.readArray2D(SimEcuPort.CONFIG, b.alsXBins)[0][0], 1e-9);
        // start from a weak anti-lag so the autotune has work to do
        setup.writeAny(b.alsTimingTable, "-8");
        setup.writeAny(b.alsAirStepsParam, "60");
        AlsConfig cfg = new AlsConfig();
        cfg.targetKpa = 130;
        cfg.autoEndRunIdleSec = 0;
        ctl.startAntilagSession(cfg, b);
        assertEquals(TuneMode.ANTILAG, ctl.mode());
        int runs = 0;
        while (ctl.state() != SessionState.DONE && runs < 12) {
            ctl.writePlanToEcu();
            ctl.startRun();
            port.simulateAntilag(3, 1000);
            waitForStream(port);
            port.coolDown(60);
            assertNotEquals(SessionState.ABORTED, ctl.state(), ctl.abortReason());
            AlsSession.Report r = (AlsSession.Report) ctl.endRun();
            assertSame(r, listener.last);
            assertTrue(r.events.size() >= 2, "events in run " + runs + ": " + r.events.size());
            ctl.applyAndPrepareNext();
            runs++;
        }
        assertEquals(SessionState.DONE, ctl.state(), String.join("\n", listener.log));
        assertTrue(runs >= 2 && runs <= 10, "runs " + runs);
        double[][] timing = port.readArray2D(SimEcuPort.CONFIG, b.alsTimingTable);
        // rows = TPS bins, columns = RPM bins 2000..7000: the visited 4000 rpm column got more retard
        assertTrue(timing[0][2] < -14, "4000 rpm timing " + timing[0][2]);
        assertTrue(timing[0][2] >= -35, "4000 rpm timing " + timing[0][2]);
        assertEquals(-8, timing[0][5], 1e-9); // 7000 rpm never visited
        assertFalse(ctl.tables().isEmpty());
        assertTrue(ctl.analysisText().length() > 0);
        ctl.restoreOriginal();
        assertEquals(-8, port.readArray2D(SimEcuPort.CONFIG, b.alsTimingTable)[0][2], 1e-9);
        assertEquals(60, port.readScalar(SimEcuPort.CONFIG, b.alsAirStepsParam), 1e-9);
        assertEquals("Always ON", port.readOption(SimEcuPort.CONFIG, b.alsEnableParam));
    }
}
