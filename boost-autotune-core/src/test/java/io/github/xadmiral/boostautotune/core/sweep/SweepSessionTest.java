package io.github.xadmiral.boostautotune.core.sweep;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.LoadSource;
import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.sim.BoostPlant;
import io.github.xadmiral.boostautotune.core.sim.PullSimulator;
import io.github.xadmiral.boostautotune.core.sim.SimEcu;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SweepSessionTest {
    private static final Axis RPM8 = Axis.of(500, 1000, 2000, 3000, 4000, 5000, 6000, 7000);
    private static final Axis LOAD8 = Axis.of(40, 60, 100, 120, 150, 180, 200, 220);

    /** Boost controller in open loop at a fixed 45 % duty so every pull sees the same boost. */
    private static SimEcu fixedBoostEcu() {
        EcuState e = new EcuState();
        e.targetTable = Grid.filled(RPM8, Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 100);
        e.openLoopTable = Grid.filled(RPM8, Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 45);
        e.pid = new PidGains(100, 50, 50);
        e.closedLoop = false;
        return new SimEcu(e);
    }

    private static Grid vvtTableOffsetFromOptimum(SimEcu ecu, double offset) {
        Grid g = new Grid(RPM8, LOAD8);
        for (int yi = 0; yi < g.height(); yi++) {
            for (int xi = 0; xi < g.width(); xi++) {
                double v = ecu.torque.camOptimum(RPM8.bin(xi)) + offset;
                g.set(xi, yi, Math.max(0, Math.min(45, v)));
            }
        }
        return g;
    }

    private static Grid sparkTableBelowMbt(SimEcu ecu, double below) {
        Grid g = new Grid(RPM8, LOAD8);
        for (int yi = 0; yi < g.height(); yi++) {
            for (int xi = 0; xi < g.width(); xi++) {
                g.set(xi, yi, ecu.torque.mbt(RPM8.bin(xi), LOAD8.bin(yi)) - below);
            }
        }
        return g;
    }

    private static int runSweep(SweepSession session, SimEcu ecu, PullSimulator sim, boolean vvt, int maxRuns) {
        int runs = 0;
        SweepPlan plan = session.plan();
        while (session.state() != SessionState.DONE && session.state() != SessionState.ABORTED && runs < maxRuns) {
            if (vvt) {
                ecu.vvtTable = plan.table;
            } else {
                ecu.sparkTable = plan.table;
            }
            ecu.resetAuxiliaries();
            session.startRun();
            for (int k = 0; k < 2; k++) {
                for (Sample s : sim.pull(3)) {
                    session.onSample(s);
                    if (session.state() == SessionState.ABORTED) {
                        return runs;
                    }
                }
                sim.setTime(sim.time() + 3);
            }
            SweepReport r = session.endRun();
            System.out.println(r.summary());
            plan = session.commit(r);
            runs++;
        }
        return runs;
    }

    @Test
    void vvtSweepMovesWotRowsTowardsTheCamOptimum() {
        SimEcu ecu = fixedBoostEcu();
        BoostPlant plant = new BoostPlant(3);
        plant.noiseKpa = 0.3;
        PullSimulator sim = new PullSimulator(plant, ecu);
        Grid start = vvtTableOffsetFromOptimum(ecu, 12); // 12 degrees too much advance everywhere
        ecu.vvtTable = start.copy();
        SweepConfig cfg = SweepConfig.vvtDefaults();
        cfg.candidateOffsets = new ArrayList<Double>(Arrays.asList(0.0, -10.0, -5.0, 5.0, 10.0));
        cfg.minRpm = 2500;
        cfg.maxRpm = 6500;
        cfg.gear = 3;
        cfg.autoEndRunIdleSec = 0;
        SweepSession session = new SweepSession(cfg, LoadSource.FUEL_LOAD);
        SweepPlan first = session.initialize(start);
        assertEquals(0, first.offset, 1e-9);
        int runs = runSweep(session, ecu, sim, true, 10);
        assertEquals(SessionState.DONE, session.state(), "runs " + runs);
        assertEquals(5, runs);
        Grid result = session.plan().table;
        // WOT rows (>= 100 kPa) moved towards the optimum in the swept RPM range, low load rows untouched
        double before = 0, after = 0;
        int cells = 0;
        for (int xi = 3; xi <= 6; xi++) {
            for (int yi = 2; yi < LOAD8.size(); yi++) {
                double opt = ecu.torque.camOptimum(RPM8.bin(xi));
                before += Math.abs(start.get(xi, yi) - opt);
                after += Math.abs(result.get(xi, yi) - opt);
                cells++;
            }
        }
        assertTrue(after < before * 0.5, "mean error before " + before / cells + " after " + after / cells + "\n" + result);
        assertEquals(start.get(4, 0), result.get(4, 0), 1e-9);
        assertFalse(session.lastReport().decisions.isEmpty());
    }

    @Test
    void ignitionSweepAdvancesTowardsMbtAndRespectsKnock() {
        SimEcu ecu = fixedBoostEcu();
        ecu.torque.knockLimitedFromRpm = 5000;
        ecu.torque.knockBelowMbtDeg = 7; // knock 1 degree above the starting table in the 5000+ region
        BoostPlant plant = new BoostPlant(5);
        plant.noiseKpa = 0.3;
        PullSimulator sim = new PullSimulator(plant, ecu);
        Grid start = sparkTableBelowMbt(ecu, 8);
        ecu.sparkTable = start.copy();
        SweepConfig cfg = SweepConfig.ignitionDefaults();
        cfg.minRpm = 2500;
        cfg.maxRpm = 6500;
        cfg.gear = 3;
        cfg.passes = 1;
        cfg.autoEndRunIdleSec = 0;
        SweepSession session = new SweepSession(cfg, LoadSource.IGN_LOAD);
        session.initialize(start);
        int runs = runSweep(session, ecu, sim, false, 10);
        assertEquals(SessionState.DONE, session.state(), "aborted: " + session.abortReason());
        assertEquals(4, runs);
        Grid result = session.plan().table;
        // below the knock-limited region: advanced by the session maximum (+4) in the WOT rows
        assertEquals(start.get(3, 4) + 4, result.get(3, 4), 0.6, "3000 rpm / 150 kPa");
        // 4000 rpm sits next to the knock-limited 5000 rpm column: the smoothing keeps the step <= 3 deg
        assertTrue(result.get(4, 5) >= start.get(4, 5) + 2, "4000 rpm / 180 kPa: " + result.get(4, 5) + " vs " + start.get(4, 5));
        // knock-limited region: capped at or below the knocking advance minus the margin
        assertTrue(result.get(6, 4) <= start.get(6, 4) + 0.01, "6000 rpm should not be advanced: " + result.get(6, 4) + " vs " + start.get(6, 4));
        assertTrue(session.knockGuard().cappedCells() > 0);
        boolean knockLimitedDecision = false;
        for (SweepReport.BinDecision d : session.lastReport().decisions) {
            if (d.knockLimited) {
                knockLimitedDecision = true;
            }
        }
        assertTrue(knockLimitedDecision);
        // low load rows untouched
        assertEquals(start.get(4, 0), result.get(4, 0), 1e-9);
    }

    @Test
    void heavyKnockAbortsTheSweep() {
        SimEcu ecu = fixedBoostEcu();
        ecu.torque.knockLimitedFromRpm = 3000;
        ecu.torque.knockBelowMbtDeg = 14; // the starting table itself knocks hard above 3000 rpm
        BoostPlant plant = new BoostPlant(9);
        PullSimulator sim = new PullSimulator(plant, ecu);
        Grid start = sparkTableBelowMbt(ecu, 8);
        ecu.sparkTable = start.copy();
        SweepConfig cfg = SweepConfig.ignitionDefaults();
        cfg.gear = 3;
        cfg.autoEndRunIdleSec = 0;
        SweepSession session = new SweepSession(cfg, LoadSource.IGN_LOAD);
        session.initialize(start);
        runSweep(session, ecu, sim, false, 3);
        assertEquals(SessionState.ABORTED, session.state());
        assertTrue(session.abortReason().contains("knock"), session.abortReason());
    }

    @Test
    void leanMixtureAbortsIgnitionSweep() {
        SweepConfig cfg = SweepConfig.ignitionDefaults();
        cfg.maxWotAfr = 13.0;
        cfg.autoEndRunIdleSec = 0;
        SweepSession session = new SweepSession(cfg, LoadSource.IGN_LOAD);
        session.initialize(Grid.filled(RPM8, LOAD8, 15));
        session.startRun();
        for (int i = 0; i < 10; i++) {
            session.onSample(Sample.builder().time(i * 0.05).rpm(4000).tps(100).map(150).clt(90).afr(14.2).advance(15).build());
        }
        assertEquals(SessionState.ABORTED, session.state());
        assertTrue(session.abortReason().contains("Lean"));
    }

    @Test
    void candidateOrderAlternatesBetweenPasses() {
        SweepConfig cfg = SweepConfig.ignitionDefaults();
        assertEquals(Arrays.asList(0.0, -2.0, 2.0, 4.0), cfg.orderedCandidates(0));
        assertEquals(Arrays.asList(4.0, 2.0, -2.0, 0.0), cfg.orderedCandidates(1));
        cfg.candidateOffsets = new ArrayList<Double>(Arrays.asList(2.0, 6.0));
        assertFalse(cfg.validate().isEmpty());
    }
}
