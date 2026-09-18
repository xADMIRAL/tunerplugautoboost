package io.github.xadmiral.boostautotune.core.knock;

import java.util.ArrayList;
import java.util.List;

/**
 * Knock sensor calibration settings. The goal is a knock input that reads the engine's normal
 * noise at a usable level ({@link #noiseTargetPct} at the noisiest RPM) with every cylinder alike,
 * and a threshold curve that sits a margin ({@link #marginPct}) above that noise so only real
 * knock pulls timing. The survey runs are full-throttle pulls on a timing map you trust.
 */
public final class KnockCalConfig {
    // ---- what you want ----
    /** Where the noisiest measured RPM bin's 95th percentile should sit after the gains are set, % of full scale. */
    public double noiseTargetPct = 40;
    /** Band around the target inside which the gains are left alone. */
    public double noiseBandLowPct = 30;
    public double noiseBandHighPct = 55;
    /** Threshold above the measured noise, in % of the noise level. */
    public double marginPct = 30;
    /** With per-cylinder knock channels: equalise the cylinders with their own gains. */
    public boolean balanceCylinders = true;
    /** Cylinders whose noise differs more than this (% of the loudest) get their gain moved. */
    public double cylinderImbalancePct = 20;

    // ---- which samples count ----
    /** Ignition load at or above this counts (the ECU's own knock minimum load is used when known). */
    public double minLoad = 80;
    /** Throttle at or above this counts when no load channel is bound. */
    public double wotTps = 85;
    public double minRpm = 1500;
    public double maxRpm = 7000;
    /** Take the load and RPM window from the ECU's knock settings when they are bound. */
    public boolean windowFromEcu = true;
    public double minCltC = 70;
    public int minSamplesPerBin = 8;

    // ---- knobs ----
    /** A gain never moves by more than this factor in one run. */
    public double maxGainFactorPerRun = 2.0;
    public double minThresholdPct = 10;
    public double maxThresholdPct = 95;
    /** Samples above the threshold allowed per bin in a verification run, % of the bin's samples (one stray sample is always allowed). */
    public double maxFalsePct = 2.0;
    /** Threshold raise on a bin that false-triggered, % of its value (at least 3 points). */
    public double raiseOnFalsePct = 10;
    /** A sample this many times the bin median (plus 3 points) is a spike (possible knock), not noise. */
    public double spikeFactor = 1.35;

    // ---- guards ----
    public double maxBoostKpa = 200;
    public int runsRequired = 2;
    public double autoEndRunIdleSec = 8;

    public KnockCalConfig copy() {
        KnockCalConfig c = new KnockCalConfig();
        c.noiseTargetPct = noiseTargetPct;
        c.noiseBandLowPct = noiseBandLowPct;
        c.noiseBandHighPct = noiseBandHighPct;
        c.marginPct = marginPct;
        c.balanceCylinders = balanceCylinders;
        c.cylinderImbalancePct = cylinderImbalancePct;
        c.minLoad = minLoad;
        c.wotTps = wotTps;
        c.minRpm = minRpm;
        c.maxRpm = maxRpm;
        c.windowFromEcu = windowFromEcu;
        c.minCltC = minCltC;
        c.minSamplesPerBin = minSamplesPerBin;
        c.maxGainFactorPerRun = maxGainFactorPerRun;
        c.minThresholdPct = minThresholdPct;
        c.maxThresholdPct = maxThresholdPct;
        c.maxFalsePct = maxFalsePct;
        c.raiseOnFalsePct = raiseOnFalsePct;
        c.spikeFactor = spikeFactor;
        c.maxBoostKpa = maxBoostKpa;
        c.runsRequired = runsRequired;
        c.autoEndRunIdleSec = autoEndRunIdleSec;
        return c;
    }

    public List<String> validate() {
        List<String> p = new ArrayList<String>();
        if (noiseBandLowPct >= noiseBandHighPct || noiseTargetPct < noiseBandLowPct || noiseTargetPct > noiseBandHighPct) {
            p.add("Noise target must lie inside its band and the band's low end below its high end");
        }
        if (noiseTargetPct <= 5 || noiseBandHighPct >= 90) {
            p.add("Noise target must be between 5 and 90 % of full scale");
        }
        if (marginPct < 5 || marginPct > 200) {
            p.add("Threshold margin must be between 5 and 200 %");
        }
        if (minRpm >= maxRpm) {
            p.add("Minimum RPM must be below maximum RPM");
        }
        if (minThresholdPct >= maxThresholdPct || minThresholdPct < 0 || maxThresholdPct > 100) {
            p.add("Threshold limits must be within 0..100 with the minimum below the maximum");
        }
        if (maxGainFactorPerRun <= 1) {
            p.add("Maximum gain change per run must be above 1");
        }
        if (minSamplesPerBin < 1) {
            p.add("Samples per bin must be at least 1");
        }
        if (runsRequired < 1) {
            p.add("At least one good run is required");
        }
        return p;
    }
}
