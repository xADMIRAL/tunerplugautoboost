package io.github.xadmiral.boostautotune.core.session;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.BiasBuildResult;
import io.github.xadmiral.boostautotune.core.learn.BiasTableBuilder;
import io.github.xadmiral.boostautotune.core.learn.DutyLadder;
import io.github.xadmiral.boostautotune.core.learn.PidSuggestion;
import io.github.xadmiral.boostautotune.core.learn.PidTuner;
import io.github.xadmiral.boostautotune.core.learn.PlantModel;
import io.github.xadmiral.boostautotune.core.learn.Pull;
import io.github.xadmiral.boostautotune.core.learn.PullSegmenter;
import io.github.xadmiral.boostautotune.core.learn.ResponseMetrics;
import io.github.xadmiral.boostautotune.core.learn.SampleClassifier;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.learn.StepResponseAnalyzer;
import io.github.xadmiral.boostautotune.core.learn.TargetTableBuilder;
import io.github.xadmiral.boostautotune.core.learn.TargetTrimmer;
import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * State machine of one autotune: characterization runs, then closed-loop runs per target stage.
 * The session never talks to the ECU; it hands out {@link RunPlan}s and consumes {@link Sample}s.
 */
public final class AutotuneSession {
    private final AutotuneConfig cfg;
    private final SampleClassifier classifier;
    private final PullSegmenter segmenter;
    private SafetyListener safetyListener;

    private SessionState state = SessionState.IDLE;
    private SessionPhase phase = SessionPhase.CHARACTERIZE;
    private EcuState current;
    private PlantModel model;
    /** Duty subtracted from the model-built bias table around spool RPM to kill lag overshoot. */
    private Grid spoolTrim;
    private int stageIndex;
    private int closedLoopRunsInStage;
    private int convergedStreak;
    private int runNumber;
    private boolean biasEverBuilt;
    private final List<Double> ladderDuties = new ArrayList<Double>();
    private RunPlan plan;
    private RunReport lastReport;
    private double runPeak = Double.NaN;
    private final int[] counts = new int[SampleState.values().length];
    private String abortReason;
    private final List<String> log = new ArrayList<String>();

    public AutotuneSession(AutotuneConfig cfg) {
        this.cfg = cfg.copy();
        this.classifier = new SampleClassifier(this.cfg);
        this.segmenter = new PullSegmenter(this.cfg, classifier);
    }

    public AutotuneConfig config() {
        return cfg;
    }

    public void setSafetyListener(SafetyListener l) {
        this.safetyListener = l;
    }

    public SessionState state() {
        return state;
    }

    public SessionPhase phase() {
        return phase;
    }

    public int stageIndex() {
        return stageIndex;
    }

    public int runNumber() {
        return runNumber;
    }

    public RunPlan plan() {
        return plan;
    }

    public RunReport lastReport() {
        return lastReport;
    }

    public EcuState currentEcuState() {
        return current;
    }

    public PlantModel plantModel() {
        return model;
    }

    public String abortReason() {
        return abortReason;
    }

    public List<String> log() {
        return new ArrayList<String>(log);
    }

    public int pullsInRun() {
        return segmenter.pullCount() + (segmenter.pullInProgress() ? 1 : 0);
    }

    public double idleSeconds() {
        return segmenter.idleSeconds();
    }

    public double runPeak() {
        return runPeak;
    }

    public int[] sampleCounts() {
        return counts.clone();
    }

    /** Whether the plan for the next run still has to be written to the ECU. */
    public boolean canStartRun() {
        return state == SessionState.READY;
    }

    /**
     * Starts the session from what is in the ECU right now and returns the first plan.
     */
    public RunPlan initialize(EcuState ecu) {
        List<String> problems = cfg.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuration problems: " + problems);
        }
        if (ecu.targetTable == null) {
            throw new IllegalStateException("A target table is required");
        }
        current = ecu.copy();
        Axis rpmAxis = current.hasBiasTable() ? current.biasTable.xAxis() : current.targetTable.xAxis();
        model = new PlantModel(rpmAxis, cfg.dutyBinPct, cfg.defaultGainKpaPerPct);
        spoolTrim = current.hasBiasTable() ? Grid.filled(current.biasTable.xAxis(), current.biasTable.yAxis(), 0) : null;
        stageIndex = 0;
        closedLoopRunsInStage = 0;
        convergedStreak = 0;
        runNumber = 0;
        biasEverBuilt = false;
        ladderDuties.clear();
        abortReason = null;
        log.clear();
        boolean canCharacterize = cfg.characterizeFirst && current.hasOpenLoopTable() && current.hasBiasTable();
        phase = canCharacterize ? SessionPhase.CHARACTERIZE : SessionPhase.CLOSED_LOOP;
        if (!canCharacterize && cfg.characterizeFirst) {
            note("Open-loop characterization skipped: the ECU binding has no open-loop duty table or no bias table");
        }
        plan = phase == SessionPhase.CHARACTERIZE ? planCharacterize() : planClosedLoop(true);
        state = SessionState.READY;
        return plan;
    }

    public void startRun() {
        if (state != SessionState.READY) {
            throw new IllegalStateException("Cannot start a run in state " + state);
        }
        segmenter.reset();
        java.util.Arrays.fill(counts, 0);
        runPeak = Double.NaN;
        runNumber = plan.runNumber;
        state = SessionState.RECORDING;
        note(plan.title() + " started");
    }

    /** Feeds one live sample. Returns its classification. */
    public SampleState onSample(Sample s) {
        if (state != SessionState.RECORDING) {
            return SampleState.NOT_WOT;
        }
        SampleState st = segmenter.feed(s);
        counts[st.ordinal()]++;
        if (Stats.finite(s.map)) {
            runPeak = Double.isNaN(runPeak) ? s.map : Math.max(runPeak, s.map);
        }
        if (st == SampleState.OVERBOOST) {
            abort(String.format(Locale.US, "Overboost: %.1f kPa above the %.0f kPa hard limit at %.0f rpm",
                    s.map, cfg.maxBoostKpa, s.rpm));
            if (safetyListener != null) {
                safetyListener.overboost(s, cfg.maxBoostKpa);
            }
        }
        return st;
    }

    /** True when the run has at least one pull and the car has been off throttle long enough. */
    public boolean runLooksFinished() {
        if (state != SessionState.RECORDING || cfg.autoEndRunIdleSec <= 0) {
            return false;
        }
        double idle = segmenter.idleSeconds();
        return segmenter.pullCount() >= 1 && !segmenter.pullInProgress() && Stats.finite(idle)
                && idle >= cfg.autoEndRunIdleSec;
    }

    public void abort(String reason) {
        if (state == SessionState.ABORTED) {
            return;
        }
        abortReason = reason;
        state = SessionState.ABORTED;
        note("ABORTED: " + reason);
    }

    /** Ends the current run, analyses it and prepares the next plan (not yet applied). */
    public RunReport endRun() {
        if (state != SessionState.RECORDING) {
            throw new IllegalStateException("No run in progress (state " + state + ")");
        }
        segmenter.flush();
        RunReport report = new RunReport(plan);
        report.pulls.addAll(segmenter.pulls());
        for (SampleState st : SampleState.values()) {
            report.sampleCounts.put(st, counts[st.ordinal()]);
        }
        report.observedPeakKpa = runPeak;
        if (report.pulls.isEmpty()) {
            report.messages.add("No usable pull recorded (need >= " + cfg.minPullDurationSec + " s at WOT, warm engine, RPM in range)");
            report.nextPlan = repeatPlan();
            lastReport = report;
            state = SessionState.REVIEW;
            return report;
        }
        if (phase == SessionPhase.CHARACTERIZE) {
            analyseCharacterize(report);
        } else {
            analyseClosedLoop(report);
        }
        lastReport = report;
        state = SessionState.REVIEW;
        note(report.summary());
        return report;
    }

    /** Accepts the report's next plan; the caller must then write plan.ecu to the ECU. */
    public RunPlan commit(RunReport report) {
        if (state != SessionState.REVIEW || report != lastReport) {
            throw new IllegalStateException("Nothing to commit");
        }
        if (report.sessionDone) {
            phase = SessionPhase.DONE;
            plan = report.nextPlan;
            current = plan.ecu.copy();
            state = SessionState.DONE;
            note("Session complete");
            return plan;
        }
        plan = report.nextPlan;
        current = plan.ecu.copy();
        state = SessionState.READY;
        return plan;
    }

    /** Discards the analysis and lets the same plan be driven again. */
    public RunPlan repeatRun() {
        if (state != SessionState.REVIEW) {
            throw new IllegalStateException("Nothing to repeat");
        }
        plan = repeatPlan();
        state = SessionState.READY;
        return plan;
    }

    // ------------------------------------------------------------------------------------------

    private void analyseCharacterize(RunReport report) {
        double duty = plan.openLoopDuty;
        int added = addObservations(report.pulls, 1.0, false, 1.0);
        report.messages.add(String.format(Locale.US, "%d steady samples added to the plant model at %.0f%% duty", added, duty));
        if (added > 0) {
            ladderDuties.add(duty);
        } else {
            report.messages.add("No steady samples: repeat this duty");
            report.nextPlan = repeatPlan();
            return;
        }
        report.plantGainKpaPerPct = model.gainKpaPerPct();
        BiasBuildResult bias = BiasTableBuilder.build(current.biasTable, model, cfg, current.minDuty, current.maxDuty,
                cfg.biasInitialFillUnlimited && !biasEverBuilt);
        report.biasResult = bias;
        DutyLadder.Step step = DutyLadder.next(model, cfg, current.minDuty, current.maxDuty, ladderDuties, runPeak);
        if (!step.done) {
            EcuState next = current.copy();
            next.biasTable = bias.grid;
            next.openLoopTable = TargetTableBuilder.buildOpenLoopDuty(current.openLoopTable, step.duty, cfg,
                    current.minDuty, current.maxDuty);
            next.closedLoop = false;
            RunPlan np = new RunPlan(runNumber + 1, SessionPhase.CHARACTERIZE, stageIndex, cfg.targetStagesKpa.get(0),
                    step.duty, next);
            np.notes.add(step.reason);
            report.nextPlan = np;
            return;
        }
        report.messages.add("Characterization finished: " + step.reason);
        biasEverBuilt = true;
        EcuState withBias = current.copy();
        withBias.biasTable = bias.grid;
        current = withBias;
        phase = SessionPhase.CLOSED_LOOP;
        closedLoopRunsInStage = 0;
        report.nextPlan = planClosedLoop(true);
    }

    private void analyseClosedLoop(RunReport report) {
        boolean hasFf = current.hasBiasTable();
        for (Pull p : report.pulls) {
            report.metrics.add(StepResponseAnalyzer.analyze(p, cfg, current.minDuty, current.maxDuty));
        }
        PidSuggestion pid = PidTuner.suggest(current.pid, report.metrics, cfg, hasFf, convergedStreak > 0);
        report.pidSuggestion = pid;

        EcuState next = current.copy();
        next.pid = pid.after;
        boolean biasSettled = true;
        if (hasFf) {
            int added = addObservations(report.pulls, cfg.closedLoopObservationWeight, true, cfg.observationDecayPerRun);
            report.messages.add(added + " steady closed-loop samples added to the plant model");
            report.plantGainKpaPerPct = model.gainKpaPerPct();
            if (pid.transientOvershoot) {
                increaseSpoolTrim(report);
            } else {
                relaxSpoolTrim(report);
            }
            Grid untrimmed = addGrids(current.biasTable, spoolTrim, 1.0, current.minDuty, current.maxDuty);
            BiasBuildResult bias = BiasTableBuilder.build(untrimmed, model, cfg, current.minDuty, current.maxDuty,
                    !biasEverBuilt && cfg.biasInitialFillUnlimited);
            biasEverBuilt = true;
            report.biasResult = bias;
            next.biasTable = addGrids(bias.grid, spoolTrim, -1.0, current.minDuty, current.maxDuty);
            biasSettled = focusedBiasChange(bias) <= cfg.biasSettledPct;
        }
        TargetTrimmer.Result trim = TargetTrimmer.trim(current.targetTable, current.targetLoadSource, report.pulls, cfg,
                current.maxDuty);
        report.targetTrims.addAll(trim.notes);
        next.targetTable = trim.grid;
        closedLoopRunsInStage++;

        boolean allReached = !report.metrics.isEmpty();
        for (ResponseMetrics m : report.metrics) {
            if (!m.hasTarget || !m.reached) {
                allReached = false;
            }
        }
        boolean runOk = pid.converged && (allReached || pid.unreachable) && biasSettled && trim.trimmedCells == 0;
        convergedStreak = runOk ? convergedStreak + 1 : 0;
        boolean converged = convergedStreak >= cfg.runsRequiredPerStage;
        report.stageConverged = converged;
        if (!converged) {
            List<String> why = new ArrayList<String>();
            if (!pid.converged) why.add("PID not settled");
            if (!allReached && !pid.unreachable) why.add("target not reached in every pull");
            if (!biasSettled) why.add(String.format(Locale.US, "bias table still moving (%.1f%% in measured cells near the target)", focusedBiasChange(report.biasResult)));
            if (trim.trimmedCells > 0) why.add("targets were trimmed");
            if (runOk) why.add(String.format(Locale.US, "good run %d of %d in a row", convergedStreak, cfg.runsRequiredPerStage));
            report.messages.add("Stage " + (stageIndex + 1) + " continues: " + join(why));
            current = next;
            report.nextPlan = planClosedLoop(false);
            return;
        }
        report.messages.add(String.format(Locale.US, "Stage %d (%.0f kPa) converged after %d closed-loop runs",
                stageIndex + 1, cfg.targetStagesKpa.get(stageIndex), closedLoopRunsInStage));
        if (stageIndex + 1 < cfg.targetStagesKpa.size()) {
            stageIndex++;
            closedLoopRunsInStage = 0;
            convergedStreak = 0;
            current = next;
            report.nextPlan = planClosedLoop(true);
            report.nextPlan.notes.add("Moving to stage " + (stageIndex + 1));
            return;
        }
        report.sessionDone = true;
        RunPlan done = new RunPlan(runNumber + 1, SessionPhase.DONE, stageIndex, cfg.targetStagesKpa.get(stageIndex),
                Double.NaN, next);
        done.notes.add("All stages converged. Burn the tune to make it permanent.");
        report.nextPlan = done;
    }

    private RunPlan planCharacterize() {
        DutyLadder.Step step = DutyLadder.next(model, cfg, current.minDuty, current.maxDuty, ladderDuties, Double.NaN);
        EcuState next = current.copy();
        next.openLoopTable = TargetTableBuilder.buildOpenLoopDuty(current.openLoopTable, step.duty, cfg,
                current.minDuty, current.maxDuty);
        next.closedLoop = false;
        RunPlan p = new RunPlan(runNumber + 1, SessionPhase.CHARACTERIZE, 0, cfg.targetStagesKpa.get(0), step.duty, next);
        p.notes.add(step.reason);
        return p;
    }

    private RunPlan planClosedLoop(boolean stageStart) {
        double target = cfg.targetStagesKpa.get(stageIndex);
        EcuState next = current.copy();
        next.closedLoop = true;
        if (stageStart) {
            next.targetTable = TargetTableBuilder.build(current.targetTable, target, cfg, current.targetTableMax);
            if (cfg.softStartClosedLoop && closedLoopRunsInStage == 0 && stageIndex == 0) {
                next.pid = PidTuner.softStart(current.pid, cfg);
            }
        }
        RunPlan p = new RunPlan(runNumber + 1, SessionPhase.CLOSED_LOOP, stageIndex, target, Double.NaN, next);
        if (stageStart) {
            p.notes.add(String.format(Locale.US, "Target table rebuilt for %.0f kPa", target));
            if (cfg.softStartClosedLoop && closedLoopRunsInStage == 0 && stageIndex == 0) {
                p.notes.add("Soft start: I and D halved for the first closed-loop run");
            }
            if (next.hasBiasTable() && (target > next.biasTable.yAxis().max() || target < next.biasTable.yAxis().min())) {
                p.notes.add(String.format(Locale.US,
                        "WARNING: target %.0f kPa lies outside the bias table Y axis (%.0f-%.0f); the ECU will clamp",
                        target, next.biasTable.yAxis().min(), next.biasTable.yAxis().max()));
            }
        }
        return p;
    }

    private RunPlan repeatPlan() {
        RunPlan p = new RunPlan(runNumber + 1, plan.phase, plan.stageIndex, plan.stageTarget, plan.openLoopDuty, plan.ecu.copy());
        p.notes.add("Repeat of the previous run");
        return p;
    }

    /**
     * Feeds steady samples into the plant model. MAP is paired with the duty (and RPM) commanded
     * {@code plantLagSec} earlier, because that is what produced it. In closed loop only samples
     * after the loop has reached its target and settled count: while the integrator is still
     * winding up the duty says nothing about steady state. Columns that receive fresh data are
     * aged first so recent evidence wins; untouched columns keep their characterization data.
     */
    private int addObservations(List<Pull> pulls, double weight, boolean closedLoop, double decayFactor) {
        List<double[]> obs = new ArrayList<double[]>(); // {rpm, duty, map}
        for (Pull p : pulls) {
            List<Sample> ss = p.samples();
            double settledFrom = Double.NEGATIVE_INFINITY;
            if (closedLoop) {
                settledFrom = Double.NaN;
                for (Sample s : ss) {
                    if (Stats.finite(s.target) && s.target > cfg.wastegateKpa + 1 && s.map >= s.target - cfg.steadyStateTolKpa) {
                        settledFrom = s.timeSec + cfg.settleDelaySec;
                        break;
                    }
                }
                if (Double.isNaN(settledFrom)) {
                    continue; // never reached the target: nothing steady to learn from
                }
            }
            for (int k = 0; k < p.size(); k++) {
                if (p.stateAt(k) != SampleState.STEADY) {
                    continue;
                }
                Sample s = ss.get(k);
                if (closedLoop && (s.timeSec < settledFrom
                        || (Stats.finite(s.target) && s.map < s.target - cfg.closedLoopLearnWindowKpa))) {
                    continue;
                }
                Sample cause = laggedSample(ss, k);
                if (cause == null) {
                    continue;
                }
                obs.add(new double[]{cause.rpm, cause.duty, s.map});
            }
        }
        if (decayFactor < 1.0 && !obs.isEmpty()) {
            java.util.Set<Integer> touched = new java.util.HashSet<Integer>();
            for (double[] o : obs) {
                touched.addAll(model.columnsFor(o[0]));
            }
            for (int c : touched) {
                model.decayColumn(c, decayFactor);
            }
        }
        for (double[] o : obs) {
            model.addObservation(o[0], o[1], o[2], weight);
        }
        return obs.size();
    }

    /** Largest change among data-backed cells whose target row matters for the current stage. */
    private double focusedBiasChange(BiasBuildResult bias) {
        double stageTarget = cfg.targetStagesKpa.get(stageIndex);
        double m = 0;
        for (BiasBuildResult.CellChange c : bias.changes) {
            boolean backed = c.quality == io.github.xadmiral.boostautotune.core.learn.DutyEstimate.Quality.MEASURED
                    || c.quality == io.github.xadmiral.boostautotune.core.learn.DutyEstimate.Quality.INTERPOLATED;
            if (!backed || c.target < stageTarget - 20 || c.target > stageTarget + 10) {
                continue;
            }
            if (cfg.fullTargetRpm > 0 && c.rpm < cfg.fullTargetRpm - 1) {
                continue; // spool columns never hold the stage target; they are handled by the spool trim
            }
            m = Math.max(m, Math.abs(c.delta()));
        }
        return m;
    }

    /** Lowers the bias around the RPM that produced each overshooting peak. */
    private void increaseSpoolTrim(RunReport report) {
        double gain = model.gainKpaPerPct();
        Axis rpm = spoolTrim.xAxis();
        Axis targets = spoolTrim.yAxis();
        double stageTarget = cfg.targetStagesKpa.get(stageIndex);
        for (ResponseMetrics m : report.metrics) {
            if (!m.hasTarget || !m.reached || m.overshootKpa <= cfg.overshootThresholdKpa) {
                continue;
            }
            List<Sample> ss = m.pull.samples();
            int peakIdx = 0;
            for (int k = 1; k < ss.size(); k++) {
                if (ss.get(k).map > ss.get(peakIdx).map) {
                    peakIdx = k;
                }
            }
            Sample cause = laggedSample(ss, peakIdx);
            if (cause == null) {
                continue;
            }
            double amount = Stats.clamp(m.overshootKpa / gain * 0.5, 1.0, cfg.biasMaxStepPct / 2);
            double lo = cause.rpm - 700;
            double hi = cause.rpm + 300;
            StringBuilder cols = new StringBuilder();
            for (int xi = 0; xi < rpm.size(); xi++) {
                if (rpm.bin(xi) < lo || rpm.bin(xi) > hi) {
                    continue;
                }
                for (int yi = 0; yi < targets.size(); yi++) {
                    if (targets.bin(yi) < stageTarget - 25) {
                        continue;
                    }
                    spoolTrim.set(xi, yi, Math.min(20, spoolTrim.get(xi, yi) + amount));
                }
                if (cols.length() > 0) {
                    cols.append(", ");
                }
                cols.append(String.format(Locale.US, "%.0f", rpm.bin(xi)));
            }
            if (cols.length() > 0) {
                report.messages.add(String.format(Locale.US,
                        "Spool trim: bias lowered by %.1f%% at %s rpm for targets >= %.0f kPa (peak %.1f kPa, overshoot %.1f)",
                        amount, cols, stageTarget - 25, m.peakKpa, m.overshootKpa));
            }
        }
    }

    /** Slowly gives trimmed duty back when the response has become sluggish. */
    private void relaxSpoolTrim(RunReport report) {
        boolean slow = false;
        for (ResponseMetrics m : report.metrics) {
            if (m.hasTarget && m.reached && Stats.finite(m.riseTimeSec) && m.riseTimeSec > cfg.maxRiseTimeSec
                    && m.overshootKpa < cfg.overshootThresholdKpa / 2) {
                slow = true;
            }
        }
        if (!slow || spoolTrim.max() <= 0) {
            return;
        }
        for (int yi = 0; yi < spoolTrim.height(); yi++) {
            for (int xi = 0; xi < spoolTrim.width(); xi++) {
                spoolTrim.set(xi, yi, spoolTrim.get(xi, yi) * 0.8);
            }
        }
        report.messages.add("Spool trim relaxed by 20 % (slow rise without overshoot)");
    }

    private static Grid addGrids(Grid base, Grid trim, double sign, double lo, double hi) {
        if (trim == null) {
            return base.copy();
        }
        Grid out = base.copy();
        for (int yi = 0; yi < out.height(); yi++) {
            for (int xi = 0; xi < out.width(); xi++) {
                out.set(xi, yi, Stats.clamp(base.get(xi, yi) + sign * trim.get(xi, yi), lo, hi));
            }
        }
        return out;
    }

    public Grid spoolTrim() {
        return spoolTrim == null ? null : spoolTrim.copy();
    }

    private Sample laggedSample(List<Sample> ss, int k) {
        double t = ss.get(k).timeSec - cfg.plantLagSec;
        for (int j = k; j >= 0; j--) {
            Sample c = ss.get(j);
            if (c.timeSec <= t) {
                return Stats.finite(c.duty) ? c : null;
            }
        }
        // the pull started less than one lag ago: fall back to its first sample
        Sample c = ss.get(0);
        return Stats.finite(c.duty) && ss.get(k).timeSec - c.timeSec >= cfg.plantLagSec * 0.5 ? c : null;
    }

    private void note(String s) {
        log.add(s);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** Convenience: builds a target grid preview for a stage from the current ECU target table. */
    public Grid previewTargets(Grid ecuTargets, double stageTarget, double tableMax) {
        return TargetTableBuilder.build(ecuTargets, stageTarget, cfg, tableMax);
    }

    /** Convenience for UIs that want to display the gains of the last plan. */
    public PidGains plannedGains() {
        return plan == null ? null : plan.ecu.pid;
    }
}
