package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.PidGains;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class PidTunerTest {
    private static ResponseMetrics metrics(boolean reached, double overshoot, double ss, double oscAmp, double cycles,
                                           double rise, double satHi) {
        return new ResponseMetrics(new Pull(), true, reached, 160, 160 + overshoot, overshoot, rise, ss, Math.abs(ss),
                oscAmp, cycles, 0.8, satHi, 0, 20);
    }

    private static AutotuneConfig cfg() {
        AutotuneConfig c = new AutotuneConfig();
        c.pidStepFraction = 0.25;
        c.overshootThresholdKpa = 8;
        c.steadyStateTolKpa = 5;
        c.oscillationAmplitudeKpa = 6;
        c.maxRiseTimeSec = 1.5;
        return c;
    }

    @Test
    void oscillationLowersPAndI() {
        PidSuggestion s = PidTuner.suggest(new PidGains(100, 100, 40),
                Collections.singletonList(metrics(true, 10, 0, 9, 3, 0.8, 0)), cfg(), true);
        assertEquals(75, s.after.p, 1e-9);
        assertEquals(75, s.after.i, 1e-9);
        assertEquals(35, s.after.d, 1e-9);
        assertFalse(s.converged);
    }

    @Test
    void ringingOvershootSoftensAndAddsDerivative() {
        PidSuggestion s = PidTuner.suggest(new PidGains(100, 100, 20),
                Arrays.asList(metrics(true, 12, 1, 4, 1.0, 0.8, 0), metrics(true, 14, 0, 3, 1.0, 0.9, 0)), cfg(), true);
        assertEquals(85, s.after.p, 1e-9);
        assertEquals(88, s.after.i, 1e-9);
        assertEquals(25, s.after.d, 1e-9);
        assertFalse(s.transientOvershoot);
    }

    @Test
    void singleBumpOvershootWithFeedForwardKeepsGains() {
        PidSuggestion s = PidTuner.suggest(new PidGains(100, 100, 20),
                Arrays.asList(metrics(true, 12, 1, 2, 0.5, 0.8, 0), metrics(true, 14, 0, 1, 0, 0.9, 0)), cfg(), true);
        assertEquals(100, s.after.p, 1e-9);
        assertFalse(s.converged);
        assertEquals(100, s.after.i, 1e-9);
        assertEquals(20, s.after.d, 1e-9);
        assertTrue(s.transientOvershoot);
        // without a bias table the classic rule applies
        PidSuggestion noFf = PidTuner.suggest(new PidGains(100, 100, 20),
                Arrays.asList(metrics(true, 12, 1, 2, 0.5, 0.8, 0)), cfg(), false);
        assertEquals(85, noFf.after.p, 1e-9);
    }

    @Test
    void lenientModeWidensThresholds() {
        PidSuggestion strict = PidTuner.suggest(new PidGains(100, 100, 0),
                Collections.singletonList(metrics(true, 9, 1, 1, 0, 0.9, 0)), cfg(), false, false);
        assertFalse(strict.converged);
        PidSuggestion lenient = PidTuner.suggest(new PidGains(100, 100, 0),
                Collections.singletonList(metrics(true, 9, 1, 1, 0, 0.9, 0)), cfg(), false, true);
        assertTrue(lenient.converged);
    }

    @Test
    void steadyStateErrorRaisesIntegral() {
        PidSuggestion ff = PidTuner.suggest(new PidGains(100, 80, 0),
                Collections.singletonList(metrics(true, 2, -7, 1, 0, 0.9, 0)), cfg(), true);
        assertEquals(90, ff.after.i, 1e-9);
        PidSuggestion noFf = PidTuner.suggest(new PidGains(100, 80, 0),
                Collections.singletonList(metrics(true, 2, -7, 1, 0, 0.9, 0)), cfg(), false);
        assertEquals(100, noFf.after.i, 1e-9);
        assertEquals(0, noFf.after.d, 1e-9);
    }

    @Test
    void slowResponseRaisesP() {
        PidSuggestion s = PidTuner.suggest(new PidGains(100, 100, 0),
                Collections.singletonList(metrics(true, 1, 0, 0, 0, 2.5, 0)), cfg(), true);
        assertEquals(115, s.after.p, 1e-9);
    }

    @Test
    void goodResponseConverges() {
        PidSuggestion s = PidTuner.suggest(new PidGains(100, 100, 0),
                Collections.singletonList(metrics(true, 3, 1, 1, 0, 0.9, 0)), cfg(), true);
        assertTrue(s.converged);
        assertFalse(s.changed());
    }

    @Test
    void unreachableTargetKeepsGains() {
        PidSuggestion s = PidTuner.suggest(new PidGains(100, 100, 0),
                Collections.singletonList(metrics(false, 0, Double.NaN, 0, 0, Double.NaN, 0.9)), cfg(), true);
        assertTrue(s.unreachable);
        assertFalse(s.changed());
    }

    @Test
    void gainsAreClampedAndRounded() {
        AutotuneConfig c = cfg();
        c.pMax = 110;
        c.pidDecimals = 0;
        PidSuggestion s = PidTuner.suggest(new PidGains(100, 100, 0),
                Collections.singletonList(metrics(true, 1, 0, 0, 0, 2.5, 0)), c, true);
        assertEquals(110, s.after.p, 1e-9);
        assertEquals(new PidGains(100, 50, 25).toString(), PidTuner.softStart(new PidGains(100, 100, 50), c).toString());
    }
}
