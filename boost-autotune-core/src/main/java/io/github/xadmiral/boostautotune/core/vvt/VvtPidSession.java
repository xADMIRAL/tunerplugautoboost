package io.github.xadmiral.boostautotune.core.vvt;

import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tunes the VVT closed-loop gains from ordinary driving: each run is a minute or two of varied
 * load and RPM; the cam has to follow its target without ringing or lagging.
 */
public final class VvtPidSession {

    public static final class Plan {
        public final int runNumber;
        public final PidGains gains;
        public final boolean finalResult;
        public final List<String> notes = new ArrayList<String>();

        Plan(int runNumber, PidGains gains, boolean finalResult) {
            this.runNumber = runNumber;
            this.gains = gains;
            this.finalResult = finalResult;
        }

        public String title() {
            return finalResult ? "VVT PID settled: " + gains : String.format(Locale.US, "Run %d: VVT PID %s", runNumber, gains);
        }

        public String driverInstructions() {
            return finalResult ? "Burn the gains." : "Drive normally for a minute or two with plenty of throttle and RPM changes "
                    + "(the cam target must move). No pulls needed. Then end the run.";
        }
    }

    public static final class Report {
        public final Plan plan;
        public VvtTrackingMetrics metrics;
        public final List<String> rationale = new ArrayList<String>();
        public boolean converged;
        public boolean done;
        public Plan nextPlan;

        Report(Plan plan) {
            this.plan = plan;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder(plan.title()).append('\n');
            if (metrics != null) {
                sb.append("  ").append(metrics.summary()).append('\n');
            }
            for (String r : rationale) {
                sb.append("  - ").append(r).append('\n');
            }
            if (nextPlan != null) {
                sb.append("Next: ").append(nextPlan.title()).append('\n');
                for (String n : nextPlan.notes) {
                    sb.append("  ").append(n).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private final VvtPidConfig cfg;
    private SessionState state = SessionState.IDLE;
    private PidGains current;
    private Plan plan;
    private Report lastReport;
    private final List<Sample> run = new ArrayList<Sample>();
    private int runNumber;
    private int goodStreak;
    private String abortReason;
    private double lastError = Double.NaN;
    private double lastTarget = Double.NaN;
    /** Do not raise P right after lowering it for ringing (avoids ping-pong). */
    private boolean softenedLastRun;

    public VvtPidSession(VvtPidConfig cfg) {
        this.cfg = cfg.copy();
    }

    public VvtPidConfig config() {
        return cfg;
    }

    public SessionState state() {
        return state;
    }

    public Plan plan() {
        return plan;
    }

    public Report lastReport() {
        return lastReport;
    }

    public String abortReason() {
        return abortReason;
    }

    public int runNumber() {
        return runNumber;
    }

    public int samplesInRun() {
        return run.size();
    }

    public double lastError() {
        return lastError;
    }

    public double lastTarget() {
        return lastTarget;
    }

    public Plan initialize(PidGains ecuGains) {
        current = ecuGains;
        runNumber = 0;
        goodStreak = 0;
        softenedLastRun = false;
        abortReason = null;
        plan = new Plan(1, current, false);
        state = SessionState.READY;
        return plan;
    }

    public void startRun() {
        if (state != SessionState.READY) {
            throw new IllegalStateException("Cannot start a run in state " + state);
        }
        run.clear();
        runNumber = plan.runNumber;
        state = SessionState.RECORDING;
    }

    public void onSample(Sample s) {
        if (state != SessionState.RECORDING) {
            return;
        }
        run.add(s);
        if (Stats.finite(s.vvtAngle) && Stats.finite(s.vvtTarget)) {
            lastError = s.vvtAngle - s.vvtTarget;
            lastTarget = s.vvtTarget;
        }
    }

    public boolean runLooksFinished() {
        return false; // driving runs are ended by the user
    }

    public void abort(String reason) {
        if (state == SessionState.ABORTED) {
            return;
        }
        abortReason = reason;
        state = SessionState.ABORTED;
    }

    public Report endRun() {
        if (state != SessionState.RECORDING) {
            throw new IllegalStateException("No run in progress (state " + state + ")");
        }
        Report r = new Report(plan);
        VvtTrackingMetrics m = VvtTrackingAnalyzer.analyze(run, cfg);
        r.metrics = m;
        if (m.activeSamples < cfg.minSamples) {
            r.rationale.add(String.format(Locale.US, "Only %d samples with the cam commanded away from rest (need %d): drive with more load / RPM variation and repeat",
                    m.activeSamples, cfg.minSamples));
            r.nextPlan = new Plan(runNumber + 1, current, false);
            r.nextPlan.notes.add("Repeat with the same gains");
            lastReport = r;
            state = SessionState.REVIEW;
            return r;
        }
        double step = cfg.pidStepFraction;
        double p = current.p, i = current.i, d = current.d;
        boolean good = false;
        boolean rings = rings(m, cfg);
        if (rings) {
            if (cfg.tuneP) p *= 1 - step;
            if (cfg.tuneI) i *= 1 - step / 2;
            if (cfg.tuneD && d > 0) d *= 1 + step / 2;
            softenedLastRun = true;
            r.rationale.add(String.format(Locale.US, "Cam rings %.1f deg over %.1f cycles (period %.2f s): lowering P (and I), a little more D",
                    m.oscillationAmplitudeDeg, m.oscillationCycles, m.oscillationPeriodSec));
        } else if (m.lagSec > cfg.lagTolSec && !softenedLastRun) {
            if (cfg.tuneP) p *= 1 + step * 0.6;
            r.rationale.add(String.format(Locale.US, "Cam trails a moving target by %.2f s: raising P", m.lagSec));
        } else if (Stats.finite(m.meanErrorDeg) && Math.abs(m.meanErrorDeg) > cfg.steadyStateTolDeg / 2
                && m.meanAbsErrorDeg > cfg.steadyStateTolDeg) {
            if (cfg.tuneI) i *= 1 + step;
            softenedLastRun = false;
            r.rationale.add(String.format(Locale.US, "Persistent offset %+.1f deg while the target holds: raising I", m.meanErrorDeg));
        } else if (Stats.finite(m.meanAbsErrorDeg) && m.meanAbsErrorDeg > cfg.steadyStateTolDeg
                && m.oscillationCycles >= 1.5 && m.oscillationAmplitudeDeg >= 1.5
                && !Double.isNaN(m.oscillationPeriodSec) && m.oscillationPeriodSec <= 1.0) {
            // not quite ringing, but the wobble is what keeps the error up: back P off a little
            if (cfg.tuneP) p *= 1 - step / 2;
            softenedLastRun = true;
            r.rationale.add(String.format(Locale.US, "Steady |error| %.1f deg with a %.1f deg wobble: lowering P a little", m.meanAbsErrorDeg, m.oscillationAmplitudeDeg));
        } else if (Stats.finite(m.meanAbsErrorDeg) && m.meanAbsErrorDeg > cfg.steadyStateTolDeg) {
            if (cfg.tuneI) i *= 1 + step / 2;
            softenedLastRun = false;
            r.rationale.add(String.format(Locale.US, "Steady |error| %.1f deg above tolerance without offset, lag or wobble: a little more I", m.meanAbsErrorDeg));
        } else {
            good = true;
            softenedLastRun = false;
            r.rationale.add(String.format(Locale.US, "Tracking within limits (steady |err| %.1f deg, %.0f%% within tolerance, lag %.2f s): gains kept",
                    m.meanAbsErrorDeg, m.withinTolFraction * 100, m.lagSec));
        }
        p = Stats.round(Stats.clamp(p, cfg.pMin, cfg.pMax), cfg.pidDecimals);
        i = Stats.round(Stats.clamp(i, cfg.iMin, cfg.iMax), cfg.pidDecimals);
        d = Stats.round(Stats.clamp(d, cfg.dMin, cfg.dMax), cfg.pidDecimals);
        PidGains next = new PidGains(p, i, d);
        goodStreak = good ? goodStreak + 1 : 0;
        r.converged = good;
        if (goodStreak >= cfg.runsRequired) {
            r.done = true;
            r.nextPlan = new Plan(runNumber + 1, next, true);
            r.nextPlan.notes.add("Two good runs in a row: done");
        } else {
            r.nextPlan = new Plan(runNumber + 1, next, false);
            if (good) {
                r.nextPlan.notes.add("Good run " + goodStreak + " of " + cfg.runsRequired);
            }
        }
        lastReport = r;
        state = SessionState.REVIEW;
        return r;
    }

    /**
     * Ringing is fast and repetitive: either many cycles of a visible wobble or fewer cycles of a
     * large one, in both cases with a short period. Slow single overshoots are lag, not ringing.
     */
    public static boolean rings(VvtTrackingMetrics m, VvtPidConfig cfg) {
        if (Double.isNaN(m.oscillationPeriodSec) || m.oscillationPeriodSec > cfg.ringingMaxPeriodSec) {
            return false;
        }
        boolean many = m.oscillationCycles >= 3 && m.oscillationAmplitudeDeg >= Math.max(1.5, cfg.oscillationDeadbandDeg * 1.5);
        boolean big = m.oscillationCycles >= 1.5 && m.oscillationAmplitudeDeg >= cfg.oscillationDeg;
        return many || big;
    }

    public Plan commit(Report report) {
        if (state != SessionState.REVIEW || report != lastReport) {
            throw new IllegalStateException("Nothing to commit");
        }
        plan = report.nextPlan;
        current = plan.gains;
        state = report.done ? SessionState.DONE : SessionState.READY;
        return plan;
    }

    public Plan repeatRun() {
        if (state != SessionState.REVIEW) {
            throw new IllegalStateException("Nothing to repeat");
        }
        plan = new Plan(runNumber + 1, current, false);
        plan.notes.add("Repeat of the previous run");
        state = SessionState.READY;
        return plan;
    }
}
