package io.github.xadmiral.boostautotune.core.knock;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.LoadSource;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Knock sensor calibration. A run is two or three full-throttle pulls on a timing map that does
 * not knock. Per RPM bin of the ECU's knock threshold curve the knock input level is collected
 * (per cylinder when the ECU reports it) and its distribution describes the engine's mechanical
 * noise through the sensor. Two things are then set from it:
 * <ul>
 * <li><b>gains</b> (survey phase): the noisiest bin / cylinder is brought to the noise target and
 * the cylinders are equalised, moving each gain by at most a factor per run;</li>
 * <li><b>thresholds</b> (verify phase): a margin above the noise per bin, then checked on further
 * pulls: a bin whose samples cross its threshold, or where the ECU's knock control fired, is
 * raised; a threshold far above the noise is tightened.</li>
 * </ul>
 * Isolated spikes well above the bin's median are reported as possible knock and kept out of the
 * noise estimate.
 */
public final class KnockCalSession {

    public enum Phase { SURVEY, VERIFY }

    public static final class Plan {
        public final int runNumber;
        public final Phase phase;
        public final double[] thresholds;
        public final double[] gains;
        public final boolean finalResult;
        public final List<String> notes = new ArrayList<String>();
        private final double minRpm;
        private final double maxRpm;

        Plan(int runNumber, Phase phase, double[] thresholds, double[] gains, boolean finalResult, KnockCalConfig cfg) {
            this.runNumber = runNumber;
            this.phase = phase;
            this.thresholds = thresholds.clone();
            this.gains = gains.clone();
            this.finalResult = finalResult;
            this.minRpm = cfg.minRpm;
            this.maxRpm = cfg.maxRpm;
        }

        public String title() {
            if (finalResult) {
                return "Knock calibration settled: gains " + gainsText(gains) + ", thresholds " + listText(thresholds, "%.0f");
            }
            return String.format(Locale.US, "Run %d: %s, gains %s, thresholds %s", runNumber,
                    phase == Phase.SURVEY ? "noise survey" : "threshold check", gainsText(gains), listText(thresholds, "%.0f"));
        }

        public String driverInstructions() {
            if (finalResult) {
                return "Burn the tune. Knock control is now set to the engine's noise; keep an ear open on the first hard pulls.";
            }
            return String.format(Locale.US, "Engine warm, on a timing map you trust: 2-3 full-throttle pulls in 3rd gear from about %.0f to %.0f rpm. "
                    + "No knock is wanted here: lift at once if you hear any.", Math.max(1500, minRpm), maxRpm);
        }
    }

    /** Knock level statistics of one RPM bin over one run. */
    public static final class BinStats {
        public final double rpm;
        public int n;
        public double p50 = Double.NaN;
        /** 95th percentile and maximum without the spikes. */
        public double p95 = Double.NaN;
        public double max = Double.NaN;
        public int spikes;
        /** Verify phase: plain (non-spike) samples at or above the threshold curve, and knock retard onsets. */
        public int exceed;
        public int retards;

        /** Enough spikes to look like real knock rather than a stray noise sample. */
        public boolean knockSuspect() {
            return spikes >= 3 || spikes > 0.1 * n;
        }
        /** Per-cylinder 95th percentiles (null without per-cylinder channels). */
        public double[] cylP95;

        BinStats(double rpm) {
            this.rpm = rpm;
        }

        public boolean measured(int minSamples) {
            return n >= minSamples;
        }
    }

    public static final class Report {
        public final Plan plan;
        public final BinStats[] bins;
        /** Per tuned gain: the loudest 95th percentile over the measured bins (NaN without data). */
        public double[] cylReference;
        /** The noisiest measured bin / cylinder: what the gains are set from. */
        public double reference = Double.NaN;
        public int samples;
        public int retardEvents;
        public final List<String> changes = new ArrayList<String>();
        public final List<String> messages = new ArrayList<String>();
        public boolean converged;
        public boolean done;
        public Plan nextPlan;

        Report(Plan plan, BinStats[] bins) {
            this.plan = plan;
            this.bins = bins;
        }

        public String summary(KnockCalConfig cfg) {
            StringBuilder sb = new StringBuilder(plan.title()).append('\n');
            sb.append(String.format(Locale.US, "Samples at load: %d, knock retard fired %d time(s)%s\n", samples, retardEvents,
                    Double.isNaN(reference) ? "" : String.format(Locale.US, "; loudest noise (p95) %.0f %%, target %.0f (%.0f..%.0f)",
                            reference, cfg.noiseTargetPct, cfg.noiseBandLowPct, cfg.noiseBandHighPct)));
            sb.append("   rpm    n   p50   p95   max  spikes  over  retard  threshold\n");
            for (int i = 0; i < bins.length; i++) {
                BinStats b = bins[i];
                double next = nextPlan == null ? plan.thresholds[i] : nextPlan.thresholds[i];
                sb.append(String.format(Locale.US, "%6.0f %4d %s %s %s %6d %5d %7d  %5.1f%s\n", b.rpm, b.n, num5(b.p50), num5(b.p95), num5(b.max),
                        b.spikes, b.exceed, b.retards, plan.thresholds[i],
                        Math.abs(next - plan.thresholds[i]) > 1e-9 ? String.format(Locale.US, " -> %.1f", next) : ""));
            }
            if (cylReference != null && cylReference.length > 1) {
                sb.append("Per cylinder p95: ");
                for (int c = 0; c < cylReference.length; c++) {
                    sb.append(c > 0 ? ", " : "").append(String.format(Locale.US, "#%d %s %% (gain %s)", c + 1, num5(cylReference[c]).trim(), gainText(plan.gains[c])));
                }
                sb.append('\n');
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

    private final KnockCalConfig cfg;
    private SessionState state = SessionState.IDLE;
    private Axis rpm;
    private double[] originalThresholds;
    private double[] originalGains;
    private double[] thresholds;
    private double[] gains;
    private double[] gainOptions;
    private Phase phase = Phase.SURVEY;
    /** Bins raised by a verification run: never tightened again in this session. */
    private boolean[] raised;
    private Plan plan;
    private Report lastReport;
    private int runNumber;
    private int goodStreak;
    private String abortReason;
    private final List<String> log = new ArrayList<String>();
    // ---- per run ----
    private List<List<double[]>> levels;
    private int[] retardOnsets;
    private double prevRetard;
    private double lastSampleTime = Double.NaN;
    private double lastEligibleTime = Double.NaN;
    private int eligible;
    private int pulls;

    public KnockCalSession(KnockCalConfig cfg) {
        this.cfg = cfg.copy();
    }

    public KnockCalConfig config() {
        return cfg;
    }

    public SessionState state() {
        return state;
    }

    public Phase phase() {
        return phase;
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

    public double[] originalThresholds() {
        return originalThresholds == null ? null : originalThresholds.clone();
    }

    public double[] originalGains() {
        return originalGains == null ? null : originalGains.clone();
    }

    public int samplesInRun() {
        return eligible;
    }

    public int pullsInRun() {
        return pulls;
    }

    public List<String> log() {
        return new ArrayList<String>(log);
    }

    /**
     * @param rpmBins     the RPM axis of the ECU's threshold curve
     * @param thresholds  the current thresholds, one per bin
     * @param gains       the gains to tune: one value (single gain) or one per cylinder
     * @param gainOptions the gain values the ECU offers (null = any value)
     */
    public Plan initialize(double[] rpmBins, double[] thresholds, double[] gains, double[] gainOptions) {
        List<String> problems = cfg.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuration problems: " + problems);
        }
        if (rpmBins.length != thresholds.length) {
            throw new IllegalArgumentException("Threshold curve has " + thresholds.length + " values for " + rpmBins.length + " RPM bins");
        }
        if (gains.length == 0) {
            throw new IllegalArgumentException("At least one gain is needed");
        }
        rpm = new Axis(rpmBins);
        originalThresholds = thresholds.clone();
        originalGains = gains.clone();
        this.thresholds = thresholds.clone();
        this.gains = gains.clone();
        this.gainOptions = gainOptions == null || gainOptions.length == 0 ? null : gainOptions.clone();
        if (this.gainOptions != null) {
            Arrays.sort(this.gainOptions);
        }
        raised = new boolean[rpmBins.length];
        phase = Phase.SURVEY;
        runNumber = 0;
        goodStreak = 0;
        abortReason = null;
        log.clear();
        plan = new Plan(1, phase, this.thresholds, this.gains, false, cfg);
        state = SessionState.READY;
        return plan;
    }

    public void startRun() {
        if (state != SessionState.READY) {
            throw new IllegalStateException("Cannot start a run in state " + state);
        }
        levels = new ArrayList<List<double[]>>();
        for (int i = 0; i < rpm.size(); i++) {
            levels.add(new ArrayList<double[]>());
        }
        retardOnsets = new int[rpm.size()];
        prevRetard = 0;
        lastSampleTime = Double.NaN;
        lastEligibleTime = Double.NaN;
        eligible = 0;
        pulls = 0;
        runNumber = plan.runNumber;
        state = SessionState.RECORDING;
        log.add(plan.title() + " started");
    }

    private boolean atLoad(Sample s) {
        double load = s.load(LoadSource.IGN_LOAD);
        if (Stats.finite(load)) {
            return load >= cfg.minLoad;
        }
        return s.tps >= cfg.wotTps;
    }

    public void onSample(Sample s) {
        if (state != SessionState.RECORDING) {
            return;
        }
        lastSampleTime = s.timeSec;
        if (Stats.finite(s.map) && s.map > cfg.maxBoostKpa) {
            abort(String.format(Locale.US, "Overboost %.1f kPa above the %.0f kPa limit", s.map, cfg.maxBoostKpa));
            return;
        }
        boolean inWindow = s.rpm >= cfg.minRpm && s.rpm <= cfg.maxRpm;
        if (Stats.finite(s.knockRetard)) {
            if (s.knockRetard > 1e-9 && prevRetard <= 1e-9 && inWindow) {
                retardOnsets[rpm.nearest(s.rpm)]++;
            }
            prevRetard = s.knockRetard;
        }
        if (!inWindow || !atLoad(s) || s.clt < cfg.minCltC || !Stats.finite(s.knock)) {
            return;
        }
        if (Double.isNaN(lastEligibleTime) || s.timeSec - lastEligibleTime > 1.5) {
            pulls++;
        }
        lastEligibleTime = s.timeSec;
        eligible++;
        // per eligible sample: [rpm, overall level, cylinder levels...]
        int n = gains.length;
        double[] v = new double[2 + n];
        v[0] = s.rpm;
        v[1] = s.knock;
        boolean perCyl = s.knockCyl != null && n > 1;
        for (int c = 0; c < n; c++) {
            v[2 + c] = perCyl && c < s.knockCyl.length && Stats.finite(s.knockCyl[c]) ? s.knockCyl[c] : Double.NaN;
        }
        levels.get(rpm.nearest(s.rpm)).add(v);
    }

    public boolean runLooksFinished() {
        if (state != SessionState.RECORDING || cfg.autoEndRunIdleSec <= 0 || eligible < cfg.minSamplesPerBin) {
            return false;
        }
        return Stats.finite(lastEligibleTime) && lastSampleTime - lastEligibleTime >= cfg.autoEndRunIdleSec;
    }

    public void abort(String reason) {
        if (state == SessionState.ABORTED) {
            return;
        }
        abortReason = reason;
        state = SessionState.ABORTED;
        log.add("ABORTED: " + reason);
    }

    // ---- analysis --------------------------------------------------------------------------------

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double pos = p * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return Stats.lerp(sorted[lo], sorted[hi], pos - lo);
    }

    /** Threshold of the current curve at an RPM (linear between bins, flat outside). */
    private double thresholdAt(double r) {
        Axis.Pos p = rpm.locate(r);
        return thresholds[p.i0] * p.w0() + thresholds[p.i1] * p.w1;
    }

    private BinStats stats(int bin) {
        BinStats b = new BinStats(rpm.bin(bin));
        List<double[]> xs = levels.get(bin);
        b.n = xs.size();
        b.retards = retardOnsets[bin];
        if (xs.isEmpty()) {
            return b;
        }
        double[] all = new double[xs.size()];
        for (int i = 0; i < all.length; i++) {
            all[i] = xs.get(i)[1];
        }
        Arrays.sort(all);
        b.p50 = percentile(all, 0.5);
        double spikeAbove = cfg.spikeFactor * b.p50 + 3;
        List<Double> kept = new ArrayList<Double>();
        for (double v : all) {
            if (v > spikeAbove) {
                b.spikes++;
            } else {
                kept.add(v);
            }
        }
        double[] k = new double[kept.size()];
        for (int i = 0; i < k.length; i++) {
            k[i] = kept.get(i);
        }
        b.p95 = k.length == 0 ? b.p50 : percentile(k, 0.95);
        b.max = k.length == 0 ? b.p50 : k[k.length - 1];
        for (double[] v : xs) {
            if (v[1] > spikeAbove) {
                continue; // spikes are reported, not treated as noise crossing the threshold
            }
            double thr = thresholdAt(v[0]);
            boolean over = v[1] >= thr;
            for (int c = 2; c < v.length && !over; c++) {
                over = Stats.finite(v[c]) && v[c] >= thr;
            }
            if (over) {
                b.exceed++;
            }
        }
        int n = gains.length;
        if (n > 1) {
            double[] cyl = new double[n];
            boolean any = false;
            for (int c = 0; c < n; c++) {
                List<Double> vs = new ArrayList<Double>();
                for (double[] v : xs) {
                    if (Stats.finite(v[2 + c]) && v[2 + c] <= spikeAbove) {
                        vs.add(v[2 + c]);
                    }
                }
                if (vs.isEmpty()) {
                    cyl[c] = Double.NaN;
                    continue;
                }
                double[] a = new double[vs.size()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = vs.get(i);
                }
                Arrays.sort(a);
                cyl[c] = percentile(a, 0.95);
                any = true;
            }
            b.cylP95 = any ? cyl : null;
        }
        return b;
    }

    private double snap(double gain) {
        if (gainOptions == null) {
            return Stats.round(gain, 3);
        }
        double best = gainOptions[0];
        for (double o : gainOptions) {
            if (Math.abs(o - gain) < Math.abs(best - gain)) {
                best = o;
            }
        }
        return best;
    }

    static String gainText(double g) {
        return String.format(Locale.US, "%.3f", g);
    }

    static String gainsText(double[] g) {
        if (g.length == 1) {
            return gainText(g[0]);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < g.length; i++) {
            sb.append(i > 0 ? " " : "").append(gainText(g[i]));
        }
        return sb.append(']').toString();
    }

    static String listText(double[] v, String fmt) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i > 0 ? " " : "").append(String.format(Locale.US, fmt, v[i]));
        }
        return sb.append(']').toString();
    }

    private static String num5(double v) {
        return Double.isNaN(v) ? "    -" : String.format(Locale.US, "%5.1f", v);
    }

    public Report endRun() {
        if (state != SessionState.RECORDING) {
            throw new IllegalStateException("No run in progress (state " + state + ")");
        }
        BinStats[] bins = new BinStats[rpm.size()];
        int measured = 0;
        for (int i = 0; i < bins.length; i++) {
            bins[i] = stats(i);
            if (bins[i].measured(cfg.minSamplesPerBin)) {
                measured++;
            }
        }
        Report r = new Report(plan, bins);
        r.samples = eligible;
        for (int i = 0; i < bins.length; i++) {
            r.retardEvents += bins[i].retards;
        }
        if (measured == 0) {
            r.messages.add(String.format(Locale.US, "No RPM bin got %d samples at load >= %.0f between %.0f and %.0f rpm: repeat with full-throttle pulls (engine warm)",
                    cfg.minSamplesPerBin, cfg.minLoad, cfg.minRpm, cfg.maxRpm));
            r.nextPlan = new Plan(runNumber + 1, phase, thresholds, gains, false, cfg);
            r.nextPlan.notes.add("Repeat with the same settings");
            lastReport = r;
            state = SessionState.REVIEW;
            return r;
        }
        // reference noise: the loudest measured bin, per cylinder when known
        int n = gains.length;
        r.cylReference = new double[n];
        Arrays.fill(r.cylReference, Double.NaN);
        boolean perCyl = false;
        double clip = Double.NEGATIVE_INFINITY;
        for (BinStats b : bins) {
            if (!b.measured(cfg.minSamplesPerBin)) {
                continue;
            }
            r.reference = Double.isNaN(r.reference) ? b.p95 : Math.max(r.reference, b.p95);
            clip = Math.max(clip, b.max);
            if (b.cylP95 != null) {
                perCyl = true;
                for (int c = 0; c < n; c++) {
                    if (Stats.finite(b.cylP95[c])) {
                        r.cylReference[c] = Double.isNaN(r.cylReference[c]) ? b.cylP95[c] : Math.max(r.cylReference[c], b.cylP95[c]);
                    }
                }
            }
            if (b.knockSuspect()) {
                r.messages.add(String.format(Locale.US, "%.0f rpm: %d spike(s) far above the noise - possible real knock; check the timing map. The noise estimate ignores them.",
                        b.rpm, b.spikes));
            }
        }
        if (perCyl) {
            double loudest = Double.NaN;
            for (int c = 0; c < n; c++) {
                if (Stats.finite(r.cylReference[c])) {
                    loudest = Double.isNaN(loudest) ? r.cylReference[c] : Math.max(loudest, r.cylReference[c]);
                }
            }
            if (Stats.finite(loudest)) {
                r.reference = Math.max(r.reference, loudest);
            }
        } else {
            Arrays.fill(r.cylReference, r.reference);
        }
        if (r.retardEvents > 0 && phase == Phase.SURVEY) {
            r.messages.add(String.format(Locale.US, "The ECU's knock control fired %d time(s) in the survey: the old thresholds sit in the noise, or the engine really knocked",
                    r.retardEvents));
        }

        double[] nextGains = gains.clone();
        double[] nextThr = thresholds.clone();
        boolean gainMoved = false;
        double lo = cfg.noiseBandLowPct;
        double hi = cfg.noiseBandHighPct;
        boolean clipping = clip >= 98;
        // in the verify phase the gains are left alone unless they drifted well outside the band
        boolean gainCheck = phase == Phase.SURVEY || clipping || r.reference < lo / 1.5 || r.reference > hi * 1.3;
        if (gainCheck) {
            double factorSum = 0;
            int factorN = 0;
            for (int c = 0; c < n; c++) {
                double ref = r.cylReference[c];
                if (!Stats.finite(ref)) {
                    continue;
                }
                boolean out = ref < lo || ref > hi;
                boolean unbalanced = false;
                if (perCyl && cfg.balanceCylinders && !out) {
                    // a cylinder well below the loudest one is turned up so one threshold curve fits all
                    double loudest = 0;
                    for (int k = 0; k < n; k++) {
                        if (Stats.finite(r.cylReference[k])) {
                            loudest = Math.max(loudest, r.cylReference[k]);
                        }
                    }
                    unbalanced = loudest - ref > cfg.cylinderImbalancePct / 100.0 * loudest;
                }
                if (!out && !unbalanced && !clipping) {
                    continue;
                }
                double f;
                if (ref < 3) {
                    f = cfg.maxGainFactorPerRun;
                } else {
                    f = cfg.noiseTargetPct / ref;
                }
                if (clipping && ref >= 90) {
                    f = Math.min(f, 0.7);
                }
                f = Stats.clamp(f, 1 / cfg.maxGainFactorPerRun, cfg.maxGainFactorPerRun);
                double g = snap(gains[c] * f);
                if (gainOptions != null && Math.abs(g - gains[c]) < 1e-9 && Math.abs(f - 1) > 0.02) {
                    // the option grid is coarser than the wanted change: take the next option in that direction
                    g = neighbourOption(gains[c], f > 1);
                }
                if (Math.abs(g - gains[c]) < 1e-9) {
                    continue;
                }
                nextGains[c] = g;
                gainMoved = true;
                factorSum += g / gains[c];
                factorN++;
                String why = ref < 3 ? "barely any signal" : out ? String.format(Locale.US, "noise p95 %.0f %% is %s the %.0f..%.0f %% band", ref, ref < lo ? "below" : "above", lo, hi)
                        : String.format(Locale.US, "noise p95 %.0f %% is more than %.0f %% below the loudest cylinder", ref, cfg.cylinderImbalancePct);
                r.changes.add(String.format(Locale.US, "%s: %s -> gain %s -> %s", n > 1 ? "Cylinder " + (c + 1) : "Knock gain", why, gainText(gains[c]), gainText(g)));
            }
            if (gainMoved) {
                // the levels scale with the gain: the thresholds follow so the protection stays what it was
                double f = factorN == 0 ? 1 : factorSum / factorN;
                for (int i = 0; i < nextThr.length; i++) {
                    nextThr[i] = Stats.round(Stats.clamp(thresholds[i] * f, cfg.minThresholdPct, cfg.maxThresholdPct), 1);
                }
                r.changes.add(String.format(Locale.US, "Thresholds scaled by %.2f with the gains; they are set from the noise on the next survey", f));
            }
        }
        if (gainMoved) {
            goodStreak = 0;
            phase = Phase.SURVEY;
            r.nextPlan = new Plan(runNumber + 1, Phase.SURVEY, nextThr, nextGains, false, cfg);
            r.nextPlan.notes.add("Survey again with the new gains");
            lastReport = r;
            state = SessionState.REVIEW;
            log.add(r.summary(cfg));
            return r;
        }

        if (phase == Phase.SURVEY) {
            // gains are good: thresholds from the noise, a margin above it
            boolean[] set = new boolean[bins.length];
            for (int i = 0; i < bins.length; i++) {
                BinStats b = bins[i];
                if (!b.measured(cfg.minSamplesPerBin)) {
                    continue;
                }
                double noise = b.p95;
                if (b.cylP95 != null) {
                    for (double v : b.cylP95) {
                        if (Stats.finite(v)) {
                            noise = Math.max(noise, v);
                        }
                    }
                }
                double t = Math.max(noise * (1 + cfg.marginPct / 100.0), b.max + 3);
                nextThr[i] = Stats.clamp(t, cfg.minThresholdPct, cfg.maxThresholdPct);
                set[i] = true;
            }
            // unmeasured bins take the nearest measured threshold
            int unmeasured = 0;
            for (int i = 0; i < bins.length; i++) {
                if (set[i]) {
                    continue;
                }
                unmeasured++;
                int left = -1;
                int right = -1;
                for (int k = i - 1; k >= 0; k--) {
                    if (set[k]) { left = k; break; }
                }
                for (int k = i + 1; k < bins.length; k++) {
                    if (set[k]) { right = k; break; }
                }
                if (left >= 0 && right >= 0) {
                    double w = (rpm.bin(i) - rpm.bin(left)) / (rpm.bin(right) - rpm.bin(left));
                    nextThr[i] = Stats.lerp(nextThr[left], nextThr[right], w);
                } else {
                    nextThr[i] = nextThr[left >= 0 ? left : right];
                }
            }
            // no bin far below its neighbours: the ECU interpolates between bins
            double[] smooth = nextThr.clone();
            for (int i = 1; i < bins.length - 1; i++) {
                smooth[i] = Math.max(nextThr[i], 0.9 * 0.5 * (nextThr[i - 1] + nextThr[i + 1]));
            }
            for (int i = 0; i < bins.length; i++) {
                nextThr[i] = Stats.round(Stats.clamp(smooth[i], cfg.minThresholdPct, cfg.maxThresholdPct), 1);
                if (Math.abs(nextThr[i] - thresholds[i]) > 0.05) {
                    r.changes.add(String.format(Locale.US, "%.0f rpm: %s -> threshold %.1f -> %.1f %%", rpm.bin(i),
                            set[i] ? String.format(Locale.US, "noise p95 %.0f %% (max %.0f)", bins[i].p95, bins[i].max) : "not measured, from the neighbours",
                            thresholds[i], nextThr[i]));
                }
            }
            if (unmeasured > 0) {
                r.messages.add(String.format(Locale.US, "%d bin(s) had fewer than %d samples: their thresholds follow the measured neighbours. Pull through more of the RPM range to measure them.",
                        unmeasured, cfg.minSamplesPerBin));
            }
            phase = Phase.VERIFY;
            goodStreak = 0;
            Arrays.fill(raised, false);
            r.nextPlan = new Plan(runNumber + 1, Phase.VERIFY, nextThr, gains, false, cfg);
            r.nextPlan.notes.add("Check the new thresholds on more pulls");
            lastReport = r;
            state = SessionState.REVIEW;
            log.add(r.summary(cfg));
            return r;
        }

        // ---- verify: raise what false-triggers, tighten what is far above the noise ----
        boolean allGood = true;
        for (int i = 0; i < bins.length; i++) {
            BinStats b = bins[i];
            if (!b.measured(cfg.minSamplesPerBin)) {
                continue;
            }
            // one stray sample per bin is tolerated: the ECU wants a couple of knock events before it acts
            int allowed = cfg.maxFalsePct <= 0 ? 0 : Math.max(1, (int) Math.floor(b.n * cfg.maxFalsePct / 100.0));
            double noise = b.p95;
            if (b.cylP95 != null) {
                for (double v : b.cylP95) {
                    if (Stats.finite(v)) {
                        noise = Math.max(noise, v);
                    }
                }
            }
            double want = noise * (1 + cfg.marginPct / 100.0);
            if (b.knockSuspect() && b.exceed <= allowed) {
                // the ECU firing here is what it is for: the threshold stays, the driver fixes the map
                allGood = false;
                if (b.retards > 0) {
                    r.changes.add(String.format(Locale.US, "%.0f rpm: knock retard fired %d time(s) on %d spike(s) - looks like real knock, threshold %.1f kept. Fix the timing map, then repeat.",
                            b.rpm, b.retards, b.spikes, thresholds[i]));
                }
                continue;
            }
            if (b.exceed > allowed || b.retards > 0) {
                double t = Math.max(thresholds[i] * (1 + cfg.raiseOnFalsePct / 100.0), thresholds[i] + 3);
                t = Math.max(t, b.max + 3);
                t = Stats.round(Stats.clamp(t, cfg.minThresholdPct, cfg.maxThresholdPct), 1);
                if (t > thresholds[i] + 1e-9) {
                    nextThr[i] = t;
                    raised[i] = true;
                    allGood = false;
                    r.changes.add(String.format(Locale.US, "%.0f rpm: %d sample(s) over the threshold, knock retard fired %d time(s) -> threshold %.1f -> %.1f %%",
                            b.rpm, b.exceed, b.retards, thresholds[i], t));
                } else {
                    r.messages.add(String.format(Locale.US, "%.0f rpm: still %d sample(s) over the threshold at the %.0f %% ceiling - lower the gain or check for real knock",
                            b.rpm, b.exceed, cfg.maxThresholdPct));
                }
            } else if (!raised[i] && b.exceed == 0 && thresholds[i] > want * 1.5 && thresholds[i] > b.max + 3) {
                // the survey overestimated this bin (a spiky pull): back to the margin above today's noise
                double t = Stats.round(Stats.clamp(Math.max(want, b.max + 3), cfg.minThresholdPct, cfg.maxThresholdPct), 1);
                if (t < thresholds[i] - 1e-9) {
                    nextThr[i] = t;
                    allGood = false;
                    r.changes.add(String.format(Locale.US, "%.0f rpm: threshold %.1f is far above the noise (p95 %.0f) -> %.1f %%", b.rpm, thresholds[i], b.p95, t));
                }
            }
        }
        goodStreak = allGood ? goodStreak + 1 : 0;
        r.converged = allGood;
        if (goodStreak >= cfg.runsRequired) {
            r.done = true;
            r.nextPlan = new Plan(runNumber + 1, Phase.VERIFY, nextThr, gains, true, cfg);
            r.nextPlan.notes.add("No false triggers in " + goodStreak + " runs in a row");
        } else {
            r.nextPlan = new Plan(runNumber + 1, Phase.VERIFY, nextThr, gains, false, cfg);
            r.nextPlan.notes.add(allGood ? "Good run " + goodStreak + " of " + cfg.runsRequired : "Check the raised thresholds again");
        }
        lastReport = r;
        state = SessionState.REVIEW;
        log.add(r.summary(cfg));
        return r;
    }

    private double neighbourOption(double current, boolean up) {
        int idx = 0;
        for (int i = 0; i < gainOptions.length; i++) {
            if (Math.abs(gainOptions[i] - current) < Math.abs(gainOptions[idx] - current)) {
                idx = i;
            }
        }
        int j = up ? Math.min(gainOptions.length - 1, idx + 1) : Math.max(0, idx - 1);
        return gainOptions[j];
    }

    public Plan commit(Report report) {
        if (state != SessionState.REVIEW || report != lastReport) {
            throw new IllegalStateException("Nothing to commit");
        }
        plan = report.nextPlan;
        thresholds = plan.thresholds.clone();
        gains = plan.gains.clone();
        phase = plan.phase;
        state = report.done ? SessionState.DONE : SessionState.READY;
        return plan;
    }

    public Plan repeatRun() {
        if (state != SessionState.REVIEW) {
            throw new IllegalStateException("Nothing to repeat");
        }
        plan = new Plan(runNumber + 1, phase, thresholds, gains, false, cfg);
        plan.notes.add("Repeat of the previous run");
        state = SessionState.READY;
        return plan;
    }
}
