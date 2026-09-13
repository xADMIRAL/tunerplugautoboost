package io.github.xadmiral.boostautotune.core.sweep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings of a torque-proxy sweep. Candidates are <em>offsets</em> in degrees added to the
 * current table in the rows at or above {@code minLoad}; the offset 0 is the baseline and is
 * always part of the sweep.
 */
public final class SweepConfig {
    public SweepKind kind = SweepKind.VVT;
    public List<Double> candidateOffsets = new ArrayList<Double>();
    /** Repeat the whole sweep this many times (the second pass runs in reverse order to cancel drift). */
    public int passes = 1;
    /** Only rows whose load bin is at or above this get the offset (WOT rows). */
    public double minLoad = 100;
    public double minRpm = 2000;
    public double maxRpm = 7000;
    /** Gear the pulls must be made in (0 = any, but then keep it the same yourself). */
    public int gear = 0;
    public double wotLoadThreshold = 80;
    public double minCltC = 70;
    /** Regression window for dRPM/dt. */
    public double accelWindowSec = 0.3;
    /** Ignore this long after the throttle opens. */
    public double accelSettleSec = 0.4;
    public int minSamplesPerBin = 4;
    /** Smallest torque gain (percent) that justifies changing a cell. */
    public double minGainPct = 1.0;
    /** Also require the gain to exceed the measurement noise (standard error of both candidates). */
    public boolean requireAboveNoise = true;
    /** Adjacent RPM columns of the result may not differ by more than this (degrees). */
    public double smoothingMaxStepDeg = 5;
    /** Absolute limits of the table values written. */
    public double absoluteMin = 0;
    public double absoluteMax = 45;
    /** Ignition: prefer the least advance whose torque is within this many percent of the best (MBT rule). */
    public double mbtPlateauPct = 0.5;
    /** Ignition: knock retard at or above this marks the cell as knock-limited. */
    public double knockRetardTriggerDeg = 0.5;
    /** Ignition: knock retard at or above this aborts the session. */
    public double knockAbortRetardDeg = 3.0;
    /** Ignition: knock sensor level at or above this counts as knock (NaN = ignore the level channel). */
    public double knockLevelTrigger = Double.NaN;
    /** Ignition: cells that knocked are capped this far below the advance that knocked. */
    public double knockCapMarginDeg = 2.0;
    /** Ignition: abort when AFR at WOT is leaner than this (NaN disables the guard). */
    public double maxWotAfr = 13.0;
    /** Ignition: never advance a cell more than this above its original value in one session. */
    public double maxAdvanceOverOriginalDeg = 4.0;
    /** Safety abort (same as the boost tuner). */
    public double maxBoostKpa = 200;
    public double minPullDurationSec = 1.2;
    public double autoEndRunIdleSec = 8;

    public static SweepConfig vvtDefaults() {
        SweepConfig c = new SweepConfig();
        c.kind = SweepKind.VVT;
        c.candidateOffsets = new ArrayList<Double>(Arrays.asList(0.0, -10.0, -5.0, 5.0, 10.0));
        c.passes = 1;
        c.minLoad = 100;
        c.smoothingMaxStepDeg = 10;
        c.absoluteMin = 0;
        c.absoluteMax = 45;
        c.minGainPct = 1.0;
        return c;
    }

    public static SweepConfig ignitionDefaults() {
        SweepConfig c = new SweepConfig();
        c.kind = SweepKind.IGNITION;
        c.candidateOffsets = new ArrayList<Double>(Arrays.asList(0.0, -2.0, 2.0, 4.0));
        c.passes = 2;
        c.minLoad = 100;
        c.smoothingMaxStepDeg = 3;
        c.absoluteMin = -10;
        c.absoluteMax = 40;
        c.minGainPct = 0.7;
        return c;
    }

    public SweepConfig copy() {
        SweepConfig c = new SweepConfig();
        c.kind = kind;
        c.candidateOffsets = new ArrayList<Double>(candidateOffsets);
        c.passes = passes;
        c.minLoad = minLoad;
        c.minRpm = minRpm;
        c.maxRpm = maxRpm;
        c.gear = gear;
        c.wotLoadThreshold = wotLoadThreshold;
        c.minCltC = minCltC;
        c.accelWindowSec = accelWindowSec;
        c.accelSettleSec = accelSettleSec;
        c.minSamplesPerBin = minSamplesPerBin;
        c.minGainPct = minGainPct;
        c.requireAboveNoise = requireAboveNoise;
        c.smoothingMaxStepDeg = smoothingMaxStepDeg;
        c.absoluteMin = absoluteMin;
        c.absoluteMax = absoluteMax;
        c.mbtPlateauPct = mbtPlateauPct;
        c.knockRetardTriggerDeg = knockRetardTriggerDeg;
        c.knockAbortRetardDeg = knockAbortRetardDeg;
        c.knockLevelTrigger = knockLevelTrigger;
        c.knockCapMarginDeg = knockCapMarginDeg;
        c.maxWotAfr = maxWotAfr;
        c.maxAdvanceOverOriginalDeg = maxAdvanceOverOriginalDeg;
        c.maxBoostKpa = maxBoostKpa;
        c.minPullDurationSec = minPullDurationSec;
        c.autoEndRunIdleSec = autoEndRunIdleSec;
        return c;
    }

    public List<String> validate() {
        List<String> p = new ArrayList<String>();
        if (candidateOffsets.isEmpty()) {
            p.add("At least one candidate offset is required");
        }
        if (!candidateOffsets.contains(0.0)) {
            p.add("The candidate list must contain 0 (the baseline)");
        }
        if (passes < 1) {
            p.add("passes must be at least 1");
        }
        if (minRpm >= maxRpm) {
            p.add("minRpm must be below maxRpm");
        }
        if (absoluteMin >= absoluteMax) {
            p.add("absoluteMin must be below absoluteMax");
        }
        if (kind == SweepKind.IGNITION) {
            for (double o : candidateOffsets) {
                if (o > maxAdvanceOverOriginalDeg) {
                    p.add("Candidate +" + o + " exceeds the session advance limit of " + maxAdvanceOverOriginalDeg + " degrees");
                }
            }
        }
        return p;
    }

    /** Candidate order of a pass: baseline, then retard side, then advance side ascending; reversed on even passes. */
    public List<Double> orderedCandidates(int pass) {
        List<Double> sorted = new ArrayList<Double>(candidateOffsets);
        java.util.Collections.sort(sorted);
        List<Double> out = new ArrayList<Double>();
        out.add(0.0);
        for (double o : sorted) {
            if (o < 0) {
                out.add(o);
            }
        }
        for (double o : sorted) {
            if (o > 0) {
                out.add(o);
            }
        }
        if (pass % 2 == 1) {
            java.util.Collections.reverse(out);
        }
        return out;
    }
}
