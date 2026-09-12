package io.github.xadmiral.boostautotune.plugin;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.RunPlan;
import io.github.xadmiral.boostautotune.core.session.RunReport;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPresets;
import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the controller (adapter, layouts, live feed, safety) against the simulated MS3 port. */
class ControllerDemoIntegrationTest {

    private static final class Collector implements TuneController.Listener {
        final List<String> log = new ArrayList<String>();
        volatile RunReport last;
        int samples;

        public void log(String line) { log.add(line); }
        public void stateChanged() { }
        public void sample(Sample s, SampleState state) { samples++; }
        public void reportReady(RunReport report) { last = report; }
    }

    private static void waitForPull(SimEcuPort port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20000;
        Thread.sleep(50);
        while (port.isPulling() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(port.isPulling(), "simulated pull did not finish");
    }

    @Test
    void fullSessionThroughTheControllerConverges() throws Exception {
        SimEcuPort port = new SimEcuPort();
        Collector listener = new Collector();
        TuneController ctl = new TuneController(listener);
        ctl.setPort(port);
        AutotuneConfig cfg = new AutotuneConfig();
        cfg.targetStagesKpa = new ArrayList<Double>(Arrays.asList(150.0, 170.0));
        cfg.wastegateKpa = 130;
        cfg.spoolStartRpm = 2300;
        cfg.fullTargetRpm = 3300;
        cfg.maxBoostKpa = 200;
        cfg.characterizeStartDuty = 20;
        cfg.characterizeStepPct = 20;
        cfg.autoEndRunIdleSec = 0;
        EcuBinding binding = EcuPresets.create(EcuPresets.STEALTH_PCM);
        binding.timeChannel = "seconds";

        RunPlan plan = ctl.startSession(cfg, binding);
        assertNotNull(plan);
        assertEquals(SessionState.READY, ctl.state());
        assertNotNull(ctl.original());
        assertEquals(100, ctl.original().biasTable.get(0, 0), 1e-9);

        int runs = 0;
        while (ctl.state() != SessionState.DONE && runs < 12) {
            ctl.writePlanToEcu();
            assertTrue(ctl.isEcuPrepared());
            // the plan must be visible in the simulated ECU
            String mode = port.readOption(SimEcuPort.CONFIG, binding.modeParam);
            assertEquals(ctl.session().plan().ecu.closedLoop ? "Closed-loop" : "Open-loop", mode);
            ctl.startRun();
            assertEquals(SessionState.RECORDING, ctl.state());
            port.simulatePull(3, 1000);
            waitForPull(port);
            port.simulatePull(4, 1000);
            waitForPull(port);
            assertNotEquals(SessionState.ABORTED, ctl.state(), "aborted: " + ctl.session().abortReason());
            RunReport r = ctl.endRun();
            assertSame(r, listener.last);
            assertTrue(r.pulls.size() >= 1, "no pulls recorded: " + r.summary());
            ctl.applyAndPrepareNext();
            runs++;
        }
        assertEquals(SessionState.DONE, ctl.state(), String.join("\n", listener.log));
        assertTrue(runs <= 12, "runs " + runs);
        assertTrue(listener.samples > 500);
        // the tune landed in the simulated ECU
        assertEquals("Closed-loop", port.readOption(SimEcuPort.CONFIG, binding.modeParam));
        assertEquals("Advanced Mode", port.readOption(SimEcuPort.CONFIG, binding.closedLoopExtraParam));
        assertEquals("On", port.readOption(SimEcuPort.CONFIG, binding.enableParam));
        double[][] targets = port.readArray2D(SimEcuPort.CONFIG, binding.targetTable);
        assertEquals(170, targets[7][7], 1e-9); // top load row, last rpm column (rows = Y)
        double[][] bias = port.readArray2D(SimEcuPort.CONFIG, binding.biasTable);
        assertTrue(bias[6][4] > bias[2][4], "more duty for 170 than for 130 kPa");
        // undo puts the original numbers back
        ctl.restoreOriginal();
        assertEquals(100, port.readArray2D(SimEcuPort.CONFIG, binding.biasTable)[0][0], 1e-9);
        assertEquals("Open-loop", port.readOption(SimEcuPort.CONFIG, binding.modeParam));
    }

    @Test
    void overboostWritesSafeStateAndAborts() throws Exception {
        SimEcuPort port = new SimEcuPort();
        Collector listener = new Collector();
        TuneController ctl = new TuneController(listener);
        ctl.setPort(port);
        AutotuneConfig cfg = new AutotuneConfig();
        cfg.targetStagesKpa = new ArrayList<Double>(Arrays.asList(150.0));
        cfg.maxBoostKpa = 160;
        cfg.characterizeStartDuty = 90; // way too much duty for the first pull
        cfg.characterizeMaxDuty = 95;
        cfg.autoEndRunIdleSec = 0;
        EcuBinding binding = EcuPresets.create(EcuPresets.STEALTH_PCM);
        binding.timeChannel = "seconds";
        ctl.startSession(cfg, binding);
        ctl.writePlanToEcu();
        ctl.startRun();
        port.simulatePull(3, 1000);
        waitForPull(port);
        ctl.awaitSafety(5000);
        assertEquals(SessionState.ABORTED, ctl.state());
        assertTrue(ctl.session().abortReason().contains("Overboost"));
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        assertEquals(0, port.readScalar(SimEcuPort.CONFIG, b.maxDuty), 1e-9, "max duty forced to min duty");
        double[][] ol = port.readArray2D(SimEcuPort.CONFIG, b.openLoopTable);
        assertEquals(0, ol[7][7], 1e-9);
        boolean logged = false;
        for (String l : listener.log) {
            if (l.contains("OVERBOOST")) {
                logged = true;
            }
        }
        assertTrue(logged);
    }
}
