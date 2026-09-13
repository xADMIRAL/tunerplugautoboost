package io.github.xadmiral.boostautotune.core.als;

import java.util.ArrayList;
import java.util.List;

/**
 * Anti-lag autotune settings. The goal is a chosen manifold pressure while the throttle is closed
 * and the anti-lag system is active, reached with the least ignition retard (heat) and, as a
 * second knob, extra air through the idle valve.
 */
public final class AlsConfig {
    /** Boost to hold off throttle while ALS is active, kPa absolute. */
    public double targetKpa = 130;
    public double tolKpa = 8;
    /** Throttle at or below this counts as "off throttle" when the ECU does not report an ALS flag. */
    public double offThrottleTps = 12;
    /** Throttle above this ends an ALS event (driver back on the power). */
    public double onThrottleTps = 50;
    public double minRpm = 2000;
    public double maxRpm = 7500;
    /** Ignore the first part of every event while the boost settles. */
    public double settleSec = 0.6;
    public double minEventSec = 1.2;
    public double minSamplesPerColumn = 6;

    /** Timing (absolute, negative = ATDC) step per run and its limits. */
    public double timingStepDeg = 2.0;
    public double minTimingDeg = -35;
    public double maxTimingDeg = -5;
    /** Rows of the ALS timing table (TPS axis) at or below this get the change. */
    public double maxRowTps = 20;

    /** Second knob: extra air through the idle valve once the timing is at its retard limit. */
    public boolean tuneAir = true;
    public double airStep = 10;
    public double airMin = 20;
    public double airMax = 160;

    // ---- guards ----
    public double maxMatC = 70;
    public double abortMatC = 80;
    public double stallRpm = 1200;
    public double maxBoostKpa = 200;
    /** Total ALS-active seconds allowed in one run before the plugin asks for a cool-down. */
    public double maxActiveSecPerRun = 30;
    /** After a re-spool this fast the mission is accomplished even if the off-throttle boost is a bit low. */
    public double respoolTargetKpa = 140;
    public double respoolMaxSec = 3.0;

    public int runsRequired = 2;
    public double autoEndRunIdleSec = 10;

    public AlsConfig copy() {
        AlsConfig c = new AlsConfig();
        c.targetKpa = targetKpa;
        c.tolKpa = tolKpa;
        c.offThrottleTps = offThrottleTps;
        c.onThrottleTps = onThrottleTps;
        c.minRpm = minRpm;
        c.maxRpm = maxRpm;
        c.settleSec = settleSec;
        c.minEventSec = minEventSec;
        c.minSamplesPerColumn = minSamplesPerColumn;
        c.timingStepDeg = timingStepDeg;
        c.minTimingDeg = minTimingDeg;
        c.maxTimingDeg = maxTimingDeg;
        c.maxRowTps = maxRowTps;
        c.tuneAir = tuneAir;
        c.airStep = airStep;
        c.airMin = airMin;
        c.airMax = airMax;
        c.maxMatC = maxMatC;
        c.abortMatC = abortMatC;
        c.stallRpm = stallRpm;
        c.maxBoostKpa = maxBoostKpa;
        c.maxActiveSecPerRun = maxActiveSecPerRun;
        c.respoolTargetKpa = respoolTargetKpa;
        c.respoolMaxSec = respoolMaxSec;
        c.runsRequired = runsRequired;
        c.autoEndRunIdleSec = autoEndRunIdleSec;
        return c;
    }

    public List<String> validate() {
        List<String> p = new ArrayList<String>();
        if (targetKpa >= maxBoostKpa) {
            p.add("Off-throttle target must be below the hard limit");
        }
        if (minTimingDeg >= maxTimingDeg) {
            p.add("Minimum timing must be below maximum timing");
        }
        if (timingStepDeg <= 0) {
            p.add("Timing step must be positive");
        }
        if (offThrottleTps >= onThrottleTps) {
            p.add("Off-throttle TPS must be below on-throttle TPS");
        }
        if (maxMatC >= abortMatC) {
            p.add("MAT warning must be below MAT abort");
        }
        return p;
    }
}
