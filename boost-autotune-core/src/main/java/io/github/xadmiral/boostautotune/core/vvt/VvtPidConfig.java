package io.github.xadmiral.boostautotune.core.vvt;

/** Thresholds (degrees of cam angle) and limits for the VVT closed-loop assessment. */
public final class VvtPidConfig {
    /** Only samples with the cam commanded at least this far from its rest position count. */
    public double activeMinTargetDeg = 2;
    public double minRpm = 1200;
    public double minCltC = 65;
    /** Oscillation amplitude that calls for softer gains. */
    public double oscillationDeg = 3;
    public double oscillationDeadbandDeg = 1.0;
    /** Mean absolute error considered acceptable. */
    public double steadyStateTolDeg = 2;
    /** The cam may trail a moving target by this many seconds before P is raised. */
    public double lagTolSec = 0.2;
    /** Oscillations slower than this are treated as sluggishness, not ringing. */
    public double ringingMaxPeriodSec = 0.7;
    /** Target derivative below which a sample counts as "target steady" for oscillation detection. */
    public double steadyTargetDegPerSec = 15;
    public int minSamples = 300;
    public double pidStepFraction = 0.25;
    public boolean tuneP = true;
    public boolean tuneI = true;
    public boolean tuneD = true;
    public double pMin = 1, pMax = 200, iMin = 0, iMax = 200, dMin = 0, dMax = 200;
    public int pidDecimals = 0;
    public int runsRequired = 2;
    /** Seconds of driving the plugin suggests per run. */
    public double suggestedRunSec = 90;

    public VvtPidConfig copy() {
        VvtPidConfig c = new VvtPidConfig();
        c.activeMinTargetDeg = activeMinTargetDeg;
        c.minRpm = minRpm;
        c.minCltC = minCltC;
        c.oscillationDeg = oscillationDeg;
        c.oscillationDeadbandDeg = oscillationDeadbandDeg;
        c.steadyStateTolDeg = steadyStateTolDeg;
        c.lagTolSec = lagTolSec;
        c.ringingMaxPeriodSec = ringingMaxPeriodSec;
        c.steadyTargetDegPerSec = steadyTargetDegPerSec;
        c.minSamples = minSamples;
        c.pidStepFraction = pidStepFraction;
        c.tuneP = tuneP;
        c.tuneI = tuneI;
        c.tuneD = tuneD;
        c.pMin = pMin; c.pMax = pMax; c.iMin = iMin; c.iMax = iMax; c.dMin = dMin; c.dMax = dMax;
        c.pidDecimals = pidDecimals;
        c.runsRequired = runsRequired;
        c.suggestedRunSec = suggestedRunSec;
        return c;
    }
}
