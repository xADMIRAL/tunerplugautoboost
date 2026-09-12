package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Sample;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Streaming filter that decides whether a sample is usable for learning, in the spirit of
 * TunerStudio's VE Analyze filters: WOT only, warm engine, RPM window, no boost cut,
 * settled after throttle opening and MAP not changing quickly.
 */
public final class SampleClassifier {
    private final AutotuneConfig cfg;
    private final Deque<Sample> window = new ArrayDeque<Sample>();
    private double wotOnsetTime = Double.NaN;
    private double lastMapSlope;
    private double lastDutySlope;

    public SampleClassifier(AutotuneConfig cfg) {
        this.cfg = cfg;
    }

    public void reset() {
        window.clear();
        wotOnsetTime = Double.NaN;
        lastMapSlope = 0;
        lastDutySlope = 0;
    }

    /** MAP slope (kPa/s) estimated over the last samples, updated on every classify call. */
    public double lastMapSlope() {
        return lastMapSlope;
    }

    public boolean isWot(Sample s) {
        return s.tps >= cfg.wotLoadThreshold;
    }

    public SampleState classify(Sample s) {
        // slope estimate over a short window
        window.addLast(s);
        while (window.size() > Math.max(2, cfg.slopeWindow)) {
            window.removeFirst();
        }
        Sample first = window.peekFirst();
        double dt = s.timeSec - first.timeSec;
        lastMapSlope = dt > 1e-6 ? (s.map - first.map) / dt : 0.0;
        lastDutySlope = dt > 1e-6 && !Double.isNaN(s.duty) && !Double.isNaN(first.duty) ? (s.duty - first.duty) / dt : 0.0;

        if (s.map > cfg.maxBoostKpa) {
            return SampleState.OVERBOOST;
        }
        if (!isWot(s)) {
            wotOnsetTime = Double.NaN;
            return SampleState.NOT_WOT;
        }
        if (Double.isNaN(wotOnsetTime)) {
            wotOnsetTime = s.timeSec;
        }
        if (s.boostCut) {
            return SampleState.BOOST_CUT;
        }
        if (s.clt < cfg.minCltC) {
            return SampleState.COLD;
        }
        if (s.rpm < cfg.minRpm || s.rpm > cfg.maxRpm) {
            return SampleState.RPM_OUT_OF_RANGE;
        }
        if (s.timeSec - wotOnsetTime < cfg.settleDelaySec) {
            return SampleState.TRANSIENT;
        }
        if (Math.abs(lastMapSlope) > cfg.maxMapSlopeKpaPerSec) {
            return SampleState.TRANSIENT;
        }
        if (Math.abs(lastDutySlope) > cfg.maxDutySlopePctPerSec) {
            return SampleState.TRANSIENT;
        }
        return SampleState.STEADY;
    }
}
