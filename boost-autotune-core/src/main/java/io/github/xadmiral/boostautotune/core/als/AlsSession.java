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
        /** Seconds one activation may hold (ECU maximum ALS time) and the ALS cut-off RPM to write. */
        public final double holdSec;
        public final double ecuMinRpm;
        public final double holdRpm;
        public final boolean finalResult;
        public final List<String> notes = new ArrayList<String>();

        Plan(int runNumber, Grid timing, double air, AlsConfig cfg, boolean finalResult) {
            this.runNumber = runNumber;
            this.timing = timing;
            this.air = air;
            this.holdSec = cfg.holdSec;
            this.ecuMinRpm = cfg.ecuMinRpm();
            this.holdRpm = cfg.holdRpm;
            this.finalResult = finalResult;
        }

        public String title() {
            return finalResult ? "Anti-lag settled"
                    : String.format(Locale.US, "Run %d: anti-lag, idle valve %.0f, hold >= %.0f rpm for %.0f s", runNumber, air, holdRpm, holdSec);
        }

        public String driverInstructions() {
            return finalResult ? "Burn the tune. Give the turbo a cool-down drive." :
                    String.format(Locale.US, "With the anti-lag armed, in 3rd gear at 3500-5500 rpm: lift fully off the throttle for about %.0f s, then back on. "
                            + "Repeat 3-4 times with the ECU's pause time between lifts. Watch MAT; stop if anything sounds wrong.", holdSec + 1);
        }
    }

    public static final class Report {
        public final Plan plan;
        public final List<AlsEvent> events = new ArrayList<AlsEvent>();
        public final List<String> messages = new ArrayList<String>();
        public final List<String> changes = new ArrayList<String>();
        public double activeSec;
        /** Median of the lowest RPM seen in each event; NaN without events. */
        public double minRpm = Double.NaN;
        public boolean converged;
        public boolean done;
        public Plan nextPlan;

        Report(Plan plan) {
            this.plan = plan;
        }

        public String summary(AlsConfig cfg) {
            StringBuilder sb = new StringBuilder(plan.title()).append('\n');
            sb.append(String.format(Locale.US, "Events: %d, ALS active %.1f s%s\n", events.size(), activeSec,
                    Double.isNaN(minRpm) ? "" : String.format(Locale.US, ", RPM held down to %.0f (asked >= %.0f)", minRpm, cfg.holdRpm)));
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
    private double hottest = Double.NaN;
    /** Per RPM column: last shortfall, last timing change, runs without response, and columns given up on. */
    private double[] lastErr;
    private double[] lastDelta;
    private int[] noResponse;
    private boolean[] capped;

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
        int cols = timing.xAxis().size();
        lastErr = new double[cols];
        java.util.Arrays.fill(lastErr, Double.NaN);
        lastDelta = new double[cols];
        noResponse = new int[cols];
        capped = new boolean[cols];
        runNumber = 0;
        goodStreak = 0;
        abortReason = null;
        log.clear();
        plan = new Plan(1, timing.copy(), air, cfg, false);
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
            r.nextPlan = new Plan(runNumber + 1, timing.copy(), air, cfg, false);
            r.nextPlan.notes.add("Repeat with the same settings");
            lastReport = r;
            state = SessionState.REVIEW;
            return r;
        }
        Axis rpm = timing.xAxis();
        double[] errSum = new double[rpm.size()];
        int[] n = new int[rpm.size()];
        hottest = Double.NaN;
        for (AlsEvent e : events) {
            // settled samples are charged to the column of the RPM they were taken at: the engine
            // falls towards the hold RPM during an event, so the boost is made by several columns
            for (Sample x : e.active) {
                if (x.timeSec - e.startTime() < cfg.settleSec || !Stats.finite(x.map)) {
                    continue;
                }
                int col = rpm.nearest(x.rpm);
                errSum[col] += cfg.targetKpa - x.map;
                n[col]++;
            }
            double mat = e.maxMat();
            if (Stats.finite(mat)) {
                hottest = Double.isNaN(hottest) ? mat : Math.max(hottest, mat);
            }
        }
        Grid next = timing.copy();
        double nextAir = air;
        boolean allWithin = true;
        boolean anyColumn = false;
        int limitedColumns = 0;
        boolean tooMuchAtLeastRetard = false;
        for (int col = 0; col < rpm.size(); col++) {
            if (n[col] < cfg.minSamplesPerColumn) {
                continue;
            }
            anyColumn = true;
            double err = errSum[col] / n[col];
            if (Math.abs(err) <= cfg.tolKpa) {
                r.changes.add(String.format(Locale.US, "%.0f rpm: MAP within %.0f kPa of target - kept", rpm.bin(col), cfg.tolKpa));
                lastErr[col] = err;
                lastDelta[col] = 0;
                noResponse[col] = 0;
                continue;
            }
            if (capped[col]) {
                r.changes.add(String.format(Locale.US, "%.0f rpm: MAP %.0f kPa short, retard has no effect here - kept as is", rpm.bin(col), err));
                continue;
            }
            // more retard last time but no more boost: after two such runs the anti-lag simply cannot
            // make more boost at this RPM (too little exhaust energy), so stop adding heat there
            if (err > 0 && lastDelta[col] < 0 && Stats.finite(lastErr[col]) && err > lastErr[col] - 1.5) {
                noResponse[col]++;
            } else {
                noResponse[col] = 0;
            }
            lastErr[col] = err;
            if (err > 0 && noResponse[col] >= 2) {
                capped[col] = true;
                lastDelta[col] = 0;
                limitedColumns++;
                r.changes.add(String.format(Locale.US, "%.0f rpm: two more-retard steps brought no more boost (MAP %.0f kPa short): the anti-lag cannot do more here - accepted",
                        rpm.bin(col), err));
                continue;
            }
            // bigger steps while far from the target, single steps close to it
            double mult = Stats.clamp(Math.abs(err) / (1.5 * cfg.tolKpa), 1, 3);
            double delta = (err > 0 ? -cfg.timingStepDeg : cfg.timingStepDeg) * mult;
            if (mult > 1) {
                delta = Math.round(delta / cfg.timingStepDeg) * cfg.timingStepDeg;
            }
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
            lastDelta[col] = moved ? delta : 0;
            if (moved) {
                allWithin = false;
                r.changes.add(String.format(Locale.US, "%.0f rpm: MAP %s target by %.0f kPa -> timing %s%.0f deg",
                        rpm.bin(col), err > 0 ? "below" : "above", Math.abs(err), delta < 0 ? "" : "+", delta));
            } else if (err > 0) {
                // as much boost as the retard limit allows: accepted, the driver is told
                limitedColumns++;
                r.changes.add(String.format(Locale.US, "%.0f rpm: timing at the retard limit %.0f deg, MAP %.0f kPa short of target - accepted",
                        rpm.bin(col), cfg.minTimingDeg, err));
            } else {
                allWithin = false;
                tooMuchAtLeastRetard = true;
                r.changes.add(String.format(Locale.US, "%.0f rpm: timing already at %.0f deg and still %.0f kPa too much boost", rpm.bin(col), cfg.maxTimingDeg, -err));
            }
        }
        if (limitedColumns > 0) {
            r.messages.add(String.format(Locale.US,
                    "%d column(s) cannot reach %.0f kPa: the retard limit or the exhaust energy at that RPM is the ceiling - lower the target, or raise the hold RPM",
                    limitedColumns, cfg.targetKpa));
        }

        // ---- RPM hold: the idle valve air keeps the engine from dropping below the hold RPM ----
        List<Double> mins = new ArrayList<Double>();
        for (AlsEvent e : events) {
            double m = e.minRpm();
            if (Stats.finite(m)) {
                mins.add(m);
            }
        }
        r.minRpm = mins.isEmpty() ? Double.NaN : Stats.median(mins);
        boolean rpmOk = true;
        if (cfg.tuneAir && Stats.finite(r.minRpm)) {
            double shortfall = cfg.holdRpm - r.minRpm;
            if (shortfall > cfg.holdTolRpm) {
                if (air >= cfg.airMax - 1e-9) {
                    r.messages.add(String.format(Locale.US,
                            "RPM fell to %.0f, %.0f below the hold RPM, but the idle valve is already at its maximum %.0f - accepted",
                            r.minRpm, shortfall, cfg.airMax));
                } else {
                    rpmOk = false;
                    double mult = Stats.clamp(shortfall / (2 * cfg.holdTolRpm), 1, 3);
                    nextAir = Math.min(cfg.airMax, air + cfg.airStep * mult);
                    r.changes.add(String.format(Locale.US, "RPM fell to %.0f (hold >= %.0f): idle valve air %.0f -> %.0f",
                            r.minRpm, cfg.holdRpm, air, nextAir));
                }
            } else if (tooMuchAtLeastRetard && air > cfg.airMin + 1e-9) {
                nextAir = Math.max(cfg.airMin, air - cfg.airStep);
                r.changes.add(String.format(Locale.US, "Too much boost at the least retard: idle valve air %.0f -> %.0f", air, nextAir));
            } else if (shortfall < -2 * cfg.holdTolRpm && air > cfg.airMin + 1e-9) {
                nextAir = Math.max(cfg.airMin, air - cfg.airStep);
                r.changes.add(String.format(Locale.US, "RPM held at %.0f, well above %.0f: idle valve air %.0f -> %.0f (less bleed and heat)",
                        r.minRpm, cfg.holdRpm, air, nextAir));
            } else {
                r.changes.add(String.format(Locale.US, "RPM held down to %.0f (hold >= %.0f) - kept", r.minRpm, cfg.holdRpm));
            }
        } else if (Stats.finite(r.minRpm) && cfg.holdRpm - r.minRpm > cfg.holdTolRpm) {
            r.messages.add(String.format(Locale.US, "RPM fell to %.0f, below the hold RPM %.0f (air tuning is off)", r.minRpm, cfg.holdRpm));
        }
        if (Stats.finite(hottest) && hottest > cfg.maxMatC) {
            r.messages.add(String.format(Locale.US, "MAT reached %.0f C (warning above %.0f): give it a cool-down before the next run", hottest, cfg.maxMatC));
        }
        if (!anyColumn) {
            r.messages.add("Events were too short or too few settled samples: repeat");
            r.nextPlan = new Plan(runNumber + 1, timing.copy(), air, cfg, false);
            r.nextPlan.notes.add("Repeat with the same settings");
            lastReport = r;
            state = SessionState.REVIEW;
            return r;
        }
        boolean good = allWithin && rpmOk;
        goodStreak = good ? goodStreak + 1 : 0;
        r.converged = good;
        if (goodStreak >= cfg.runsRequired) {
            r.done = true;
            r.nextPlan = new Plan(runNumber + 1, next, nextAir, cfg, true);
            r.nextPlan.notes.add("Off-throttle boost and RPM hold on target in " + goodStreak + " runs in a row");
        } else {
            r.nextPlan = new Plan(runNumber + 1, next, nextAir, cfg, false);
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
        plan = new Plan(runNumber + 1, timing.copy(), air, cfg, false);
        plan.notes.add("Repeat of the previous run");
        state = SessionState.READY;
        return plan;
    }
}
