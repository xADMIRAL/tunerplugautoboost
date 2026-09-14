package io.github.xadmiral.boostautotune.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Every tunable of the autotune in one place. Plain public fields so the plugin can bind
 * them to Swing controls and persist them as properties without reflection tricks.
 * All pressures are kPa absolute, durations are seconds, duties are percent.
 */
public final class AutotuneConfig {

    // ---- Targets -------------------------------------------------------------------------
    /** Boost targets to tune, in order. Each stage must converge before the next one starts. */
    public List<Double> targetStagesKpa = new ArrayList<Double>();
    /** Boost when the wastegate is fully open (spring pressure). Used below the spool RPM. */
    public double wastegateKpa = 130;
    /** Target ramps from wastegate pressure at this RPM ... */
    public double spoolStartRpm = 2200;
    /** ... up to the stage target at this RPM. Set both to 0 for a flat target. */
    public double fullTargetRpm = 3200;
    /** Lower the target by this much at the last RPM bin (0 disables the high-RPM taper). */
    public double highRpmTaperKpa = 0;
    /** RPM at which the high-RPM taper starts. */
    public double highRpmTaperStartRpm = 6000;
    /** Rows at or above this load count as wide-open throttle. */
    public double wotLoadThreshold = 80;
    /** Rows below WOT: keep ECU values (false) or scale the target between wastegate and WOT (true). */
    public boolean scalePartThrottleRows = true;
    /** Minimum target written for part throttle rows. */
    public double partThrottleFloorKpa = 100;

    // ---- Safety ----------------------------------------------------------------------------
    /** Hard limit: any sample above this aborts the session. Keep below the ECU overboost cut. */
    public double maxBoostKpa = 200;
    /** Open-loop characterization never plans a duty whose predicted boost exceeds maxBoostKpa - this. */
    public double predictionMarginKpa = 15;
    /** Ignore everything while coolant is below this. */
    public double minCltC = 70;
    /** Only learn between these engine speeds. */
    public double minRpm = 1800;
    public double maxRpm = 7200;

    // ---- Sample filtering ------------------------------------------------------------------
    /** Seconds after WOT onset before samples may count as steady. */
    public double settleDelaySec = 0.6;
    /** |dMAP/dt| must stay below this for a steady sample. */
    public double maxMapSlopeKpaPerSec = 60;
    /** Samples per bias cell needed for a full-authority update. */
    public int minSamplesPerCell = 6;
    /** A WOT event shorter than this is ignored. */
    public double minPullDurationSec = 1.2;
    /** Consider a pull finished when load stays below WOT for this long. */
    public double pullEndHoldSec = 0.4;
    /** |dDuty/dt| must stay below this for a steady sample (closed loop only; open loop duty is constant). */
    public double maxDutySlopePctPerSec = 25;
    /** Smoothing window (samples) for MAP slope estimation. */
    public int slopeWindow = 4;
    /** Delay between a duty change and its effect on MAP; observations pair MAP with the duty this long before. */
    public double plantLagSec = 0.25;

    // ---- Open-loop characterization ---------------------------------------------------------
    /** Run open-loop duty ladder pulls before closing the loop (needs an open-loop duty table). */
    public boolean characterizeFirst = true;
    /** First ladder duty; 0 = minDuty + characterizeStepPct. */
    public double characterizeStartDuty = 20;
    /** Duty increase between ladder runs. */
    public double characterizeStepPct = 15;
    /** Stop climbing once observed peak boost exceeds the highest stage target by this margin. */
    public double characterizeHeadroomKpa = 10;
    /** Minimum number of distinct ladder duties before the bias table is trusted. */
    public int characterizeMinRuns = 2;
    /** Never plan a ladder duty above this (in addition to maxDuty). */
    public double characterizeMaxDuty = 90;

    // ---- Bias / feed-forward learning --------------------------------------------------------
    /** Max change per cell per run once a bias table exists. */
    public double biasMaxStepPct = 15;
    /** The first table built from characterization ignores biasMaxStepPct. */
    public boolean biasInitialFillUnlimited = true;
    /** Extrapolate at most this far above the highest observed duty in a column. */
    public double biasMaxExtrapolationPct = 15;
    /** Default plant gain used when nothing better is known: kPa of boost per percent duty. Deliberately on the
     *  high side: it makes the ladder predict more boost (safer) and extrapolate smaller duty changes. */
    public double defaultGainKpaPerPct = 2.0;
    /** Observation weights are multiplied by this after every run (recent data wins). */
    public double observationDecayPerRun = 0.6;
    /** Weight of a closed-loop steady sample relative to an open-loop one (PID activity adds noise). */
    public double closedLoopObservationWeight = 1.0;
    /** Closed-loop samples teach the plant model only when MAP is within this window below the target. */
    public double closedLoopLearnWindowKpa = 15;
    /** Duty bin width used to cluster observations inside one RPM column. */
    public double dutyBinPct = 2.0;

    // ---- PID assessment ------------------------------------------------------------------------
    public double overshootThresholdKpa = 8;
    public double steadyStateTolKpa = 5;
    public double oscillationAmplitudeKpa = 6;
    public double oscillationDeadbandKpa = 2.5;
    public double maxRiseTimeSec = 1.5;
    /** Relative change applied to a gain per run (0.25 = 25 %). */
    public double pidStepFraction = 0.25;
    public boolean tuneP = true;
    public boolean tuneI = true;
    public boolean tuneD = true;
    /** Start a derivative term (10 % of P) if overshoot persists and D is zero. */
    public boolean allowDerivativeInit = false;
    /** Halve I and D before the first closed-loop run (fresh bias table, be gentle). */
    public boolean softStartClosedLoop = true;
    /** Gain limits in ECU units; the plugin overrides them from the ECU parameter ranges. */
    public double pMin = 1, pMax = 200, iMin = 0, iMax = 200, dMin = 0, dMax = 200;
    public int pidDecimals = 0;

    // ---- Target trimming (unreachable targets) -------------------------------------------------
    public boolean trimUnreachableTargets = true;
    public double saturationDutyMarginPct = 1.5;
    public int minSaturatedSamples = 4;

    // ---- Spool ----------------------------------------------------------------------------------
    /**
     * Tune for the fastest possible spool: the valve is held shut (max duty) wherever the target is
     * out of reach, WOT targets are flat at the stage target above the spool-start RPM (no ramp
     * that would crack the valve open early; below the ECU's closed-loop window the duty is the
     * bias alone, so there is no wind-up), and once the loop is settled the spool trim, P and the
     * closed-loop window are pushed one step at a time while the target keeps arriving earlier
     * without overshoot.
     */
    public boolean fastSpool = true;
    /** A bias cell whose estimate asks for at least this much duty is set to maximum duty (the valve is shut anyway). */
    public double spoolShutDutyPct = 90;
    /** A spool push is kept going only while the target arrives at least this many RPM earlier. */
    public double spoolImproveRpm = 50;
    /** Extra runs on the last stage spent pushing the spool (trim, feed-forward, P, window) once the loop is settled. */
    public int maxSpoolPushesPerStage = 4;
    /** Feed-forward raised above the steady duty around the RPM where the target arrives, per push. */
    public double spoolBoostStepPct = 5;
    /** Adjust the ECU's closed-loop activation window (MS3 "lower limit delta") when it is bound. */
    public boolean tuneClosedLoopWindow = true;
    public double windowStepKpa = 5;
    public double windowMinKpa = 10;
    public double windowMaxKpa = 60;

    // ---- Convergence ------------------------------------------------------------------------------
    /** Closed-loop runs per stage before the stage may be called converged. */
    public int runsRequiredPerStage = 2;
    /** Bias table changes below this are considered settled. */
    public double biasSettledPct = 4;
    /** Automatically consider a run finished after this long without WOT (0 = manual only). */
    public double autoEndRunIdleSec = 8;

    public AutotuneConfig() {
        targetStagesKpa.add(150.0);
    }

    public AutotuneConfig copy() {
        AutotuneConfig c = new AutotuneConfig();
        c.targetStagesKpa = new ArrayList<Double>(targetStagesKpa);
        c.wastegateKpa = wastegateKpa;
        c.spoolStartRpm = spoolStartRpm;
        c.fullTargetRpm = fullTargetRpm;
        c.highRpmTaperKpa = highRpmTaperKpa;
        c.highRpmTaperStartRpm = highRpmTaperStartRpm;
        c.wotLoadThreshold = wotLoadThreshold;
        c.scalePartThrottleRows = scalePartThrottleRows;
        c.partThrottleFloorKpa = partThrottleFloorKpa;
        c.maxBoostKpa = maxBoostKpa;
        c.predictionMarginKpa = predictionMarginKpa;
        c.minCltC = minCltC;
        c.minRpm = minRpm;
        c.maxRpm = maxRpm;
        c.settleDelaySec = settleDelaySec;
        c.maxMapSlopeKpaPerSec = maxMapSlopeKpaPerSec;
        c.minSamplesPerCell = minSamplesPerCell;
        c.minPullDurationSec = minPullDurationSec;
        c.pullEndHoldSec = pullEndHoldSec;
        c.slopeWindow = slopeWindow;
        c.maxDutySlopePctPerSec = maxDutySlopePctPerSec;
        c.plantLagSec = plantLagSec;
        c.closedLoopObservationWeight = closedLoopObservationWeight;
        c.closedLoopLearnWindowKpa = closedLoopLearnWindowKpa;
        c.characterizeFirst = characterizeFirst;
        c.characterizeStartDuty = characterizeStartDuty;
        c.characterizeStepPct = characterizeStepPct;
        c.characterizeHeadroomKpa = characterizeHeadroomKpa;
        c.characterizeMinRuns = characterizeMinRuns;
        c.characterizeMaxDuty = characterizeMaxDuty;
        c.biasMaxStepPct = biasMaxStepPct;
        c.biasInitialFillUnlimited = biasInitialFillUnlimited;
        c.biasMaxExtrapolationPct = biasMaxExtrapolationPct;
        c.defaultGainKpaPerPct = defaultGainKpaPerPct;
        c.observationDecayPerRun = observationDecayPerRun;
        c.dutyBinPct = dutyBinPct;
        c.overshootThresholdKpa = overshootThresholdKpa;
        c.steadyStateTolKpa = steadyStateTolKpa;
        c.oscillationAmplitudeKpa = oscillationAmplitudeKpa;
        c.oscillationDeadbandKpa = oscillationDeadbandKpa;
        c.maxRiseTimeSec = maxRiseTimeSec;
        c.pidStepFraction = pidStepFraction;
        c.tuneP = tuneP;
        c.tuneI = tuneI;
        c.tuneD = tuneD;
        c.allowDerivativeInit = allowDerivativeInit;
        c.softStartClosedLoop = softStartClosedLoop;
        c.pMin = pMin; c.pMax = pMax; c.iMin = iMin; c.iMax = iMax; c.dMin = dMin; c.dMax = dMax;
        c.pidDecimals = pidDecimals;
        c.trimUnreachableTargets = trimUnreachableTargets;
        c.saturationDutyMarginPct = saturationDutyMarginPct;
        c.minSaturatedSamples = minSaturatedSamples;
        c.fastSpool = fastSpool;
        c.spoolShutDutyPct = spoolShutDutyPct;
        c.spoolImproveRpm = spoolImproveRpm;
        c.maxSpoolPushesPerStage = maxSpoolPushesPerStage;
        c.spoolBoostStepPct = spoolBoostStepPct;
        c.tuneClosedLoopWindow = tuneClosedLoopWindow;
        c.windowStepKpa = windowStepKpa;
        c.windowMinKpa = windowMinKpa;
        c.windowMaxKpa = windowMaxKpa;
        c.runsRequiredPerStage = runsRequiredPerStage;
        c.biasSettledPct = biasSettledPct;
        c.autoEndRunIdleSec = autoEndRunIdleSec;
        return c;
    }

    /** Highest stage target, or NaN when no stages are configured. */
    public double maxStageTarget() {
        double m = Double.NaN;
        for (double t : targetStagesKpa) {
            m = Double.isNaN(m) ? t : Math.max(m, t);
        }
        return m;
    }

    /** Sanity checks that would make a session unsafe or meaningless. */
    public List<String> validate() {
        List<String> problems = new ArrayList<String>();
        if (targetStagesKpa.isEmpty()) {
            problems.add("At least one target stage is required");
        }
        for (double t : targetStagesKpa) {
            if (t <= wastegateKpa) {
                problems.add("Target " + t + " kPa is not above wastegate pressure " + wastegateKpa + " kPa");
            }
            if (t >= maxBoostKpa) {
                problems.add("Target " + t + " kPa is not below the hard limit " + maxBoostKpa + " kPa");
            }
        }
        if (maxBoostKpa <= wastegateKpa) {
            problems.add("Hard limit must be above wastegate pressure");
        }
        if (minRpm >= maxRpm) {
            problems.add("minRpm must be below maxRpm");
        }
        if (spoolStartRpm > fullTargetRpm) {
            problems.add("spoolStartRpm must not exceed fullTargetRpm");
        }
        if (pidStepFraction <= 0 || pidStepFraction > 0.5) {
            problems.add("pidStepFraction must be in (0, 0.5]");
        }
        if (characterizeStepPct <= 0) {
            problems.add("characterizeStepPct must be positive");
        }
        if (windowMinKpa >= windowMaxKpa || windowStepKpa <= 0) {
            problems.add("Closed-loop window: min must be below max and the step positive");
        }
        if (spoolImproveRpm < 0 || maxSpoolPushesPerStage < 0) {
            problems.add("Spool improvement threshold and push count must not be negative");
        }
        return problems;
    }
}
