package io.github.xadmiral.boostautotune.core.session;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.ResponseMetrics;
import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.LoadSource;
import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.sim.BoostPlant;
import io.github.xadmiral.boostautotune.core.sim.PullSimulator;
import io.github.xadmiral.boostautotune.core.sim.SimEcu;
import io.github.xadmiral.boostautotune.core.util.Stats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives a whole autotune against the simulated engine, starting from the untouched Stealth PCM /
 * MS3 tune (targets 100, bias 100 %, PID 100/100/100) and asks for 150 then 170 kPa.
 */
class AutotuneEndToEndTest {

    static EcuState untouchedMs3Tune() {
        EcuState e = new EcuState();
        e.targetTable = Grid.filled(Axis.of(1500, 2500, 3500, 4500, 5000, 5500, 6000, 7000),
                Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 100);
        e.targetLoadSource = LoadSource.TPS;
        e.biasTable = Grid.filled(Axis.of(1500, 2500, 3500, 4500, 5000, 5500, 6000, 6500),
                Axis.of(100, 110, 130, 140, 150, 160, 170, 180), 100);
        e.openLoopTable = Grid.filled(Axis.of(500, 1000, 2000, 3000, 4000, 5000, 6000, 7000),
                Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 0);
        e.openLoopLoadSource = LoadSource.TPS;
        e.pid = new PidGains(100, 100, 100);
        e.minDuty = 0;
        e.maxDuty = 100;
        e.closedLoop = false;
        e.targetTableMax = 400;
        return e;
    }

    static AutotuneConfig config() {
        AutotuneConfig c = new AutotuneConfig();
        c.targetStagesKpa = new ArrayList<Double>(Arrays.asList(150.0, 170.0));
        c.wastegateKpa = 130;
        c.autoWastegate = false; // the legacy flow: the spring pressure comes from the settings
        c.gear = 0;              // these tests pull in 3rd and 4th
        c.spoolStartRpm = 2300;
        c.fullTargetRpm = 3300;
        c.wotLoadThreshold = 80;
        c.maxBoostKpa = 200;
        c.minRpm = 1800;
        c.maxRpm = 7200;
        c.characterizeStartDuty = 20;
        c.characterizeStepPct = 20;
        c.runsRequiredPerStage = 2;
        c.autoEndRunIdleSec = 0;
        return c;
    }

    @Test
    void convergesOnTwoStagesWithinAFewRuns() {
        AutotuneConfig cfg = config();
        cfg.fastSpool = false; // classic ramp flow; the fast-spool flow has its own test below
        AutotuneSession session = new AutotuneSession(cfg);
        BoostPlant plant = new BoostPlant(42);
        SimEcu ecu = new SimEcu(untouchedMs3Tune());
        PullSimulator sim = new PullSimulator(plant, ecu);
        final List<Sample> overboost = new ArrayList<Sample>();
        session.setSafetyListener(new SafetyListener() {
            public void overboost(Sample sample, double limitKpa) { overboost.add(sample); }
        });

        RunPlan plan = session.initialize(untouchedMs3Tune());
        assertEquals(SessionPhase.CHARACTERIZE, plan.phase);
        assertEquals(20, plan.openLoopDuty, 1e-9);

        RunReport last = null;
        int runs = 0;
        double peakEver = 0;
        while (session.state() != SessionState.DONE && runs < 12) {
            ecu.apply(plan.ecu);
            session.startRun();
            for (int gear : new int[]{3, 4}) {
                for (Sample s : sim.pull(gear)) {
                    session.onSample(s);
                    peakEver = Math.max(peakEver, s.map);
                }
                sim.setTime(sim.time() + 3);
            }
            assertNotEquals(SessionState.ABORTED, session.state(), "aborted: " + session.abortReason());
            last = session.endRun();
            System.out.println(last.summary());
            System.out.println(session.plantModel().describe());
            plan = session.commit(last);
            runs++;
        }
        assertEquals(SessionState.DONE, session.state(), "did not converge in " + runs + " runs");
        assertTrue(runs <= 10, "took " + runs + " runs");
        assertTrue(overboost.isEmpty());
        assertTrue(peakEver <= cfg.maxBoostKpa, "peak " + peakEver);
        assertNotNull(last);
        assertEquals(SessionPhase.DONE, plan.phase);

        // final closed-loop quality on the 170 kPa stage: the tuner judges the median over the
        // run's pulls with a 25 % hysteresis once a run has been accepted
        List<Double> absErr = new ArrayList<Double>();
        List<Double> overshoot = new ArrayList<Double>();
        for (ResponseMetrics m : last.metrics) {
            assertTrue(m.reached, m.summary());
            assertTrue(m.overshootKpa <= cfg.overshootThresholdKpa * 1.6, m.summary());
            overshoot.add(m.overshootKpa);
            absErr.add(Math.abs(m.steadyStateErrorKpa));
        }
        assertFalse(absErr.isEmpty());
        assertTrue(Stats.median(overshoot) <= cfg.overshootThresholdKpa * 1.25, "overshoot " + overshoot);
        assertTrue(Stats.median(absErr) <= cfg.steadyStateTolKpa, "ss error " + absErr);

        // the bias table learnt more duty for more boost and more duty at low RPM than mid RPM
        Grid bias = plan.ecu.biasTable;
        assertTrue(bias.get(4, 6) > bias.get(4, 2), "170 kPa needs more duty than 130 kPa: " + bias);
        assertTrue(plan.ecu.closedLoop);
        // targets for the final stage
        Grid targets = plan.ecu.targetTable;
        assertEquals(170, targets.get(7, 7), 1e-9);
        assertEquals(130, targets.get(0, 7), 1e-9);
    }

    private static final class Outcome {
        int runs;
        RunReport last;
        RunPlan plan;
        double finalReach = Double.NaN;
        double bestReach = Double.NaN;
        int pushes;
        final List<String> log = new ArrayList<String>();
    }

    @Test
    void measuresTheSpringPressureFirstAndIgnoresPullsInTheWrongGear() {
        AutotuneConfig cfg = config();
        cfg.autoWastegate = true;
        cfg.wastegateKpa = 110; // wrong on purpose: the plant's spring holds 132
        cfg.gear = 4;
        AutotuneSession session = new AutotuneSession(cfg);
        BoostPlant plant = new BoostPlant(42);
        SimEcu ecu = new SimEcu(untouchedMs3Tune());
        PullSimulator sim = new PullSimulator(plant, ecu);
        RunPlan plan = session.initialize(untouchedMs3Tune());
        assertTrue(plan.springRun);
        assertEquals(0, plan.openLoopDuty, 1e-9);
        assertTrue(plan.title().contains("spring pressure"), plan.title());
        assertTrue(plan.driverInstructions().contains("4th gear"), plan.driverInstructions());
        int runs = 0;
        RunReport first = null;
        while (session.state() != SessionState.DONE && runs < 18) {
            ecu.apply(plan.ecu);
            session.startRun();
            for (int gear : new int[]{3, 4}) {
                for (Sample s : sim.pull(gear)) {
                    session.onSample(s);
                }
                sim.setTime(sim.time() + 3);
            }
            assertNotEquals(SessionState.ABORTED, session.state(), "aborted: " + session.abortReason());
            RunReport r = session.endRun();
            if (first == null) {
                first = r;
            }
            // the 3rd-gear pull is ignored: one pull per run, its samples counted as wrong gear
            assertEquals(1, r.pulls.size(), r.summary());
            assertTrue(r.sampleCounts.get(SampleState.WRONG_GEAR) > 20, r.summary());
            plan = session.commit(r);
            runs++;
        }
        assertEquals(SessionState.DONE, session.state(), "did not converge in " + runs + " runs");
        assertTrue(runs <= 14, "runs " + runs);
        assertEquals(132, first.springKpa, 4, "spring read off the open-valve pull");
        assertEquals(first.springKpa, session.measuredSpringKpa(), 1e-9);
        assertEquals(first.springKpa, session.config().wastegateKpa, 1e-9, "the measured value replaces the setting");
        assertTrue(first.summary().contains("spring pressure measured"), first.summary());
        assertFalse(first.nextPlan.springRun);
        assertEquals(20, first.nextPlan.openLoopDuty, 1e-9, "the ladder starts at its first step after the spring run");
        // the final target table ramps from the measured spring, not from the wrong setting
        assertEquals(first.springKpa, plan.ecu.targetTable.get(0, 7), 1.5);
    }

    /** The untouched tune with the ECU's quick spool set as given (on/off, hand-over kPa below the target). */
    private static EcuState quickSpoolTune(boolean on, double deltaKpa) {
        EcuState e = untouchedMs3Tune();
        e.quickSpoolOn = on;
        e.quickSpoolDeltaKpa = deltaKpa;
        e.closedLoopWindowKpa = 30;
        return e;
    }

    private static final class QuickSpoolRun {
        final List<RunPlan> plans = new ArrayList<RunPlan>();
        final List<RunReport> reports = new ArrayList<RunReport>();
        final StringBuilder log = new StringBuilder();
        RunPlan last;

        boolean said(String text) {
            return log.indexOf(text) >= 0;
        }

        RunPlan firstClosedLoop() {
            for (RunPlan p : plans) {
                if (p.phase == SessionPhase.CLOSED_LOOP) {
                    return p;
                }
            }
            return null;
        }

        /** Spool figures of the closed-loop runs on the last stage, in order. */
        List<Double> lastStageReach() {
            List<Double> out = new ArrayList<Double>();
            for (RunReport r : reports) {
                if (r.plan.phase == SessionPhase.CLOSED_LOOP && r.plan.stageIndex == 1 && Stats.finite(r.spoolReachRpm)) {
                    out.add(r.spoolReachRpm);
                }
            }
            return out;
        }
    }

    /** Two 4th-gear pulls per run until the session is done; every plan and report is kept. */
    private static QuickSpoolRun driveQuickSpool(AutotuneConfig cfg, EcuState tune, int maxRuns) {
        AutotuneSession session = new AutotuneSession(cfg);
        BoostPlant plant = new BoostPlant(42);
        SimEcu ecu = new SimEcu(tune);
        PullSimulator sim = new PullSimulator(plant, ecu);
        QuickSpoolRun o = new QuickSpoolRun();
        RunPlan plan = session.initialize(tune);
        o.log.append(String.join("\n", session.log())).append('\n');
        while (session.state() != SessionState.DONE && o.reports.size() < maxRuns) {
            o.plans.add(plan);
            ecu.apply(plan.ecu);
            session.startRun();
            for (int k = 0; k < 2; k++) {
                for (Sample s : sim.pull(4)) {
                    session.onSample(s);
                }
                sim.setTime(sim.time() + 3);
            }
            assertNotEquals(SessionState.ABORTED, session.state(), "aborted: " + session.abortReason() + "\n" + o.log);
            RunReport r = session.endRun();
            o.reports.add(r);
            o.log.append(r.summary()).append('\n');
            plan = session.commit(r);
        }
        o.last = plan;
        assertEquals(SessionState.DONE, session.state(), "did not converge in " + o.reports.size() + " runs:\n" + o.log);
        return o;
    }

    @Test
    void quickSpoolIsOffForTheOpenValveRunsAndALateHandOverMovesEarlier() {
        AutotuneConfig cfg = config();
        cfg.autoWastegate = true;
        cfg.gear = 4;
        QuickSpoolRun o = driveQuickSpool(cfg, quickSpoolTune(true, 5), 22);
        // the spring run and the ladder never run with quick spool: it would hold the valve shut
        for (RunPlan p : o.plans) {
            if (p.phase == SessionPhase.CHARACTERIZE) {
                assertEquals(Boolean.FALSE, p.ecu.quickSpoolOn, p.title());
            }
        }
        assertTrue(o.plans.get(0).springRun);
        assertEquals(132, o.reports.get(0).springKpa, 4, "the spring is read with the valve really open");
        assertTrue(o.plans.get(0).notes.toString().contains("Quick spool off"), o.plans.get(0).notes.toString());
        RunPlan firstClosed = o.firstClosedLoop();
        assertEquals(Boolean.TRUE, firstClosed.ecu.quickSpoolOn);
        assertEquals(5, firstClosed.ecu.quickSpoolDeltaKpa, 1e-9, "starts from the ECU's own hand-over");
        // 5 kPa is too late: the actuator still fills after the hand-over and the boost overshoots
        assertTrue(o.said("Quick spool hands over earlier: 5 ->"), o.log.toString());
        assertTrue(o.said("Quick spool hand-over settled at"), o.log.toString());
        assertEquals(Boolean.TRUE, o.last.ecu.quickSpoolOn);
        assertTrue(o.last.ecu.quickSpoolDeltaKpa >= 7 && o.last.ecu.quickSpoolDeltaKpa <= 16,
                "hand-over " + o.last.ecu.quickSpoolDeltaKpa + "\n" + o.log);
        RunReport last = o.reports.get(o.reports.size() - 1);
        for (ResponseMetrics m : last.metrics) {
            assertTrue(m.overshootKpa < cfg.overshootThresholdKpa, "final overshoot " + m.overshootKpa + "\n" + last.summary());
        }
        assertTrue(o.reports.size() <= 20, "runs " + o.reports.size());
        assertTrue(last.summary().contains("Quick spool for the next run: on, hands over"), last.summary());
    }

    @Test
    void anEarlyQuickSpoolHandOverIsSearchedLaterWhileItBuysSpool() {
        AutotuneConfig cfg = config();
        cfg.autoWastegate = true;
        cfg.gear = 4;
        QuickSpoolRun o = driveQuickSpool(cfg, quickSpoolTune(true, 35), 24);
        assertEquals(35, o.firstClosedLoop().ecu.quickSpoolDeltaKpa, 1e-9);
        assertTrue(o.said("Quick spool push 1/4: quick spool hands over later: 35 ->"), o.log.toString());
        assertTrue(o.said("Quick spool hand-over settled at"), o.log.toString());
        double finalDelta = o.last.ecu.quickSpoolDeltaKpa;
        assertTrue(finalDelta <= 15 && finalDelta >= cfg.quickSpoolMinKpa, "hand-over " + finalDelta + "\n" + o.log);
        List<Double> reach = o.lastStageReach();
        assertTrue(reach.get(reach.size() - 1) < reach.get(0), "the target arrives earlier: " + reach);
        RunReport last = o.reports.get(o.reports.size() - 1);
        for (ResponseMetrics m : last.metrics) {
            assertTrue(m.overshootKpa < cfg.overshootThresholdKpa, "final overshoot " + m.overshootKpa);
        }
    }

    @Test
    void untickedTheClosedLoopKeepsTheEcuQuickSpoolButTheOpenValveRunsStillSwitchItOff() {
        AutotuneConfig cfg = config();
        cfg.autoWastegate = true;
        cfg.gear = 4;
        cfg.tuneQuickSpool = false;
        QuickSpoolRun o = driveQuickSpool(cfg, quickSpoolTune(true, 20), 22);
        for (RunPlan p : o.plans) {
            if (p.phase == SessionPhase.CHARACTERIZE) {
                assertEquals(Boolean.FALSE, p.ecu.quickSpoolOn, p.title());
            } else {
                assertEquals(Boolean.TRUE, p.ecu.quickSpoolOn, p.title());
                assertEquals(20, p.ecu.quickSpoolDeltaKpa, 1e-9, p.title());
            }
        }
        assertEquals(Boolean.TRUE, o.last.ecu.quickSpoolOn);
        assertEquals(20, o.last.ecu.quickSpoolDeltaKpa, 1e-9);
        assertFalse(o.said("Quick spool push"), o.log.toString());
        assertTrue(o.said("Closed-loop runs keep the ECU's own quick spool setting"), o.log.toString());
    }

    @Test
    void anEcuWithoutQuickSpoolIsNotTouched() {
        AutotuneConfig cfg = config();
        cfg.autoWastegate = true;
        cfg.gear = 4;
        QuickSpoolRun o = driveQuickSpool(cfg, untouchedMs3Tune(), 22);
        for (RunPlan p : o.plans) {
            assertNull(p.ecu.quickSpoolOn, p.title());
        }
        assertFalse(o.said("Quick spool"), o.log.toString());
    }

    /** Drives a whole session (3rd + 4th gear pull per run) and returns what it ended with. */
    private static Outcome drive(AutotuneConfig cfg, int maxRuns, boolean bindWindow) {
        AutotuneSession session = new AutotuneSession(cfg);
        BoostPlant plant = new BoostPlant(42);
        EcuState tune = untouchedMs3Tune();
        tune.closedLoopWindowKpa = bindWindow ? 30 : Double.NaN;
        SimEcu ecu = new SimEcu(tune);
        PullSimulator sim = new PullSimulator(plant, ecu);
        RunPlan plan = session.initialize(tune);
        Outcome o = new Outcome();
        while (session.state() != SessionState.DONE && o.runs < maxRuns) {
            ecu.apply(plan.ecu);
            session.startRun();
            for (int gear : new int[]{3, 4}) {
                for (Sample s : sim.pull(gear)) {
                    session.onSample(s);
                }
                sim.setTime(sim.time() + 3);
            }
            assertNotEquals(SessionState.ABORTED, session.state(), "aborted: " + session.abortReason());
            RunReport r = session.endRun();
            o.log.add(r.summary());
            if (r.spoolPush != null) {
                o.pushes++;
            }
            if (!Double.isNaN(r.spoolReachRpm)) {
                o.finalReach = r.spoolReachRpm;
                o.bestReach = Double.isNaN(o.bestReach) ? r.spoolReachRpm : Math.min(o.bestReach, r.spoolReachRpm);
            }
            o.last = r;
            plan = session.commit(r);
            o.runs++;
        }
        o.plan = plan;
        assertEquals(SessionState.DONE, session.state(), "did not converge in " + o.runs + " runs:\n" + String.join("\n", o.log));
        return o;
    }

    @Test
    void fastSpoolReachesTheTargetEarlierThanTheRamp() {
        AutotuneConfig legacyCfg = config();
        legacyCfg.fastSpool = false;
        Outcome legacy = drive(legacyCfg, 14, true);
        AutotuneConfig fastCfg = config();
        fastCfg.fastSpool = true;
        Outcome fast = drive(fastCfg, 16, true);
        System.out.println(String.format(java.util.Locale.US,
                "legacy: %d runs, 170 kPa reached at %.0f rpm | fast: %d runs, %d pushes, reached at %.0f rpm (best %.0f), P %s, window %.0f",
                legacy.runs, legacy.finalReach, fast.runs, fast.pushes, fast.finalReach, fast.bestReach,
                fast.plan.ecu.pid, fast.plan.ecu.closedLoopWindowKpa));
        for (String l : fast.log) {
            System.out.println(l);
        }
        assertEquals(0, legacy.pushes);
        assertTrue(fast.pushes >= 1, "expected at least one spool push");
        assertTrue(fast.runs <= 15, "fast spool took " + fast.runs + " runs");
        assertTrue(fast.finalReach < legacy.finalReach - 100,
                "fast spool should reach 170 kPa earlier: fast " + fast.finalReach + " vs legacy " + legacy.finalReach);
        // the valve is held shut where 170 kPa is out of reach (1500 rpm: 105 kPa, 2500 rpm: 150 kPa capacity)
        Grid bias = fast.plan.ecu.biasTable;
        assertEquals(100, bias.get(0, 6), 1e-9);
        assertTrue(bias.get(1, 6) >= 90, "2500 rpm / 170 kPa should stay (nearly) shut: " + bias.get(1, 6));
        assertTrue(legacy.plan.ecu.biasTable.get(1, 6) < 100, "legacy keeps the extrapolated duty: " + legacy.plan.ecu.biasTable.get(1, 6));
        // flat WOT target above the spool start (2300 rpm), wastegate pressure below it: no ramp cracks the valve open
        Grid t = fast.plan.ecu.targetTable;
        assertEquals(170, t.get(1, 7), 1e-9);
        assertEquals(170, t.get(7, 7), 1e-9);
        assertEquals(130, t.get(0, 7), 1e-9);
        assertTrue(legacy.plan.ecu.targetTable.get(1, 7) < 170, "the ramp keeps 2500 rpm below the stage target");
        // still a clean response at the end
        for (ResponseMetrics m : fast.last.metrics) {
            assertTrue(m.reached, m.summary());
            assertTrue(m.overshootKpa <= fastCfg.overshootThresholdKpa * 1.6, m.summary());
        }
    }

    @Test
    void fastSpoolConvergesWithoutAClosedLoopWindowKnob() {
        AutotuneConfig cfg = config();
        cfg.fastSpool = true;
        Outcome o = drive(cfg, 16, false);
        System.out.println(String.format(java.util.Locale.US, "fast, no window knob: %d runs, %d pushes, 170 kPa reached at %.0f rpm, P %s",
                o.runs, o.pushes, o.finalReach, o.plan.ecu.pid));
        assertTrue(o.runs <= 16, "took " + o.runs + " runs");
        assertTrue(o.finalReach < 3700, "170 kPa reached at " + o.finalReach + " rpm");
        assertTrue(Double.isNaN(o.plan.ecu.closedLoopWindowKpa));
        for (ResponseMetrics m : o.last.metrics) {
            assertTrue(m.overshootKpa <= cfg.overshootThresholdKpa * 1.6, m.summary());
        }
    }

    @Test
    void overboostAbortsTheSessionImmediately() {
        AutotuneConfig cfg = config();
        cfg.maxBoostKpa = 160;
        cfg.targetStagesKpa = new ArrayList<Double>(Arrays.asList(150.0));
        AutotuneSession session = new AutotuneSession(cfg);
        final List<Sample> hits = new ArrayList<Sample>();
        session.setSafetyListener(new SafetyListener() {
            public void overboost(Sample sample, double limitKpa) { hits.add(sample); }
        });
        session.initialize(untouchedMs3Tune());
        session.startRun();
        session.onSample(Sample.builder().time(0).rpm(4000).tps(100).map(165).clt(90).build());
        assertEquals(SessionState.ABORTED, session.state());
        assertEquals(1, hits.size());
        assertTrue(session.abortReason().contains("Overboost"));
    }

    @Test
    void skipsCharacterizationWithoutOpenLoopTable() {
        AutotuneConfig cfg = config();
        AutotuneSession session = new AutotuneSession(cfg);
        EcuState e = untouchedMs3Tune();
        e.openLoopTable = null;
        RunPlan plan = session.initialize(e);
        assertEquals(SessionPhase.CLOSED_LOOP, plan.phase);
        assertTrue(plan.ecu.closedLoop);
        assertEquals(150, plan.ecu.targetTable.get(7, 7), 1e-9);
        assertEquals(50, plan.ecu.pid.i, 1e-9); // soft start
    }
}
