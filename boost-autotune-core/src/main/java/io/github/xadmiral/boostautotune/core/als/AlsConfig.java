package io.github.xadmiral.boostautotune.core.als;

import java.util.ArrayList;
import java.util.List;

/**
 * Anti-lag autotune settings. Three of them are what the driver picks: the boost to hold off
 * throttle ({@link #targetKpa}), the engine speed the anti-lag must not let the engine drop
 * below ({@link #holdRpm}) and for how long one activation may hold it ({@link #holdSec}).
 * Ignition retard is the knob for boost, idle-valve air the knob for the RPM hold; the ECU's
 * maximum activation time is written from {@link #holdSec}. Heat and stalling are watched all
 * the time. Everything else is an advanced setting.
 */
public final class AlsConfig {
    /** Boost to hold off throttle while ALS is active, kPa absolute. */
    public double targetKpa = 130;
    public double tolKpa = 8;
    /** Engine speed the anti-lag must hold off throttle: idle-valve air is added until the RPM stays above it. */
    public double holdRpm = 3000;
    public double holdTolRpm = 150;
    /** Seconds one activation may hold boost and RPM (written to the ECU's maximum ALS time). */
    public double holdSec = 3;
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

    /** Tune the second knob for the RPM hold (and give it back where the anti-lag makes too much boost). */
    public boolean tuneAir = true;
    /** Idle valve (stepper steps or PWM duty): step per run and limits. */
    public double airStep = 10;
    public double airMin = 20;
    public double airMax = 160;
    /** With drive-by-wire the second knob is the throttle opening during ALS, in % TPS: step per run and limits. */
    public double throttleStepPct = 2.0;
    public double throttleMinPct = 0;
    public double throttleMaxPct = 20;
    /** How the second knob is called in reports; the plugin sets it from the ECU ("idle valve air" / "throttle opening"). */
    public String airLabel = "idle valve air";

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

    /** ALS cut-off RPM to write to the ECU: comfortably below the hold RPM, above the stall guard. */
    public double ecuMinRpm() {
        return Math.max(stallRpm + 200, Math.min(holdRpm - 200, holdRpm - 700));
    }

    public AlsConfig copy() {
        AlsConfig c = new AlsConfig();
        c.targetKpa = targetKpa;
        c.tolKpa = tolKpa;
        c.holdRpm = holdRpm;
        c.holdTolRpm = holdTolRpm;
        c.holdSec = holdSec;
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
        c.throttleStepPct = throttleStepPct;
        c.throttleMinPct = throttleMinPct;
        c.throttleMaxPct = throttleMaxPct;
        c.airLabel = airLabel;
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
        if (holdRpm <= stallRpm + 300) {
            p.add("Hold RPM must be well above the stall guard");
        }
        if (holdRpm > maxRpm) {
            p.add("Hold RPM must not exceed the maximum RPM");
        }
        if (holdSec <= 0 || holdSec > 15) {
            p.add("Hold time must be between 0 and 15 s");
        }
        if (throttleStepPct <= 0 || throttleMinPct >= throttleMaxPct) {
            p.add("DBW throttle: step must be positive and the minimum below the maximum");
        }
        return p;
    }
}
