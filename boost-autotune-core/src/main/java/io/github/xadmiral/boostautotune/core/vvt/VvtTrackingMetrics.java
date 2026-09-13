package io.github.xadmiral.boostautotune.core.vvt;

import java.util.Locale;

/** How well the cam followed its target over a run. */
public final class VvtTrackingMetrics {
    public final int samples;
    public final int activeSamples;
    public final double meanErrorDeg;
    public final double meanAbsErrorDeg;
    public final double rmsErrorDeg;
    public final double withinTolFraction;
    /** Mean time the cam trails the target while it moves (seconds); negative = leading. */
    public final double lagSec;
    public final double oscillationAmplitudeDeg;
    public final double oscillationCycles;
    public final double oscillationPeriodSec;

    VvtTrackingMetrics(int samples, int activeSamples, double meanErrorDeg, double meanAbsErrorDeg, double rmsErrorDeg,
                       double withinTolFraction, double lagSec, double oscillationAmplitudeDeg,
                       double oscillationCycles, double oscillationPeriodSec) {
        this.samples = samples;
        this.activeSamples = activeSamples;
        this.meanErrorDeg = meanErrorDeg;
        this.meanAbsErrorDeg = meanAbsErrorDeg;
        this.rmsErrorDeg = rmsErrorDeg;
        this.withinTolFraction = withinTolFraction;
        this.lagSec = lagSec;
        this.oscillationAmplitudeDeg = oscillationAmplitudeDeg;
        this.oscillationCycles = oscillationCycles;
        this.oscillationPeriodSec = oscillationPeriodSec;
    }

    public String summary() {
        return String.format(Locale.US,
                "%d active samples of %d: mean err %+.2f deg, |err| %.2f, rms %.2f, within tol %.0f%%, lag %.3f s, oscillation %.1f deg x%.1f%s",
                activeSamples, samples, meanErrorDeg, meanAbsErrorDeg, rmsErrorDeg, withinTolFraction * 100, lagSec,
                oscillationAmplitudeDeg, oscillationCycles,
                Double.isNaN(oscillationPeriodSec) ? "" : String.format(Locale.US, " (%.2fs)", oscillationPeriodSec));
    }
}
