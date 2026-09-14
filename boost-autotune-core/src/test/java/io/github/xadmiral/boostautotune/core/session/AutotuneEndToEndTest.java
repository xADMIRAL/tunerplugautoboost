package io.github.xadmiral.boostautotune.core.session;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.ResponseMetrics;
import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.LoadSource;
import io.github.xadmiral.boostautotune.core.model.PidGains;
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
