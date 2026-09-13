package io.github.xadmiral.boostautotune.core.als;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Anti-lag autotune. Each run is a handful of "lift-offs" with the anti-lag armed. Per RPM column
 * of the ALS timing table the off-throttle boost is compared with the target: too little boost
 * means more retard (down to the retard limit, then more idle-valve air), too much means less
 * retard. Heat and stalling are watched all the time.
 */
public final class AlsSession {

    public static final class Plan {
        public final int runNumber;
        public final Grid timing;
        public final double air;
        public final boolean finalResult;
        public final List<String> notes = new ArrayList<String>();

        Plan(int runNumber, Grid timing, double air, boolean finalResult) {
            this.runNumber = runNumber;
            this.timing = timing;
            this.air = air;
            this.finalResult = finalResult;
        }

        public String title() {
            return finalResult ? "Anti-lag settled" : String.format(Locale.US, "Run %d: anti-lag, idle valve %.0f", runNumber, air);
        }

        public String driverInstructions() {
            return finalResult ? "Burn the tune. Give the turbo a cool-down drive." :
                    "With the anti-lag armed, in 3rd gear at 3500-5500 rpm: lift fully off the throttle for 2-3 s, then back on. "
                            + "Repeat 3-4 times with the ECU's pause time between lifts. Watch MAT; stop if anything sounds wrong.";
        }
    }

    public static final class Report {
        public final Plan plan;
        public final List<AlsEvent> events = new ArrayList<AlsEvent>();
        public final List<String> messages = new ArrayList<String>();
        public final List<String> changes = new ArrayList<String>();
        public double activeSec;
        public boolean converged;
        public boolean done;
        public Plan nextPlan;

        Report(Plan plan) {
            this.plan = plan;
        }

        public String summary(AlsConfig cfg) {
            StringBuilder sb = new StringBuilder(plan.title()).append('\n');
            sb.append(String.format(Locale.US, "Events: %d, ALS active %.1f s\n", events.size(), activeSec));
            for (AlsEvent e : events) {
                sb.append("  ").append(e.summary(cfg)).append('\n');
            }
            for (String c : changes) {
                sb.append("  ").append(c).append('\n');
            }
            for (String m : messages) {
                sb.append("  ").append(m).append('\n');
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

    private final AlsConfig cfg;
    private SessionState state = SessionState.IDLE;
    private Grid originalTiming;
    private double originalAir;
    private Grid timing;
    private double air;
    private Plan plan;
    private Report lastReport;
    private int runNumber;
    private int goodStreak;
    private String abortReason;
    private final List<AlsEvent> events = new ArrayList<AlsEvent>();
    private AlsEvent current;
    private boolean armed;
    private double lastActiveTime = Double.NaN;
    private double activeSec;
    private double lastSampleTime = Double.NaN;
    private double lastEventEnd = Double.NaN;
    private boolean haveFlag;
    private final List<String> log = new ArrayList<String>();

    public AlsSession(AlsConfig cfg) {
        this.cfg = cfg.copy();
    }

    public AlsConfig config() {
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

    public Grid originalTiming() {
        return originalTiming == null ? null : originalTiming.copy();
    }

    public double originalAir() {
        return originalAir;
    }

    public int eventsInRun() {
        return events.size() + (current != null ? 1 : 0);
    }

    public double activeSecInRun() {
        return activeSec;
    }

    public List<String> log() {
        return new ArrayList<String>(log);
    }

    public Plan initialize(Grid ecuTiming, double ecuAir) {
        List<String> problems = cfg.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuration problems: " + problems);
        }
        originalTiming = ecuTiming.copy();
        originalAir = ecuAir;
        timing = ecuTiming.copy();
        air = ecuAir;
        runNumber = 0;
        goodStreak = 0;
        abortReason = null;
        log.clear();
        plan = new Plan(1, timing.copy(), air, false);
        state = SessionState.READY;
        return plan;
    }

    public void startRun() {
        if (state != SessionState.READY) {
            throw new IllegalStateException("Cannot start a run in state " + state);
        }
        events.clear();
        current = null;
        armed = false;
        activeSec = 0;
        lastActiveTime = Double.NaN;
        lastSampleTime = Double.NaN;
        lastEventEnd = Double.NaN;
        haveFlag = false;
        runNumber = plan.runNumber;
        state = SessionState.RECORDING;
        log.add(plan.title() + " started");
    }

    /** True while the sample is inside an ALS window (ECU flag, or inferred when no flag is bound). */
    private boolean isActive(Sample s) {
        if (s.alsActive) {
            haveFlag = true;
        }
        if (haveFlag) {
            return s.alsActive;
        }
        if (s.tps > cfg.onThrottleTps) {
            armed = true;
            return false;
        }
        return armed && s.tps <= cfg.offThrottleTps && s.rpm >= cfg.minRpm && s.rpm <= cfg.maxRpm;
    }

    public void onSample(Sample s) {
        if (state != SessionState.RECORDING) {
            return;
        }
        double dt = Double.isNaN(lastSampleTime) ? 0 : Math.max(0, s.timeSec - lastSampleTime);
        lastSampleTime = s.timeSec;
        if (Stats.finite(s.map) && s.map > cfg.maxBoostKpa) {
            abort(String.format(Locale.US, "Overboost %.1f kPa above the %.0f kPa limit", s.map, cfg.maxBoostKpa));
            return;
        }
        boolean active = isActive(s);
        if ((active || current != null) && s.rpm < cfg.stallRpm) {
            abort(String.format(Locale.US, "RPM fell to %.0f during anti-lag (stall guard %.0f)", s.rpm, cfg.stallRpm));
            return;
        }
        if (active) {
            if (Stats.finite(s.mat) && s.mat >= cfg.abortMatC) {
                abort(String.format(Locale.US, "MAT %.0f C reached the abort limit %.0f C - let it cool down", s.mat, cfg.abortMatC));
                return;
            }
            activeSec += dt;
            if (activeSec > cfg.maxActiveSecPerRun) {
                abort(String.format(Locale.US, "More than %.0f s of anti-lag in one run - cool-down needed", cfg.maxActiveSecPerRun));
                return;
            }
            if (current == null) {
                current = new AlsEvent();
            }
            current.active.add(s);
            lastActiveTime = s.timeSec;
            return;
        }
        if (current != null) {
            // window closed: collect the re-spool until the throttle closes again or 3 s pass
            if (s.tps >= cfg.onThrottleTps || !current.after.isEmpty()) {
                current.after.add(s);
            }
            if (s.timeSec - lastActiveTime > cfg.respoolMaxSec || (s.tps < cfg.offThrottleTps && !current.after.isEmpty())) {
                closeEvent();
            }
        }
    }

    private void closeEvent() {
        if (current != null) {
            if (current.durationSec() >= cfg.minEventSec) {
                events.add(current);
            }
            lastEventEnd = current.endTime();
            current = null;
        }
    }

    public boolean runLooksFinished() {
        if (state != SessionState.RECORDING || cfg.autoEndRunIdleSec <= 0 || events.isEmpty() || current != null) {
            return false;
        }
        return Stats.finite(lastEventEnd) && lastSampleTime - lastEventEnd >= cfg.autoEndRunIdleSec;
    }

    public void abort(String reason) {
        if (state == SessionState.ABORTED) {
            return;
        }
        abortReason = reason;
        state = SessionState.ABORTED;
        log.add("ABORTED: " + reason);
    }

    public Report endRun() {
        if (state != SessionState.RECORDING) {
            throw new IllegalStateException("No run in progress (state " + state + ")");
        }
        closeEvent();
        Report r = new Report(plan);
        r.events.addAll(events);
        r.activeSec = activeSec;
        if (events.isEmpty()) {
            r.messages.add("No anti-lag event recorded (need >= " + cfg.minEventSec + " s off throttle with ALS active)");
            r.nextPlan = new Plan(runNumber + 1, timing.copy(), air, false);
            r.nextPlan.notes.add("Repeat with the same settings");
            lastReport = r;
            state = SessionState.REVIEW;
            return r;
        }
        Axis rpm = timing.xAxis();
        double[] errSum = new double[rpm.size()];
        int[] n = new int[rpm.size()];
        double hottest = Double.NaN;
        for (AlsEvent e : events) {
            double map = e.meanMapSettled(cfg.settleSec);
            if (Double.isNaN(map) || e.settledSamples(cfg.settleSec) < cfg.minSamplesPerColumn) {
                continue;
            }
            int col = rpm.nearest(e.meanRpm());
            errSum[col] += cfg.targetKpa - map;
            n[col]++;
            double mat = e.maxMat();
            if (Stats.finite(mat)) {
                hottest = Double.isNaN(hottest) ? mat : Math.max(hottest, mat);
            }
        }
        Grid next = timing.copy();
        double nextAir = air;
        boolean allWithin = true;
        boolean anyColumn = false;
        boolean needMoreAir = false;
        double largestShortfall = 0;
        for (int col = 0; col < rpm.size(); col++) {
            if (n[col] == 0) {
                continue;
            }
            anyColumn = true;
            double err = errSum[col] / n[col];
            if (Math.abs(err) <= cfg.tolKpa) {
                r.changes.add(String.format(Locale.US, "%.0f rpm: MAP within %.0f kPa of target - kept", rpm.bin(col), cfg.tolKpa));
                continue;
            }
            allWithin = false;
            // bigger steps while far from the target, single steps close to it
            double mult = Stats.clamp(Math.abs(err) / (2 * cfg.tolKpa), 1, 3);
            double delta = (err > 0 ? -cfg.timingStepDeg : cfg.timingStepDeg) * mult;
            if (mult > 1) {
                delta = Math.round(delta / cfg.timingStepDeg) * cfg.timingStepDeg;
            }
            largestShortfall = Math.max(largestShortfall, err);
            boolean moved = false;
            for (int yi = 0; yi < next.height(); yi++) {
                if (next.yAxis().bin(yi) > cfg.maxRowTps) {
                    continue;
                }
                double old = next.get(col, yi);
                double v = Stats.clamp(old + delta, cfg.minTimingDeg, cfg.maxTimingDeg);
                if (Math.abs(v - old) > 1e-9) {
                    moved = true;
                }
                next.set(col, yi, v);
            }
            if (moved) {
                r.changes.add(String.format(Locale.US, "%.0f rpm: MAP %s target by %.0f kPa -> timing %s%.0f deg",
                        rpm.bin(col), err > 0 ? "below" : "above", Math.abs(err), delta < 0 ? "" : "+", delta));
            } else if (err > 0) {
                needMoreAir = true;
                r.changes.add(String.format(Locale.US, "%.0f rpm: timing already at the retard limit %.0f deg", rpm.bin(col), cfg.minTimingDeg));
            } else {
                r.changes.add(String.format(Locale.US, "%.0f rpm: timing already at %.0f deg and still too much boost - lower the idle valve air", rpm.bin(col), cfg.maxTimingDeg));
                if (cfg.tuneAir) {
                    nextAir = Math.max(cfg.airMin, nextAir - cfg.airStep);
                }
            }
        }
        if (needMoreAir && cfg.tuneAir) {
            double mult = Stats.clamp(largestShortfall / (2 * cfg.tolKpa), 1, 3);
            nextAir = Math.min(cfg.airMax, air + cfg.airStep * mult);
            r.changes.add(String.format(Locale.US, "Idle valve air %.0f -> %.0f", air, nextAir));
        }
        if (Stats.finite(hottest) && hottest > cfg.maxMatC) {
            r.messages.add(String.format(Locale.US, "MAT reached %.0f C (warning above %.0f): give it a cool-down before the next run", hottest, cfg.maxMatC));
        }
        if (!anyColumn) {
            r.messages.add("Events were too short or too few settled samples: repeat");
            r.nextPlan = new Plan(runNumber + 1, timing.copy(), air, false);
            r.nextPlan.notes.add("Repeat with the same settings");
            lastReport = r;
            state = SessionState.REVIEW;
            return r;
        }
        boolean good = allWithin;
        goodStreak = good ? goodStreak + 1 : 0;
        r.converged = good;
        if (goodStreak >= cfg.runsRequired) {
            r.done = true;
            r.nextPlan = new Plan(runNumber + 1, next, nextAir, true);
            r.nextPlan.notes.add("Off-throttle boost on target in " + goodStreak + " runs in a row");
        } else {
            r.nextPlan = new Plan(runNumber + 1, next, nextAir, false);
            if (good) {
                r.nextPlan.notes.add("Good run " + goodStreak + " of " + cfg.runsRequired);
            }
        }
        lastReport = r;
        state = SessionState.REVIEW;
        log.add(r.summary(cfg));
        return r;
    }

    public Plan commit(Report report) {
        if (state != SessionState.REVIEW || report != lastReport) {
            throw new IllegalStateException("Nothing to commit");
        }
        plan = report.nextPlan;
        timing = plan.timing.copy();
        air = plan.air;
        state = report.done ? SessionState.DONE : SessionState.READY;
        return plan;
    }

    public Plan repeatRun() {
        if (state != SessionState.REVIEW) {
            throw new IllegalStateException("Nothing to repeat");
        }
        plan = new Plan(runNumber + 1, timing.copy(), air, false);
        plan.notes.add("Repeat of the previous run");
        state = SessionState.READY;
        return plan;
    }
}
