package io.github.xadmiral.boostautotune.core.vvt;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VvtPidSessionTest {
    private static SimEcu ecuWithVvt(PidGains gains) {
        EcuState e = new EcuState();
        Axis rpm = Axis.of(500, 1000, 2000, 3000, 4000, 5000, 6000, 7000);
        e.targetTable = Grid.filled(rpm, Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 100);
        e.openLoopTable = Grid.filled(rpm, Axis.of(0, 20, 40, 60, 70, 80, 90, 100), 30);
        e.pid = new PidGains(100, 50, 50);
        e.closedLoop = false;
        SimEcu ecu = new SimEcu(e);
        // the user's table: big advance at mid load, little at very low and very high load
        double[][] rows = {
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 10, 10, 5, 5, 0, 0, 0},
                {0, 32.5, 30, 30, 15, 5, 0, 0},
                {0, 45, 40, 30, 15, 5, 0, 0},
                {0, 45, 40, 30, 15, 5, 0, 0},
                {0, 45, 40, 25, 15, 5, 0, 0},
                {0, 45, 40, 20, 15, 5, 0, 0}};
        ecu.vvtTable = new Grid(rpm, Axis.of(40, 60, 100, 120, 150, 180, 200, 220), rows);
        ecu.vvtPid = gains;
        return ecu;
    }

    private static VvtTrackingMetrics drive(SimEcu ecu, VvtPidConfig cfg, double seconds) {
        PullSimulator sim = new PullSimulator(new BoostPlant(11), ecu);
        List<Sample> run = sim.drive(seconds);
        return VvtTrackingAnalyzer.analyze(run, cfg);
    }

    @Test
    void analyzerSeparatesRingingLagAndGoodTracking() {
        VvtPidConfig cfg = new VvtPidConfig();
        VvtTrackingMetrics hot = drive(ecuWithVvt(new PidGains(200, 120, 0)), cfg, 60);
        VvtTrackingMetrics slow = drive(ecuWithVvt(new PidGains(15, 3, 0)), cfg, 60);
        VvtTrackingMetrics good = drive(ecuWithVvt(new PidGains(70, 15, 40)), cfg, 60);
        System.out.println("hot:  " + hot.summary());
        System.out.println("slow: " + slow.summary());
        System.out.println("good: " + good.summary());
        assertTrue(hot.activeSamples > 300);
        assertTrue(VvtPidSession.rings(hot, cfg), "hot gains should ring: " + hot.summary());
        assertFalse(VvtPidSession.rings(slow, cfg), "slow gains do not ring: " + slow.summary());
        assertTrue(slow.lagSec > cfg.lagTolSec, "slow gains should lag: " + slow.summary());
        assertTrue(good.meanAbsErrorDeg <= cfg.steadyStateTolDeg, "good gains should track: " + good.summary());
        assertTrue(good.lagSec <= cfg.lagTolSec, "good gains should not lag: " + good.summary());
        assertFalse(VvtPidSession.rings(good, cfg), good.summary());
    }

    @Test
    void sessionSettlesFromTooHotGains() {
        VvtPidConfig cfg = new VvtPidConfig();
        VvtPidSession session = new VvtPidSession(cfg);
        PidGains gains = new PidGains(200, 120, 0);
        SimEcu ecu = ecuWithVvt(gains);
        PullSimulator sim = new PullSimulator(new BoostPlant(12), ecu);
        VvtPidSession.Plan plan = session.initialize(gains);
        int runs = 0;
        while (session.state() != SessionState.DONE && runs < 12) {
            ecu.vvtPid = plan.gains;
            ecu.resetAuxiliaries();
            session.startRun();
            for (Sample s : sim.drive(60)) {
                session.onSample(s);
            }
            VvtPidSession.Report r = session.endRun();
            System.out.println(r.summary());
            plan = session.commit(r);
            runs++;
        }
        assertEquals(SessionState.DONE, session.state(), "runs " + runs);
        assertTrue(plan.gains.p < 200, "P should have come down: " + plan.gains);
        assertTrue(runs <= 10, "runs " + runs);
    }

    @Test
    void sessionRaisesPWhenLagging() {
        VvtPidConfig cfg = new VvtPidConfig();
        VvtPidSession session = new VvtPidSession(cfg);
        PidGains gains = new PidGains(15, 3, 0);
        SimEcu ecu = ecuWithVvt(gains);
        PullSimulator sim = new PullSimulator(new BoostPlant(13), ecu);
        session.initialize(gains);
        session.startRun();
        for (Sample s : sim.drive(60)) {
            session.onSample(s);
        }
        VvtPidSession.Report r = session.endRun();
        assertTrue(r.nextPlan.gains.p > 15, r.summary());
        assertFalse(r.converged);
    }

    @Test
    void tooFewActiveSamplesAsksForAnotherRun() {
        VvtPidSession session = new VvtPidSession(new VvtPidConfig());
        session.initialize(new PidGains(70, 15, 40));
        session.startRun();
        for (int i = 0; i < 50; i++) {
            session.onSample(Sample.builder().time(i * 0.04).rpm(2000).tps(30).map(60).clt(90).vvtAngle(0).vvtTarget(0).build());
        }
        VvtPidSession.Report r = session.endRun();
        assertFalse(r.converged);
        assertEquals(70, r.nextPlan.gains.p, 1e-9);
        assertTrue(r.rationale.get(0).contains("Only 0 samples"));
    }
}
