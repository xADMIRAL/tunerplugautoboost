package io.github.xadmiral.boostautotune.core.learn;

import java.util.Locale;

/** Closed-loop quality figures of one pull. */
public final class ResponseMetrics {
    public final Pull pull;
    public final boolean hasTarget;
    public final boolean reached;
    public final double targetKpa;        // median target while active
    public final double peakKpa;
    public final double overshootKpa;     // max(map - target) after first reach, >= 0
    public final double riseTimeSec;      // NaN when never reached
    public final double steadyStateErrorKpa; // mean(map - target) in the settled window, NaN if none
    public final double meanAbsErrorKpa;
    public final double oscillationAmplitudeKpa;
    public final double oscillationCycles;
    public final double oscillationPeriodSec;
    public final double saturatedHighFraction;
    public final double saturatedLowFraction;
    public final int settledSamples;

    ResponseMetrics(Pull pull, boolean hasTarget, boolean reached, double targetKpa, double peakKpa,
                    double overshootKpa, double riseTimeSec, double steadyStateErrorKpa, double meanAbsErrorKpa,
                    double oscillationAmplitudeKpa, double oscillationCycles, double oscillationPeriodSec,
                    double saturatedHighFraction, double saturatedLowFraction, int settledSamples) {
        this.pull = pull;
        this.hasTarget = hasTarget;
        this.reached = reached;
        this.targetKpa = targetKpa;
        this.peakKpa = peakKpa;
        this.overshootKpa = overshootKpa;
        this.riseTimeSec = riseTimeSec;
        this.steadyStateErrorKpa = steadyStateErrorKpa;
        this.meanAbsErrorKpa = meanAbsErrorKpa;
        this.oscillationAmplitudeKpa = oscillationAmplitudeKpa;
        this.oscillationCycles = oscillationCycles;
        this.oscillationPeriodSec = oscillationPeriodSec;
        this.saturatedHighFraction = saturatedHighFraction;
        this.saturatedLowFraction = saturatedLowFraction;
        this.settledSamples = settledSamples;
    }

    public String summary() {
        if (!hasTarget) {
            return "no closed-loop target during pull";
        }
        return String.format(Locale.US,
                "target %.0f kPa, peak %.1f, %s, overshoot %.1f, rise %.2fs, ss err %+.1f, |err| %.1f, osc %.1f kPa x%.1f%s, sat hi %.0f%%",
                targetKpa, peakKpa, reached ? "reached" : "NOT reached", overshootKpa, riseTimeSec,
                steadyStateErrorKpa, meanAbsErrorKpa, oscillationAmplitudeKpa, oscillationCycles,
                Double.isNaN(oscillationPeriodSec) ? "" : String.format(Locale.US, " (%.2fs)", oscillationPeriodSec),
                saturatedHighFraction * 100);
    }

    @Override
    public String toString() {
        return summary();
    }
}
