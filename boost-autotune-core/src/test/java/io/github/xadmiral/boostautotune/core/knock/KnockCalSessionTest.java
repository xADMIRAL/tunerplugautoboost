package io.github.xadmiral.boostautotune.core.knock;

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

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class KnockCalSessionTest {
    /** The MS3 knock gain options. */
    static final double[] OPTIONS = {2.000, 1.882, 1.778, 1.684, 1.600, 1.523, 1.455, 1.391, 1.333, 1.280, 1.231, 1.185, 1.143, 1.063, 1.000,
            0.944, 0.895, 0.85, 0.81, 0.773, 0.739, 0.708, 0.680, 0.654, 0.630, 0.607, 0.586, 0.567, 0.548, 0.500, 0.471, 0.444, 0.421, 0.400,
            0.381, 0.364, 0.348, 0.333, 0.320, 0.308, 0.296, 0.286, 0.276, 0.267, 0.258, 0.250, 0.236, 0.222, 0.211, 0.200, 0.190, 0.182,
            0.174, 0.167, 0.160, 0.154, 0.148, 0.143, 0.138, 0.133, 0.129, 0.125, 0.118, 0.111};
    private static final Axis RPM8 = Axis.of(500, 1000, 2000, 3000, 4000, 5000, 6000, 7000);
    private static final Axis LOAD8 = Axis.of(40, 60, 100, 120, 150, 180, 200, 220);

    /** Open-loop boost at a fixed duty, a spark table 6 degrees under MBT (no real knock), noise model on. */
    private static SimEcu ecu(double gain) {
        EcuState e = new EcuState();
        e.targetTable = Grid.filled(RPM8, Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 100);
        e.openLoopTable = Grid.filled(RPM8, Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 45);
        e.pid = new PidGains(100, 50, 50);
        e.closedLoop = false;
        SimEcu ecu = new SimEcu(e);
        Grid spark = new Grid(RPM8, LOAD8);
        for (int yi = 0; yi < spark.height(); yi++) {
            for (int xi = 0; xi < spark.width(); xi++) {
                spark.set(xi, yi, ecu.torque.mbt(RPM8.bin(xi), LOAD8.bin(yi)) - 6);
            }
        }
        ecu.sparkTable = spark;
        ecu.torque.knockLimitedFromRpm = 0; // a safe map: whatever the level says is noise
        ecu.knockNoiseEnabled = true;
        Arrays.fill(ecu.knockGains, gain);
        return ecu;
    }

    private static int run(KnockCalSession session, SimEcu ecu, PullSimulator sim, int maxRuns, int pullsPerRun) {
        int runs = 0;
        KnockCalSession.Plan plan = session.plan();
        while (session.state() != SessionState.DONE && session.state() != SessionState.ABORTED && runs < maxRuns) {
            ecu.knockThresholds = plan.thresholds.clone();
            if (plan.gains.length == 1) {
                Arrays.fill(ecu.knockGains, plan.gains[0]);
            } else {
                System.arraycopy(plan.gains, 0, ecu.knockGains, 0, plan.gains.length);
            }
            ecu.resetAuxiliaries();
            session.startRun();
            for (int k = 0; k < pullsPerRun; k++) {
                for (Sample s : sim.pull(3)) {
                    session.onSample(s);
                }
                sim.setTime(sim.time() + 4);
            }
            KnockCalSession.Report r = session.endRun();
            System.out.println(r.summary(session.config()));
            plan = session.commit(r);
            runs++;
        }
        return runs;
    }

    private static KnockCalConfig cfg() {
        KnockCalConfig c = new KnockCalConfig();
        c.minRpm = 2000;
        c.maxRpm = 6800;
        c.autoEndRunIdleSec = 0;
        return c;
    }

    @Test
    void lowGainIsRaisedThenThresholdsSitAboveTheNoise() {
        SimEcu ecu = ecu(0.2); // reads ~15 % at the top: too quiet
        PullSimulator sim = new PullSimulator(new BoostPlant(31), ecu);
        KnockCalConfig cfg = cfg();
        cfg.balanceCylinders = false;
        KnockCalSession session = new KnockCalSession(cfg);
        session.initialize(ecu.knockRpmBins, new double[]{33, 40, 42, 45, 45, 45, 45, 45, 50, 50}, new double[]{0.2}, OPTIONS);
        int runs = run(session, ecu, sim, 12, 2);
        assertEquals(SessionState.DONE, session.state(), "aborted: " + session.abortReason());
        assertTrue(runs <= 8, "runs " + runs);
        double gain = session.plan().gains[0];
        assertTrue(gain >= 0.33 && gain <= 0.7, "gain " + gain);
        KnockCalSession.Report last = session.lastReport();
        assertTrue(last.reference >= cfg.noiseBandLowPct && last.reference <= cfg.noiseBandHighPct, "reference " + last.reference);
        // every measured bin's threshold clears its noise by the margin and no sample crossed it
        double[] thr = session.plan().thresholds;
        for (int i = 0; i < last.bins.length; i++) {
            KnockCalSession.BinStats b = last.bins[i];
            if (!b.measured(cfg.minSamplesPerBin)) {
                continue;
            }
            assertTrue(thr[i] >= b.p95 * 1.2, String.format("bin %.0f: threshold %.1f vs p95 %.1f", b.rpm, thr[i], b.p95));
            assertTrue(thr[i] <= b.p95 * 2.2, String.format("bin %.0f: threshold %.1f far above p95 %.1f", b.rpm, thr[i], b.p95));
            assertEquals(0, b.exceed, "bin " + b.rpm);
        }
        // the curve rises with RPM like the noise does and the low bins were not left at the old values
        assertTrue(thr[8] > thr[0], Arrays.toString(thr));
        assertTrue(last.converged);
        assertEquals(0.2, session.originalGains()[0], 1e-9);
        assertEquals(33, session.originalThresholds()[0], 1e-9);
    }

    @Test
    void clippingGainIsLoweredBeforeTheThresholdsAreSet() {
        SimEcu ecu = ecu(2.0); // pegged at 100 %
        PullSimulator sim = new PullSimulator(new BoostPlant(32), ecu);
        KnockCalConfig cfg = cfg();
        cfg.balanceCylinders = false;
        KnockCalSession session = new KnockCalSession(cfg);
        session.initialize(ecu.knockRpmBins, new double[]{33, 40, 42, 45, 45, 45, 45, 45, 50, 50}, new double[]{2.0}, OPTIONS);
        session.startRun();
        for (Sample s : sim.pull(3)) {
            session.onSample(s);
        }
        sim.setTime(sim.time() + 4);
        for (Sample s : sim.pull(3)) {
            session.onSample(s);
        }
        KnockCalSession.Report r = session.endRun();
        System.out.println(r.summary(cfg));
        assertTrue(r.reference > 95, "reference " + r.reference);
        assertTrue(r.nextPlan.gains[0] <= 1.0 && r.nextPlan.gains[0] >= 1.0 / cfg.maxGainFactorPerRun - 1e-9, "gain " + r.nextPlan.gains[0]);
        assertEquals(KnockCalSession.Phase.SURVEY, r.nextPlan.phase);
        assertTrue(r.retardEvents > 0, "a pegged input trips the ECU's knock control");
        session.commit(r);
        int runs = run(session, ecu, sim, 12, 2);
        assertEquals(SessionState.DONE, session.state(), "aborted: " + session.abortReason());
        assertTrue(runs <= 9, "runs " + runs);
        assertTrue(session.plan().gains[0] < 0.7, "gain " + session.plan().gains[0]);
    }

    @Test
    void cylindersAreBalancedWithTheirOwnGains() {
        SimEcu ecu = ecu(0.5);
        PullSimulator sim = new PullSimulator(new BoostPlant(33), ecu);
        KnockCalConfig cfg = cfg();
        KnockCalSession session = new KnockCalSession(cfg);
        double[] gains = {0.5, 0.5, 0.5, 0.5, 0.5, 0.5};
        session.initialize(ecu.knockRpmBins, new double[]{33, 40, 42, 45, 45, 45, 45, 45, 50, 50}, gains, OPTIONS);
        int runs = run(session, ecu, sim, 14, 2);
        assertEquals(SessionState.DONE, session.state(), "aborted: " + session.abortReason());
        assertTrue(runs <= 10, "runs " + runs);
        double[] g = session.plan().gains;
        // the quiet cylinder (factor 0.85) ends up with more gain than the loud one (factor 1.2)
        assertTrue(g[4] > g[5], Arrays.toString(g));
        assertTrue(g[1] > g[2], Arrays.toString(g));
        KnockCalSession.Report last = session.lastReport();
        double lo = Double.POSITIVE_INFINITY;
        double hi = 0;
        for (double v : last.cylReference) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        assertTrue(hi - lo <= cfg.cylinderImbalancePct / 100.0 * hi + 1e-9, "cylinder p95 spread " + Arrays.toString(last.cylReference));
        assertTrue(hi >= cfg.noiseBandLowPct && hi <= cfg.noiseBandHighPct, "loudest cylinder " + hi);
        assertTrue(last.summary(cfg).contains("Per cylinder p95"));
    }

    @Test
    void falseTriggersRaiseTheThresholdInTheVerifyRun() {
        KnockCalConfig cfg = cfg();
        cfg.balanceCylinders = false;
        cfg.minSamplesPerBin = 5;
        KnockCalSession session = new KnockCalSession(cfg);
        double[] bins = {2000, 3000, 4000, 5000, 6000};
        session.initialize(bins, new double[]{40, 40, 40, 40, 40}, new double[]{0.5}, OPTIONS);
        // survey: flat 30 % noise everywhere, gain in band
        session.startRun();
        double t = 0;
        for (double r = 2000; r <= 6000; r += 25) {
            session.onSample(Sample.builder().time(t += 0.04).rpm(r).tps(100).map(150).ignLoad(150).knock(28 + (r % 3)).knockRetard(0).build());
        }
        KnockCalSession.Report r1 = session.endRun();
        assertEquals(KnockCalSession.Phase.VERIFY, r1.nextPlan.phase);
        for (double v : r1.nextPlan.thresholds) {
            assertTrue(v >= 36 && v <= 45, "threshold " + v);
        }
        session.commit(r1);
        // verify: the 4000 rpm bin crosses its threshold twice and the ECU pulled timing there
        session.startRun();
        for (double r = 2000; r <= 6000; r += 25) {
            boolean bad = r == 4000 || r == 4025;
            session.onSample(Sample.builder().time(t += 0.04).rpm(r).tps(100).map(150).ignLoad(150).knock(bad ? 60 : 30).knockRetard(bad ? 1.5 : 0).build());
        }
        KnockCalSession.Report r2 = session.endRun();
        System.out.println(r2.summary(cfg));
        assertFalse(r2.converged);
        assertTrue(r2.nextPlan.thresholds[2] > r1.nextPlan.thresholds[2] + 2.9, Arrays.toString(r2.nextPlan.thresholds));
        assertEquals(r1.nextPlan.thresholds[0], r2.nextPlan.thresholds[0], 1e-9);
        assertTrue(r2.bins[2].retards >= 1);
        session.commit(r2);
        // two clean runs finish the job
        for (int k = 0; k < 2; k++) {
            session.startRun();
            for (double r = 2000; r <= 6000; r += 25) {
                session.onSample(Sample.builder().time(t += 0.04).rpm(r).tps(100).map(150).ignLoad(150).knock(30).knockRetard(0).build());
            }
            KnockCalSession.Report r3 = session.endRun();
            assertTrue(r3.converged);
            session.commit(r3);
        }
        assertEquals(SessionState.DONE, session.state());
        assertTrue(session.plan().title().startsWith("Knock calibration settled"));
    }

    @Test
    void noSamplesAsksForARepeatAndOverboostAborts() {
        KnockCalConfig cfg = cfg();
        KnockCalSession session = new KnockCalSession(cfg);
        session.initialize(new double[]{2000, 4000, 6000}, new double[]{40, 40, 40}, new double[]{1.0}, null);
        session.startRun();
        for (int i = 0; i < 50; i++) {
            session.onSample(Sample.builder().time(i * 0.04).rpm(3000).tps(20).map(60).ignLoad(60).knock(10).build());
        }
        assertFalse(session.runLooksFinished());
        KnockCalSession.Report r = session.endRun();
        assertFalse(r.converged);
        assertTrue(r.messages.get(0).contains("No RPM bin"));
        assertArrayEquals(new double[]{40, 40, 40}, r.nextPlan.thresholds, 1e-9);
        session.commit(r);
        session.startRun();
        session.onSample(Sample.builder().time(100).rpm(4000).tps(100).map(230).ignLoad(230).knock(30).build());
        assertEquals(SessionState.ABORTED, session.state());
        assertTrue(session.abortReason().contains("Overboost"));
    }
}
