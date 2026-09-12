package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * What the plugin has learnt about the physical relationship between wastegate solenoid duty and
 * steady-state boost, per RPM column of the bias table. Every steady WOT sample (open- or
 * closed-loop) is an observation. Observations are clustered by duty, made monotonic and
 * interpolated to answer "which duty gives me X kPa at this RPM?".
 */
public final class PlantModel {

    /** One observation: this duty produced this boost. */
    public static final class Obs {
        public final double duty;
        public final double boost;
        public double weight;

        Obs(double duty, double boost, double weight) {
            this.duty = duty;
            this.boost = boost;
            this.weight = weight;
        }
    }

    /** A point of the clustered, monotonic duty → boost curve of one column. */
    public static final class Pt {
        public final double duty;
        public final double boost;
        public final double weight;

        Pt(double duty, double boost, double weight) {
            this.duty = duty;
            this.boost = boost;
            this.weight = weight;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "(%.1f%% -> %.1f kPa, w=%.1f)", duty, boost, weight);
        }
    }

    private static final double MIN_RELIABLE_SLOPE = 0.25; // kPa per % duty
    private static final double SATURATION_SLOPE = 0.3;

    private final Axis rpmAxis;
    private final List<List<Obs>> columns = new ArrayList<List<Obs>>();
    private final double dutyBinPct;
    private final double defaultGain;

    public PlantModel(Axis rpmAxis, double dutyBinPct, double defaultGainKpaPerPct) {
        this.rpmAxis = rpmAxis;
        this.dutyBinPct = Math.max(0.5, dutyBinPct);
        this.defaultGain = defaultGainKpaPerPct;
        for (int i = 0; i < rpmAxis.size(); i++) {
            columns.add(new ArrayList<Obs>());
        }
    }

    public Axis rpmAxis() {
        return rpmAxis;
    }

    public double defaultGain() {
        return defaultGain;
    }

    /** Adds an observation, splitting its weight between the two neighbouring RPM columns. */
    public void addObservation(double rpm, double duty, double boost, double weight) {
        if (weight <= 0 || !Stats.finite(duty) || !Stats.finite(boost)) {
            return;
        }
        Axis.Pos p = rpmAxis.locate(rpm);
        if (p.i0 == p.i1) {
            columns.get(p.i0).add(new Obs(duty, boost, weight));
        } else {
            if (p.w0() > 0.02) {
                columns.get(p.i0).add(new Obs(duty, boost, weight * p.w0()));
            }
            if (p.w1 > 0.02) {
                columns.get(p.i1).add(new Obs(duty, boost, weight * p.w1));
            }
        }
    }

    /** Multiplies every stored weight by the factor and drops negligible observations. */
    public void decay(double factor) {
        for (int c = 0; c < columns.size(); c++) {
            decayColumn(c, factor);
        }
    }

    /** Columns an observation at this RPM would contribute to. */
    public List<Integer> columnsFor(double rpm) {
        List<Integer> out = new ArrayList<Integer>();
        Axis.Pos p = rpmAxis.locate(rpm);
        if (p.i0 == p.i1 || p.w0() > 0.02) {
            out.add(p.i0);
        }
        if (p.i0 != p.i1 && p.w1 > 0.02) {
            out.add(p.i1);
        }
        return out;
    }

    /** Ages one column only; columns that get no fresh data keep what they know. */
    public void decayColumn(int c, double factor) {
        List<Obs> col = columns.get(c);
        {
            List<Obs> keep = new ArrayList<Obs>();
            for (Obs o : col) {
                o.weight *= factor;
                if (o.weight > 1e-3) {
                    keep.add(o);
                }
            }
            col.clear();
            col.addAll(keep);
        }
    }

    public boolean hasData(int col) {
        return !columns.get(col).isEmpty();
    }

    public int observationCount(int col) {
        return columns.get(col).size();
    }

    public double totalWeight(int col) {
        double w = 0;
        for (Obs o : columns.get(col)) {
            w += o.weight;
        }
        return w;
    }

    /** Clustered and isotonic (non-decreasing boost with duty) curve of a column. */
    public List<Pt> curve(int col) {
        List<Obs> obs = columns.get(col);
        if (obs.isEmpty()) {
            return Collections.emptyList();
        }
        // cluster by duty bin
        List<Obs> sorted = new ArrayList<Obs>(obs);
        Collections.sort(sorted, new Comparator<Obs>() {
            @Override
            public int compare(Obs a, Obs b) {
                return Double.compare(a.duty, b.duty);
            }
        });
        List<double[]> bins = new ArrayList<double[]>(); // {sumWDuty, sumWBoost, sumW, binStart}
        for (Obs o : sorted) {
            double binStart = Math.floor(o.duty / dutyBinPct) * dutyBinPct;
            double[] last = bins.isEmpty() ? null : bins.get(bins.size() - 1);
            if (last == null || last[3] != binStart) {
                bins.add(new double[]{o.duty * o.weight, o.boost * o.weight, o.weight, binStart});
            } else {
                last[0] += o.duty * o.weight;
                last[1] += o.boost * o.weight;
                last[2] += o.weight;
            }
        }
        double[] d = new double[bins.size()];
        double[] b = new double[bins.size()];
        double[] w = new double[bins.size()];
        for (int i = 0; i < bins.size(); i++) {
            double[] bin = bins.get(i);
            d[i] = bin[0] / bin[2];
            b[i] = bin[1] / bin[2];
            w[i] = bin[2];
        }
        isotonic(b, w);
        List<Pt> pts = new ArrayList<Pt>();
        for (int i = 0; i < d.length; i++) {
            pts.add(new Pt(d[i], b[i], w[i]));
        }
        return pts;
    }

    /** Weighted pool-adjacent-violators: makes y non-decreasing in place. */
    static void isotonic(double[] y, double[] w) {
        int n = y.length;
        double[] val = new double[n];
        double[] wt = new double[n];
        int[] len = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            val[m] = y[i];
            wt[m] = w[i];
            len[m] = 1;
            m++;
            while (m > 1 && val[m - 2] > val[m - 1]) {
                double tw = wt[m - 2] + wt[m - 1];
                val[m - 2] = (val[m - 2] * wt[m - 2] + val[m - 1] * wt[m - 1]) / tw;
                wt[m - 2] = tw;
                len[m - 2] += len[m - 1];
                m--;
            }
        }
        int k = 0;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < len[i]; j++) {
                y[k++] = val[i];
            }
        }
    }

    /**
     * Duty expected to produce {@code targetKpa} in the column, or NONE when the column is empty.
     * Returns the <em>lowest</em> duty whose predicted boost reaches the target: on a flat part of
     * the curve (turbo maxed out, valve saturated) more duty buys nothing, so the knee is used.
     * Extrapolation above the observed range is capped at {@code maxExtrapolationPct}.
     */
    public DutyEstimate dutyFor(int col, double targetKpa, double minDuty, double maxDuty, double maxExtrapolationPct) {
        List<Pt> pts = curve(col);
        if (pts.isEmpty()) {
            return DutyEstimate.none();
        }
        final double tol = 1.0;
        Pt first = pts.get(0);
        Pt last = pts.get(pts.size() - 1);
        double duty;
        DutyEstimate.Quality q;
        boolean saturated = false;
        if (pts.size() == 1) {
            duty = first.duty + (targetKpa - first.boost) / defaultGain;
            q = Math.abs(targetKpa - first.boost) <= 2 ? DutyEstimate.Quality.MEASURED : DutyEstimate.Quality.EXTRAPOLATED;
        } else if (targetKpa < first.boost - tol) {
            Pt second = pts.get(1);
            double slope = slope(first, second);
            if (slope < MIN_RELIABLE_SLOPE) {
                slope = defaultGain;
            }
            duty = first.duty - (first.boost - targetKpa) / slope;
            q = DutyEstimate.Quality.EXTRAPOLATED;
        } else if (targetKpa > last.boost + tol) {
            // start of the flat top, if any
            int knee = pts.size() - 1;
            while (knee > 0 && pts.get(knee - 1).boost >= last.boost - tol) {
                knee--;
            }
            Pt kneePt = pts.get(knee);
            double slope = knee > 0 ? slope(pts.get(knee - 1), kneePt) : 0;
            if (knee < pts.size() - 1 || slope < SATURATION_SLOPE) {
                saturated = true;
                duty = kneePt.duty;
            } else {
                duty = last.duty + (targetKpa - last.boost) / slope;
            }
            q = DutyEstimate.Quality.EXTRAPOLATED;
        } else {
            int i = 0;
            while (i < pts.size() && pts.get(i).boost < targetKpa - tol) {
                i++;
            }
            if (i == 0) {
                duty = first.duty;
                q = DutyEstimate.Quality.MEASURED;
            } else {
                Pt a = pts.get(i - 1);
                Pt b = pts.get(i);
                double slope = slope(a, b);
                if (slope >= SATURATION_SLOPE) {
                    double span = b.boost - a.boost;
                    double t = span <= 1e-9 ? 1 : Stats.clamp((targetKpa - a.boost) / span, 0, 1);
                    duty = Stats.lerp(a.duty, b.duty, t);
                } else {
                    duty = b.duty; // flat step: the first duty that reaches the target
                }
                q = Math.abs(targetKpa - b.boost) <= 2 || Math.abs(targetKpa - a.boost) <= 2
                        ? DutyEstimate.Quality.MEASURED : DutyEstimate.Quality.INTERPOLATED;
            }
        }
        duty = Math.min(duty, last.duty + maxExtrapolationPct);
        duty = Stats.clamp(duty, minDuty, maxDuty);
        return new DutyEstimate(duty, q, saturated);
    }

    /** Boost expected at {@code duty} in a column; NaN without data. Extrapolates conservatively (predicts more boost). */
    public double predictBoost(int col, double duty) {
        List<Pt> pts = curve(col);
        if (pts.isEmpty()) {
            return Double.NaN;
        }
        Pt first = pts.get(0);
        Pt last = pts.get(pts.size() - 1);
        if (pts.size() == 1) {
            return first.boost + (duty - first.duty) * defaultGain;
        }
        if (duty <= first.duty) {
            double slope = Math.max(slope(first, pts.get(1)), 0);
            return first.boost - (first.duty - duty) * slope;
        }
        if (duty >= last.duty) {
            double slope = Math.max(slope(pts.get(pts.size() - 2), last), defaultGain);
            return last.boost + (duty - last.duty) * slope;
        }
        for (int i = 1; i < pts.size(); i++) {
            Pt a = pts.get(i - 1);
            Pt b = pts.get(i);
            if (duty >= a.duty && duty <= b.duty) {
                double span = b.duty - a.duty;
                double t = span <= 1e-9 ? 0 : (duty - a.duty) / span;
                return Stats.lerp(a.boost, b.boost, t);
            }
        }
        return last.boost;
    }

    /** Highest predicted boost over all columns that have data; NaN when the model is empty. */
    public double predictPeakBoost(double duty) {
        double m = Double.NaN;
        for (int c = 0; c < rpmAxis.size(); c++) {
            double b = predictBoost(c, duty);
            if (Stats.finite(b)) {
                m = Double.isNaN(m) ? b : Math.max(m, b);
            }
        }
        return m;
    }

    /** Global plant gain (kPa per % duty) from all columns with a usable slope; default gain otherwise. */
    public double gainKpaPerPct() {
        double sw = 0;
        double ss = 0;
        for (int c = 0; c < rpmAxis.size(); c++) {
            List<Pt> pts = curve(c);
            for (int i = 1; i < pts.size(); i++) {
                Pt a = pts.get(i - 1);
                Pt b = pts.get(i);
                double dd = b.duty - a.duty;
                if (dd < 3) {
                    continue;
                }
                double s = (b.boost - a.boost) / dd;
                if (s <= 0.05) {
                    continue;
                }
                double w = Math.min(a.weight, b.weight);
                sw += w;
                ss += s * w;
            }
        }
        if (sw <= 0) {
            return defaultGain;
        }
        return Stats.clamp(ss / sw, 0.3, 6.0);
    }

    public boolean isEmpty() {
        for (int c = 0; c < rpmAxis.size(); c++) {
            if (hasData(c)) {
                return false;
            }
        }
        return true;
    }

    /** Highest boost ever observed (NaN when empty). */
    public double maxObservedBoost() {
        double m = Double.NaN;
        for (List<Obs> col : columns) {
            for (Obs o : col) {
                m = Double.isNaN(m) ? o.boost : Math.max(m, o.boost);
            }
        }
        return m;
    }

    private static double slope(Pt a, Pt b) {
        double dd = b.duty - a.duty;
        return dd <= 1e-9 ? 0 : (b.boost - a.boost) / dd;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < rpmAxis.size(); c++) {
            sb.append(String.format(java.util.Locale.US, "%6.0f rpm: ", rpmAxis.bin(c)));
            List<Pt> pts = curve(c);
            if (pts.isEmpty()) {
                sb.append("no data");
            } else {
                for (Pt p : pts) {
                    sb.append(String.format(java.util.Locale.US, " %.0f%%->%.0fkPa", p.duty, p.boost));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
