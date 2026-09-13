package io.github.xadmiral.boostautotune.core.sweep;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.Pull;
import io.github.xadmiral.boostautotune.core.learn.PullSegmenter;
import io.github.xadmiral.boostautotune.core.learn.SampleClassifier;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.learn.TorqueProxy;
import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.LoadSource;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optimises the WOT rows of a table (VVT target or ignition advance) by trying a handful of
 * offsets, one per run, and comparing engine acceleration per RPM bin. The ignition variant is
 * guarded by knock (per-cell caps, abort on heavy knock) and by AFR.
 */
public final class SweepSession {
    private final SweepConfig cfg;
    private final LoadSource loadSource;
    private final AutotuneConfig filters;
    private final SampleClassifier classifier;
    private final PullSegmenter segmenter;

    private SessionState state = SessionState.IDLE;
    private Grid original;
    private KnockGuard knock;
    private int runNumber;
    private int pass;
    private int candidateIndex;
    private List<Double> order;
    /** results[candidate][pass] */
    private TorqueProxy.AccelProfile[][] results;
    private SweepPlan plan;
    private SweepReport lastReport;
    private final List<KnockGuard.Event> runKnock = new ArrayList<KnockGuard.Event>();
    private int leanStreak;
    private String abortReason;
    private final List<String> log = new ArrayList<String>();

    public SweepSession(SweepConfig cfg, LoadSource loadSource) {
        this.cfg = cfg.copy();
        this.loadSource = loadSource;
        this.filters = new AutotuneConfig();
        filters.wotLoadThreshold = cfg.wotLoadThreshold;
        filters.minCltC = cfg.minCltC;
        filters.minRpm = Math.min(cfg.minRpm, 1500);
        filters.maxRpm = Math.max(cfg.maxRpm, 8000);
        filters.maxBoostKpa = cfg.maxBoostKpa;
        filters.minPullDurationSec = cfg.minPullDurationSec;
        filters.settleDelaySec = 0.3;
        filters.autoEndRunIdleSec = cfg.autoEndRunIdleSec;
        this.classifier = new SampleClassifier(filters);
        this.segmenter = new PullSegmenter(filters, classifier);
    }

    public SweepConfig config() {
        return cfg;
    }

    public SessionState state() {
        return state;
    }

    public SweepPlan plan() {
        return plan;
    }

    public SweepReport lastReport() {
        return lastReport;
    }

    public String abortReason() {
        return abortReason;
    }

    public Grid original() {
        return original == null ? null : original.copy();
    }

    public KnockGuard knockGuard() {
        return knock;
    }

    public int runNumber() {
        return runNumber;
    }

    public int pullsInRun() {
        return segmenter.pullCount() + (segmenter.pullInProgress() ? 1 : 0);
    }

    public List<String> log() {
        return new ArrayList<String>(log);
    }

    public String progress() {
        if (order == null) {
            return "";
        }
        int total = order.size() * cfg.passes;
        int done = pass * order.size() + candidateIndex;
        return String.format(Locale.US, "candidate %d of %d", Math.min(done + 1, total), total);
    }

    public SweepPlan initialize(Grid currentTable) {
        List<String> problems = cfg.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuration problems: " + problems);
        }
        original = currentTable.copy();
        knock = new KnockGuard(original.xAxis(), original.yAxis(), cfg.knockCapMarginDeg);
        order = cfg.orderedCandidates(0);
        results = new TorqueProxy.AccelProfile[cfg.candidateOffsets.size()][cfg.passes];
        runNumber = 0;
        pass = 0;
        candidateIndex = 0;
        abortReason = null;
        log.clear();
        plan = planFor(pass, candidateIndex);
        state = SessionState.READY;
        return plan;
    }

    public void startRun() {
        if (state != SessionState.READY) {
            throw new IllegalStateException("Cannot start a run in state " + state);
        }
        segmenter.reset();
        runKnock.clear();
        leanStreak = 0;
        runNumber = plan.runNumber;
        state = SessionState.RECORDING;
        log.add(plan.title() + " started");
    }

    public SampleState onSample(Sample s) {
        if (state != SessionState.RECORDING) {
            return SampleState.NOT_WOT;
        }
        SampleState st = segmenter.feed(s);
        if (st == SampleState.OVERBOOST) {
            abort(String.format(Locale.US, "Overboost %.1f kPa above the %.0f kPa limit", s.map, cfg.maxBoostKpa));
            return st;
        }
        if (cfg.kind == SweepKind.IGNITION) {
            boolean retard = Stats.finite(s.knockRetard) && s.knockRetard >= cfg.knockRetardTriggerDeg;
            boolean level = Stats.finite(cfg.knockLevelTrigger) && Stats.finite(s.knock) && s.knock >= cfg.knockLevelTrigger;
            if (retard || level) {
                double load = s.load(loadSource);
                knock.record(s, load);
                runKnock.add(knock.events().get(knock.events().size() - 1));
                if (Stats.finite(s.knockRetard) && s.knockRetard >= cfg.knockAbortRetardDeg) {
                    abort(String.format(Locale.US, "Heavy knock: %.1f deg retard at %.0f rpm / load %.0f", s.knockRetard, s.rpm, load));
                    return st;
                }
            }
            if (Stats.finite(cfg.maxWotAfr) && Stats.finite(s.afr) && s.tps >= cfg.wotLoadThreshold && s.rpm >= cfg.minRpm) {
                leanStreak = s.afr > cfg.maxWotAfr ? leanStreak + 1 : 0;
                if (leanStreak >= 5) {
                    abort(String.format(Locale.US, "Lean at WOT: AFR %.1f above the %.1f limit at %.0f rpm", s.afr, cfg.maxWotAfr, s.rpm));
                    return st;
                }
            }
        }
        return st;
    }

    public boolean runLooksFinished() {
        if (state != SessionState.RECORDING || cfg.autoEndRunIdleSec <= 0) {
            return false;
        }
        double idle = segmenter.idleSeconds();
        return segmenter.pullCount() >= 1 && !segmenter.pullInProgress() && Stats.finite(idle) && idle >= cfg.autoEndRunIdleSec;
    }

    public void abort(String reason) {
        if (state == SessionState.ABORTED) {
            return;
        }
        abortReason = reason;
        state = SessionState.ABORTED;
        log.add("ABORTED: " + reason);
    }

    public SweepReport endRun() {
        if (state != SessionState.RECORDING) {
            throw new IllegalStateException("No run in progress (state " + state + ")");
        }
        segmenter.flush();
        SweepReport r = new SweepReport(plan);
        r.pulls.addAll(segmenter.pulls());
        r.knockEvents.addAll(runKnock);
        r.originalTable = original.copy();
        TorqueProxy.AccelProfile prof = new TorqueProxy.AccelProfile(original.xAxis());
        for (Pull p : r.pulls) {
            prof.addAll(TorqueProxy.profile(original.xAxis(), p, cfg.accelWindowSec, cfg.accelSettleSec,
                    cfg.wotLoadThreshold, cfg.minRpm, cfg.maxRpm, cfg.gear));
        }
        r.accel = prof;
        int usable = 0;
        for (int i = 0; i < prof.n.length; i++) {
            if (prof.n[i] >= cfg.minSamplesPerBin) {
                usable++;
            }
        }
        if (r.pulls.isEmpty() || usable == 0) {
            r.messages.add(r.pulls.isEmpty() ? "No usable pull recorded" : "Too few accelerating samples per RPM bin"
                    + (cfg.gear > 0 ? " (pulls must be in gear " + cfg.gear + ")" : ""));
            r.messages.add("Repeat this candidate");
            r.nextPlan = planFor(pass, candidateIndex);
            r.nextPlan.notes.add("Repeat of the previous run");
            lastReport = r;
            state = SessionState.REVIEW;
            return r;
        }
        int cIdx = cfg.candidateOffsets.indexOf(order.get(candidateIndex));
        if (results[cIdx][pass] == null) {
            results[cIdx][pass] = prof;
        } else {
            results[cIdx][pass].addAll(prof);
        }
        r.messages.add(String.format(Locale.US, "Candidate %+.1f deg measured in %d RPM bins", order.get(candidateIndex), usable));
        if (!runKnock.isEmpty()) {
            r.messages.add(runKnock.size() + " knock events: " + knock.cappedCells() + " cells now capped");
        }
        // advance the schedule
        int nextCandidate = candidateIndex + 1;
        int nextPass = pass;
        if (nextCandidate >= order.size()) {
            nextCandidate = 0;
            nextPass = pass + 1;
        }
        if (nextPass >= cfg.passes) {
            decide(r);
            r.sweepDone = true;
            r.nextPlan = new SweepPlan(runNumber + 1, cfg.kind, pass, candidateIndex, 0, true, r.resultTable);
            r.nextPlan.notes.add("Sweep finished. Write the optimised table, verify with a pull, then burn.");
        } else {
            r.nextPlan = planFor(nextPass, nextCandidate);
        }
        lastReport = r;
        state = SessionState.REVIEW;
        log.add(r.summary());
        return r;
    }

    public SweepPlan commit(SweepReport report) {
        if (state != SessionState.REVIEW || report != lastReport) {
            throw new IllegalStateException("Nothing to commit");
        }
        plan = report.nextPlan;
        if (report.sweepDone) {
            state = SessionState.DONE;
            log.add("Sweep complete");
            return plan;
        }
        pass = plan.pass;
        candidateIndex = plan.candidateIndex;
        order = cfg.orderedCandidates(pass);
        state = SessionState.READY;
        return plan;
    }

    public SweepPlan repeatRun() {
        if (state != SessionState.REVIEW) {
            throw new IllegalStateException("Nothing to repeat");
        }
        plan = planFor(pass, candidateIndex);
        plan.notes.add("Repeat of the previous run");
        state = SessionState.READY;
        return plan;
    }

    // ------------------------------------------------------------------------------------------

    private SweepPlan planFor(int p, int cIdx) {
        List<Double> ord = cfg.orderedCandidates(p);
        double offset = ord.get(cIdx);
        Grid t = withOffset(original, offset);
        SweepPlan sp = new SweepPlan(runNumber + 1, cfg.kind, p, cIdx, offset, false, t);
        if (knock != null && knock.cappedCells() > 0) {
            int lowered = knock.apply(sp.table);
            if (lowered > 0) {
                sp.notes.add(lowered + " cells held below their knock cap");
            }
        }
        return sp;
    }

    /** Original table plus a uniform offset in the WOT rows, within the absolute limits. */
    Grid withOffset(Grid base, double offset) {
        Grid t = base.copy();
        for (int yi = 0; yi < t.height(); yi++) {
            if (t.yAxis().bin(yi) < cfg.minLoad) {
                continue;
            }
            for (int xi = 0; xi < t.width(); xi++) {
                double rpm = t.xAxis().bin(xi);
                if (rpm < cfg.minRpm - 1 || rpm > cfg.maxRpm + 1) {
                    continue;
                }
                double v = base.get(xi, yi) + offset;
                if (cfg.kind == SweepKind.IGNITION) {
                    v = Math.min(v, base.get(xi, yi) + cfg.maxAdvanceOverOriginalDeg);
                }
                t.set(xi, yi, Stats.clamp(v, cfg.absoluteMin, cfg.absoluteMax));
            }
        }
        return t;
    }

    /** Picks the best offset per RPM bin and builds the result table. */
    private void decide(SweepReport r) {
        Axis rpm = original.xAxis();
        int nC = cfg.candidateOffsets.size();
        int baseIdx = cfg.candidateOffsets.indexOf(0.0);
        double[] chosen = new double[rpm.size()];
        boolean[] decided = new boolean[rpm.size()];
        for (int xi = 0; xi < rpm.size(); xi++) {
            double[] mean = new double[nC];
            double[] sem = new double[nC];
            int[] n = new int[nC];
            for (int c = 0; c < nC; c++) {
                TorqueProxy.AccelProfile merged = new TorqueProxy.AccelProfile(rpm);
                for (int p = 0; p < cfg.passes; p++) {
                    if (results[c][p] != null) {
                        merged.addAll(results[c][p]);
                    }
                }
                n[c] = merged.n[xi];
                mean[c] = n[c] >= cfg.minSamplesPerBin ? merged.mean(xi) : Double.NaN;
                sem[c] = merged.sem(xi);
            }
            if (baseIdx < 0 || Double.isNaN(mean[baseIdx])) {
                continue; // no baseline data in this bin: leave it alone
            }
            // knock cap of this column (most restrictive over the WOT rows)
            double maxOffsetAllowed = Double.POSITIVE_INFINITY;
            boolean knockLimited = false;
            if (cfg.kind == SweepKind.IGNITION) {
                for (int yi = 0; yi < original.height(); yi++) {
                    if (original.yAxis().bin(yi) < cfg.minLoad || !knock.hasCap(xi, yi)) {
                        continue;
                    }
                    maxOffsetAllowed = Math.min(maxOffsetAllowed, knock.cap(xi, yi) - original.get(xi, yi));
                    knockLimited = true;
                }
            }
            // candidates sorted by offset
            List<Integer> idx = new ArrayList<Integer>();
            for (int c = 0; c < nC; c++) {
                idx.add(c);
            }
            java.util.Collections.sort(idx, new java.util.Comparator<Integer>() {
                public int compare(Integer a, Integer b) {
                    return Double.compare(cfg.candidateOffsets.get(a), cfg.candidateOffsets.get(b));
                }
            });
            double bestMean = mean[baseIdx];
            int best = baseIdx;
            for (int c : idx) {
                if (Double.isNaN(mean[c]) || cfg.candidateOffsets.get(c) > maxOffsetAllowed) {
                    continue;
                }
                if (mean[c] > bestMean) {
                    bestMean = mean[c];
                    best = c;
                }
            }
            int pick = best;
            String reason = "best torque";
            if (cfg.kind == SweepKind.IGNITION) {
                // MBT rule: the least advance whose torque is within the plateau of the best
                for (int c : idx) {
                    if (Double.isNaN(mean[c]) || cfg.candidateOffsets.get(c) > maxOffsetAllowed) {
                        continue;
                    }
                    if (TorqueProxy.gainPct(mean[c], bestMean) <= cfg.mbtPlateauPct) {
                        if (cfg.candidateOffsets.get(c) < cfg.candidateOffsets.get(pick)) {
                            pick = c;
                            reason = "MBT plateau: least advance for best torque";
                        }
                        break;
                    }
                }
            }
            double gain = TorqueProxy.gainPct(mean[baseIdx], mean[pick]);
            double noise = 0;
            if (cfg.requireAboveNoise && Stats.finite(sem[baseIdx]) && Stats.finite(sem[pick]) && mean[baseIdx] > 0) {
                noise = (sem[baseIdx] + sem[pick]) / mean[baseIdx] * 100.0;
            }
            double offset = cfg.candidateOffsets.get(pick);
            if (pick != baseIdx && cfg.candidateOffsets.get(pick) > 0 && (gain < cfg.minGainPct || gain < noise)) {
                pick = baseIdx;
                offset = 0;
                gain = 0;
                reason = String.format(Locale.US, "gain below %.1f%% / noise %.1f%%: keep", cfg.minGainPct, noise);
            } else if (pick != baseIdx && cfg.candidateOffsets.get(pick) < 0 && gain < cfg.minGainPct && !knockLimited) {
                // retarding without a clear gain is pointless
                pick = baseIdx;
                offset = 0;
                gain = 0;
                reason = "no clear gain from retarding: keep";
            }
            if (cfg.kind == SweepKind.IGNITION && Stats.finite(maxOffsetAllowed) && offset > maxOffsetAllowed) {
                offset = maxOffsetAllowed;
                reason = "held at knock cap";
            }
            if (cfg.kind == SweepKind.IGNITION && knockLimited && maxOffsetAllowed < 0 && offset > maxOffsetAllowed) {
                offset = maxOffsetAllowed;
                reason = "retarded to the knock cap";
            }
            chosen[xi] = offset;
            decided[xi] = true;
            r.decisions.add(new SweepReport.BinDecision(rpm.bin(xi), offset, mean[baseIdx],
                    Double.isNaN(mean[pick]) ? mean[baseIdx] : mean[pick], gain, knockLimited, reason));
        }
        // smoothing across RPM: limit the step between neighbouring decided columns
        for (int iter = 0; iter < 3; iter++) {
            for (int xi = 1; xi < rpm.size(); xi++) {
                if (!decided[xi] || !decided[xi - 1]) {
                    continue;
                }
                double d = chosen[xi] - chosen[xi - 1];
                if (Math.abs(d) > cfg.smoothingMaxStepDeg) {
                    double excess = (Math.abs(d) - cfg.smoothingMaxStepDeg) / 2;
                    chosen[xi] -= Math.signum(d) * excess;
                    chosen[xi - 1] += Math.signum(d) * excess;
                }
            }
        }
        Grid result = original.copy();
        for (int yi = 0; yi < result.height(); yi++) {
            if (result.yAxis().bin(yi) < cfg.minLoad) {
                continue;
            }
            for (int xi = 0; xi < rpm.size(); xi++) {
                if (!decided[xi]) {
                    continue;
                }
                double v = original.get(xi, yi) + chosen[xi];
                if (cfg.kind == SweepKind.IGNITION) {
                    v = Math.min(v, original.get(xi, yi) + cfg.maxAdvanceOverOriginalDeg);
                }
                result.set(xi, yi, Stats.clamp(v, cfg.absoluteMin, cfg.absoluteMax));
            }
        }
        if (cfg.kind == SweepKind.IGNITION) {
            int lowered = knock.apply(result);
            if (lowered > 0) {
                r.messages.add(lowered + " cells held below their knock cap in the final table");
            }
        }
        r.resultTable = result;
        r.messages.add(String.format(Locale.US, "Result: %d RPM bins decided, max change %.1f deg",
                r.decisions.size(), result.maxAbsDiff(original)));
    }
}
