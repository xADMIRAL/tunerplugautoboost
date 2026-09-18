package io.github.xadmiral.boostautotune.plugin;

import io.github.xadmiral.boostautotune.core.als.AlsConfig;
import io.github.xadmiral.boostautotune.core.als.AlsSession;
import io.github.xadmiral.boostautotune.core.knock.KnockCalConfig;
import io.github.xadmiral.boostautotune.core.knock.KnockCalSession;
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
        for (AntilagPresets.Setting s : AntilagPresets.create(AntilagPresets.DRIFT_MEDIUM)) {
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
        cfg.holdRpm = 2600;
        cfg.holdSec = 2.5;
        cfg.autoEndRunIdleSec = 0;
        ctl.startAntilagSession(cfg, b);
        assertEquals(TuneMode.ANTILAG, ctl.mode());
        assertEquals(3, port.readScalar(SimEcuPort.CONFIG, b.alsMaxTimeParam), 1e-9); // preset value until the plan is written
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
        assertTrue(runs >= 2 && runs <= 12, "runs " + runs);
        // the hold settings reached the ECU and the idle valve was opened for the RPM hold
        assertEquals(2.5, port.readScalar(SimEcuPort.CONFIG, b.alsMaxTimeParam), 1e-9);
        assertEquals(1900, port.readScalar(SimEcuPort.CONFIG, b.alsMinRpmParam), 1e-9);
        assertTrue(port.readScalar(SimEcuPort.CONFIG, b.alsAirStepsParam) > 100, "air " + port.readScalar(SimEcuPort.CONFIG, b.alsAirStepsParam));
        AlsSession.Report last = (AlsSession.Report) listener.last;
        assertTrue(last.minRpm >= cfg.holdRpm - cfg.holdTolRpm, "RPM held down to " + last.minRpm);
        double[][] timing = port.readArray2D(SimEcuPort.CONFIG, b.alsTimingTable);
        // rows = TPS bins, columns = RPM bins 2000..7000: the visited low columns got more retard
        double lowest = Math.min(timing[0][0], Math.min(timing[0][1], timing[0][2]));
        assertTrue(lowest < -12, "2000-4000 rpm timing " + lowest);
        assertTrue(lowest >= -35, "timing " + lowest);
        assertEquals(-8, timing[0][5], 1e-9); // 7000 rpm never visited
        assertFalse(ctl.tables().isEmpty());
        assertTrue(ctl.analysisText().length() > 0);
        ctl.restoreOriginal();
        assertEquals(-8, port.readArray2D(SimEcuPort.CONFIG, b.alsTimingTable)[0][2], 1e-9);
        assertEquals(60, port.readScalar(SimEcuPort.CONFIG, b.alsAirStepsParam), 1e-9);
        assertEquals(3, port.readScalar(SimEcuPort.CONFIG, b.alsMaxTimeParam), 1e-9);
        assertEquals("Always ON", port.readOption(SimEcuPort.CONFIG, b.alsEnableParam));
    }

    @Test
    void knockCalibrationSetsTheGainsAndTheThresholdCurve() throws Exception {
        SimEcuPort port = new SimEcuPort();
        Collector listener = new Collector();
        TuneController ctl = new TuneController(listener);
        ctl.setPort(port);
        EcuBinding b = demoBinding();
        EcuAdapter setup = new EcuAdapter(port, b);
        for (int c = 1; c <= 6; c++) {
            setup.writeAny(b.knockGainParam(c), "1.000"); // a fresh ECU: the input pegs at full scale
        }
        // a timing map that does not knock: the demo engine's base table knocks above 5000 rpm
        double[][] spark = port.readArray2D(SimEcuPort.CONFIG, b.sparkTable);
        for (double[] row : spark) {
            for (int i = 0; i < row.length; i++) {
                row[i] -= 4;
            }
        }
        port.writeArray2D(SimEcuPort.CONFIG, b.sparkTable, spark);
        KnockCalConfig cfg = new KnockCalConfig();
        cfg.autoEndRunIdleSec = 0;
        ctl.startKnockCalSession(cfg, b);
        assertEquals(TuneMode.KNOCK_CAL, ctl.mode());
        assertTrue(String.join("\n", listener.log).contains("6 gain(s)"), String.join("\n", listener.log));
        int runs = 0;
        while (ctl.state() != SessionState.DONE && runs < 12) {
            ctl.writePlanToEcu();
            ctl.startRun();
            port.simulatePull(3, 1000);
            waitForStream(port);
            port.simulatePull(3, 1000);
            waitForStream(port);
            assertNotEquals(SessionState.ABORTED, ctl.state(), ctl.abortReason());
            KnockCalSession.Report r = (KnockCalSession.Report) ctl.endRun();
            assertSame(r, listener.last);
            assertTrue(r.samples > 100, "samples at load in run " + runs + ": " + r.samples);
            ctl.applyAndPrepareNext();
            runs++;
        }
        assertEquals(SessionState.DONE, ctl.state(), String.join("\n", listener.log));
        assertTrue(runs >= 3 && runs <= 9, "runs " + runs);
        // the gains came down from 1.000 and the quiet cylinder 5 got more than the loud cylinder 6
        double[] gains = setup.readKnockGains(setup.knockGainParams(6, true));
        for (double g : gains) {
            assertTrue(g < 0.8, "gain " + g);
        }
        assertTrue(gains[4] > gains[5], java.util.Arrays.toString(gains));
        KnockCalSession.Report last = (KnockCalSession.Report) listener.last;
        assertTrue(last.reference >= cfg.noiseBandLowPct && last.reference <= cfg.noiseBandHighPct, "reference " + last.reference);
        assertEquals(0, last.retardEvents, "no false knock retard in the final run");
        double[] thr = port.readArray1D(SimEcuPort.CONFIG, b.knockThresholdTable);
        assertTrue(thr[8] > thr[0] + 5, java.util.Arrays.toString(thr)); // the curve rises with the noise
        for (int i = 0; i < last.bins.length; i++) {
            if (last.bins[i].measured(cfg.minSamplesPerBin)) {
                assertTrue(thr[i] >= last.bins[i].p95 * 1.05, String.format("bin %.0f: threshold %.1f vs p95 %.1f", last.bins[i].rpm, thr[i], last.bins[i].p95));
            }
        }
        assertEquals(2, ctl.tables().size());
        assertTrue(ctl.analysisText().contains("Per cylinder p95"));
        ctl.restoreOriginal();
        assertEquals("1.000", port.readOption(SimEcuPort.CONFIG, "knock_gain05"));
        assertEquals(33, port.readArray1D(SimEcuPort.CONFIG, b.knockThresholdTable)[0], 1e-9);
    }

    @Test
    void antilagWithDriveByWireOpensTheThrottleForTheRpmHold() throws Exception {
        SimEcuPort port = new SimEcuPort();
        Collector listener = new Collector();
        TuneController ctl = new TuneController(listener);
        ctl.setPort(port);
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        b.timeChannel = "seconds";
        EcuAdapter setup = new EcuAdapter(port, b);
        setup.writeAny(b.dbwEnableParam, "On");
        boolean hasPos = false, hasSteps = false;
        for (AntilagPresets.Setting s : AntilagPresets.create(AntilagPresets.DRIFT_MEDIUM, true)) {
            hasPos |= s.param.equals("als_iac_pos");
            hasSteps |= s.param.equals("als_iac_steps");
            if (setup.hasParameter(s.param)) {
                setup.writeAny(s.param, s.value);
            }
        }
        assertTrue(hasPos && !hasSteps, "a DBW preset carries the throttle opening, not idle valve steps");
        setup.writeAny(b.alsTimingTable, "-8");
        setup.writeAny("als_iac_pos", "4");     // barely open: the hold has to come from the autotune
        setup.writeAny(b.alsMaxTpsParam, "6");  // and the ALS must not switch itself off as the throttle opens
        double stepsBefore = port.readScalar(SimEcuPort.CONFIG, b.alsAirStepsParam);
        AlsConfig cfg = new AlsConfig();
        cfg.targetKpa = 130;
        cfg.holdRpm = 2600;
        cfg.holdSec = 2.5;
        cfg.autoEndRunIdleSec = 0;
        ctl.startAntilagSession(cfg, b);
        assertTrue(String.join("\n", listener.log).contains("throttle opening"), "the session must say it works the throttle");
        int runs = 0;
        while (ctl.state() != SessionState.DONE && runs < 12) {
            ctl.writePlanToEcu();
            ctl.startRun();
            port.simulateAntilag(3, 1000);
            waitForStream(port);
            port.coolDown(60);
            assertNotEquals(SessionState.ABORTED, ctl.state(), ctl.abortReason());
            ctl.endRun();
            ctl.applyAndPrepareNext();
            runs++;
        }
        assertEquals(SessionState.DONE, ctl.state(), String.join("\n", listener.log));
        double pos = port.readScalar(SimEcuPort.CONFIG, "als_iac_pos");
        assertTrue(pos > 12 && pos <= 20, "throttle opening " + pos);
        assertEquals(stepsBefore, port.readScalar(SimEcuPort.CONFIG, b.alsAirStepsParam), 1e-9, "idle valve steps untouched on DBW");
        assertTrue(port.readScalar(SimEcuPort.CONFIG, b.alsMaxTpsParam) >= pos + 3 - 1e-9, "als_maxtps follows the opening");
        AlsSession.Report last = (AlsSession.Report) listener.last;
        assertTrue(last.minRpm >= cfg.holdRpm - cfg.holdTolRpm, "RPM held down to " + last.minRpm);
        ctl.restoreOriginal();
        assertEquals(4, port.readScalar(SimEcuPort.CONFIG, "als_iac_pos"), 1e-9);
        assertEquals(6, port.readScalar(SimEcuPort.CONFIG, b.alsMaxTpsParam), 1e-9);
    }
}
