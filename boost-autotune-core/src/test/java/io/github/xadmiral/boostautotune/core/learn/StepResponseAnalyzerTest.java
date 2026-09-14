package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Sample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepResponseAnalyzerTest {
    private static AutotuneConfig cfg() {
        AutotuneConfig c = new AutotuneConfig();
        c.wastegateKpa = 130;
        c.minRpm = 1800;
        c.steadyStateTolKpa = 5;
        c.settleDelaySec = 0.5;
        c.oscillationDeadbandKpa = 2.5;
        return c;
    }

    /** Builds a WOT pull where MAP follows the given function of time. */
    private static Pull pull(double seconds, double target, MapFn fn, double duty) {
        Pull p = new Pull();
        double dt = 0.04;
        for (double t = 0; t <= seconds; t += dt) {
            Sample s = Sample.builder().time(t).rpm(3000 + t * 800).tps(100).map(fn.map(t)).target(target)
                    .duty(duty).clt(90).build();
            p.add(s, SampleState.STEADY);
        }
        return p;
    }

    interface MapFn {
        double map(double t);
    }

    @Test
    void cleanFirstOrderResponse() {
        ResponseMetrics m = StepResponseAnalyzer.analyze(pull(4, 160, new MapFn() {
            public double map(double t) { return 160 - 60 * Math.exp(-t / 0.4); }
        }, 50), cfg(), 0, 100);
        assertTrue(m.hasTarget);
        assertTrue(m.reached);
        assertEquals(160, m.targetKpa, 1e-9);
        assertTrue(m.overshootKpa < 1, "overshoot " + m.overshootKpa);
        assertTrue(m.riseTimeSec > 0.8 && m.riseTimeSec < 1.2, "rise " + m.riseTimeSec);
        assertEquals(0, m.steadyStateErrorKpa, 1.0);
        assertEquals(0, m.oscillationCycles, 1e-9);
        assertEquals(0, m.saturatedHighFraction, 1e-9);
    }

    @Test
    void overshootAndSteadyStateOffset() {
        ResponseMetrics m = StepResponseAnalyzer.analyze(pull(4, 160, new MapFn() {
            public double map(double t) {
                double base = 165 - 65 * Math.exp(-t / 0.3);   // settles 5 kPa high
                double bump = t > 0.6 && t < 1.4 ? 12 * Math.sin((t - 0.6) / 0.8 * Math.PI) : 0;
                return base + bump;
            }
        }, 50), cfg(), 0, 100);
        assertTrue(m.reached);
        assertTrue(m.overshootKpa > 12, "overshoot " + m.overshootKpa);
        assertEquals(5, m.steadyStateErrorKpa, 1.2);
    }

    @Test
    void sustainedOscillationIsDetected() {
        ResponseMetrics m = StepResponseAnalyzer.analyze(pull(5, 160, new MapFn() {
            public double map(double t) { return 160 + (t > 0.3 ? 10 * Math.sin(2 * Math.PI * t / 0.8) : -30); }
        }, 50), cfg(), 0, 100);
        assertTrue(m.reached);
        assertTrue(m.oscillationAmplitudeKpa > 7, "amp " + m.oscillationAmplitudeKpa);
        assertTrue(m.oscillationCycles >= 3, "cycles " + m.oscillationCycles);
        assertEquals(0.8, m.oscillationPeriodSec, 0.15);
    }

    @Test
    void neverReachedWithSaturatedValve() {
        ResponseMetrics m = StepResponseAnalyzer.analyze(pull(3, 200, new MapFn() {
            public double map(double t) { return 170; }
        }, 100), cfg(), 0, 100);
        assertFalse(m.reached);
        assertEquals(1.0, m.saturatedHighFraction, 1e-9);
        assertTrue(Double.isNaN(m.riseTimeSec));
    }

    @Test
    void noTargetMeansNoMetrics() {
        Pull p = pull(2, Double.NaN, new MapFn() {
            public double map(double t) { return 150; }
        }, 40);
        ResponseMetrics m = StepResponseAnalyzer.analyze(p, cfg(), 0, 100);
        assertFalse(m.hasTarget);
    }

    @Test
    void reportsWhereAndWhenTheTargetArrived() {
        Pull p = pull(4, 160, new MapFn() {
            public double map(double t) { return 160 - 60 * Math.exp(-t / 0.4); }
        }, 50);
        ResponseMetrics m = StepResponseAnalyzer.analyze(p, cfg(), 0, 100);
        assertTrue(m.reached);
        // MAP passes 155 kPa at t = 0.4 * ln(12) = 0.99 s; rpm = 3000 + 800 t
        assertEquals(0.99, m.spoolSec, 0.06);
        assertEquals(3000 + 800 * 0.99, m.reachRpm, 60);
        assertTrue(m.summary().contains("after WOT"));
        // the fixed-target helper used for the open-loop reference agrees
        double[] r = StepResponseAnalyzer.reach(p, 160, 5, 1800);
        assertNotNull(r);
        assertEquals(m.reachRpm, r[0], 1e-9);
        assertEquals(m.spoolSec, r[1], 1e-9);
        assertNull(StepResponseAnalyzer.reach(p, 190, 5, 1800));
    }
}
