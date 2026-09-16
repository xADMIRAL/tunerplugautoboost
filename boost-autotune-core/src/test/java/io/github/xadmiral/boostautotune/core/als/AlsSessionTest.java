package io.github.xadmiral.boostautotune.core.als;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.sim.BoostPlant;
import io.github.xadmiral.boostautotune.core.sim.PullSimulator;
import io.github.xadmiral.boostautotune.core.sim.SimEcu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlsSessionTest {
    private static final Axis RPM = Axis.of(2000, 3000, 4000, 5000, 6000, 7000);
    private static final Axis TPS = Axis.of(0, 4, 8, 12, 16, 20);

    private static SimEcu ecu() {
        EcuState e = new EcuState();
        Axis r8 = Axis.of(500, 1000, 2000, 3000, 4000, 5000, 6000, 7000);
        e.targetTable = Grid.filled(r8, Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 100);
        e.openLoopTable = Grid.filled(r8, Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 45);
        e.pid = new PidGains(100, 50, 50);
        e.closedLoop = false;
        SimEcu ecu = new SimEcu(e);
        ecu.alsEnabled = true;
        ecu.alsOperateTps = 12;
        ecu.alsMaxTimeSec = 4;
        return ecu;
    }

    private static int run(AlsSession session, SimEcu ecu, PullSimulator sim, int maxRuns) {
        int runs = 0;
        AlsSession.Plan plan = session.plan();
        while (session.state() != SessionState.DONE && session.state() != SessionState.ABORTED && runs < maxRuns) {
            ecu.alsTiming = plan.timing;
            ecu.alsAir = plan.air;
            ecu.alsMaxTimeSec = plan.holdSec;
            ecu.alsMinRpm = plan.ecuMinRpm;
            ecu.resetAuxiliaries();
            session.startRun();
            for (Sample s : sim.alsCycle(3)) {
                session.onSample(s);
                if (session.state() == SessionState.ABORTED) {
                    return runs;
                }
            }
            sim.setTime(sim.time() + 20);
            ecu.coolDown(60);
            AlsSession.Report r = session.endRun();
            System.out.println(r.summary(session.config()));
            plan = session.commit(r);
            runs++;
        }
        return runs;
    }

    @Test
    void retardsUntilOffThrottleBoostReachesTheTarget() {
        SimEcu ecu = ecu();
        PullSimulator sim = new PullSimulator(new BoostPlant(21), ecu);
        Grid start = Grid.filled(RPM, TPS, -8); // barely any anti-lag
        ecu.alsTiming = start;
        ecu.alsAir = 60;
        AlsConfig cfg = new AlsConfig();
        cfg.targetKpa = 130;
        cfg.holdRpm = 2000; // easy RPM hold: this test is about the timing
        cfg.autoEndRunIdleSec = 0;
        AlsSession session = new AlsSession(cfg);
        session.initialize(start, 60);
        int runs = run(session, ecu, sim, 15);
        assertEquals(SessionState.DONE, session.state(), "aborted: " + session.abortReason());
        assertTrue(runs <= 12, "runs " + runs);
        Grid result = session.plan().timing;
        // the visited columns (2000-4000 rpm on the way down to the hold RPM) got more retard, unvisited ones stayed
        assertTrue(result.get(2, 0) <= -10, "4000 rpm should be retarded: " + result.get(2, 0));
        assertTrue(result.min() <= -14, "the low columns need a lot of retard: " + result);
        assertEquals(-8, result.get(5, 0), 1e-9);
        assertTrue(session.plan().air >= 60, "air " + session.plan().air);
        assertEquals(3, session.plan().holdSec, 1e-9);
        assertEquals(1400, session.plan().ecuMinRpm, 1e-9); // stall guard 1200 + 200
        assertTrue(session.lastReport().converged);
        assertTrue(session.lastReport().minRpm >= cfg.holdRpm - cfg.holdTolRpm, "min rpm " + session.lastReport().minRpm);
    }

    @Test
    void holdsTheAskedRpmByAddingIdleValveAir() {
        SimEcu ecu = ecu();
        PullSimulator sim = new PullSimulator(new BoostPlant(22), ecu);
        Grid start = Grid.filled(RPM, TPS, -16);
        ecu.alsTiming = start;
        ecu.alsAir = 40; // holds ~1400 rpm: far too little for the ask below
        AlsConfig cfg = new AlsConfig();
        cfg.targetKpa = 130;
        cfg.holdRpm = 2600;
        cfg.holdSec = 2.5;
        cfg.autoEndRunIdleSec = 0;
        AlsSession session = new AlsSession(cfg);
        session.initialize(start, 40);
        int runs = run(session, ecu, sim, 15);
        assertEquals(SessionState.DONE, session.state(), "aborted: " + session.abortReason());
        assertTrue(runs <= 12, "runs " + runs);
        assertTrue(session.plan().air > 100, "air should have been raised for the RPM hold: " + session.plan().air);
        AlsSession.Report last = session.lastReport();
        assertTrue(last.minRpm >= cfg.holdRpm - cfg.holdTolRpm, "RPM held down to " + last.minRpm);
        assertEquals(2.5, session.plan().holdSec, 1e-9);
        assertEquals(1900, session.plan().ecuMinRpm, 1e-9); // hold rpm - 700
        for (AlsEvent e : last.events) {
            assertTrue(e.durationSec() <= cfg.holdSec + 0.2, "the ECU cuts the anti-lag after the hold time: " + e.durationSec());
        }
        assertTrue(last.summary(cfg).contains("RPM held down to"));
    }

    @Test
    void acceptsWhatTheRetardLimitAllows() {
        SimEcu ecu = ecu();
        PullSimulator sim = new PullSimulator(new BoostPlant(24), ecu);
        Grid start = Grid.filled(RPM, TPS, -18);
        ecu.alsTiming = start;
        ecu.alsAir = 60;
        AlsConfig cfg = new AlsConfig();
        cfg.targetKpa = 175;    // out of reach with the retard limited to -20 deg
        cfg.minTimingDeg = -20;
        cfg.holdRpm = 2000;
        cfg.autoEndRunIdleSec = 0;
        AlsSession session = new AlsSession(cfg);
        session.initialize(start, 60);
        int runs = run(session, ecu, sim, 15);
        assertEquals(SessionState.DONE, session.state(), "aborted: " + session.abortReason());
        assertTrue(runs <= 8, "runs " + runs);
        assertEquals(-20, session.plan().timing.min(), 1e-9); // the visited columns sit on the limit
        boolean told = false;
        for (String m : session.lastReport().messages) {
            told |= m.contains("cannot reach");
        }
        assertTrue(told, "the driver should be told the target is out of reach: " + session.lastReport().messages);
    }

    @Test
    void hotIntakeAbortsAndUndoValuesAreKept() {
        SimEcu ecu = ecu();
        PullSimulator sim = new PullSimulator(new BoostPlant(23), ecu);
        Grid start = Grid.filled(RPM, TPS, -30);
        ecu.alsTiming = start;
        AlsConfig cfg = new AlsConfig();
        cfg.maxMatC = 40;
        cfg.abortMatC = 45; // the model heats the intake by a few degrees per second of anti-lag
        cfg.autoEndRunIdleSec = 0;
        AlsSession session = new AlsSession(cfg);
        session.initialize(start, 60);
        run(session, ecu, sim, 3);
        assertEquals(SessionState.ABORTED, session.state());
        assertTrue(session.abortReason().contains("MAT"), session.abortReason());
        assertEquals(-30, session.originalTiming().get(0, 0), 1e-9);
        assertEquals(60, session.originalAir(), 1e-9);
    }

    @Test
    void stallGuardAndInferredActivityWithoutEcuFlag() {
        AlsConfig cfg = new AlsConfig();
        cfg.autoEndRunIdleSec = 0;
        AlsSession session = new AlsSession(cfg);
        session.initialize(Grid.filled(RPM, TPS, -15), 60);
        session.startRun();
        double t = 0;
        for (int i = 0; i < 20; i++, t += 0.04) {
            session.onSample(Sample.builder().time(t).rpm(4000).tps(100).map(150).clt(90).build()); // arms
        }
        for (int i = 0; i < 20; i++, t += 0.04) {
            session.onSample(Sample.builder().time(t).rpm(3500).tps(3).map(120).clt(90).build());   // inferred ALS window
        }
        assertEquals(1, session.eventsInRun());
        session.onSample(Sample.builder().time(t).rpm(1000).tps(3).map(100).clt(90).build());
        assertEquals(SessionState.ABORTED, session.state());
        assertTrue(session.abortReason().contains("stall"));
    }

    @Test
    void noEventsAsksForRepeat() {
        AlsConfig cfg = new AlsConfig();
        cfg.autoEndRunIdleSec = 0;
        AlsSession session = new AlsSession(cfg);
        session.initialize(Grid.filled(RPM, TPS, -15), 60);
        session.startRun();
        for (int i = 0; i < 50; i++) {
            session.onSample(Sample.builder().time(i * 0.04).rpm(3000).tps(30).map(100).clt(90).build());
        }
        AlsSession.Report r = session.endRun();
        assertFalse(r.converged);
        assertTrue(r.messages.get(0).contains("No anti-lag event"));
        assertEquals(-15, r.nextPlan.timing.get(0, 0), 1e-9);
    }
}
