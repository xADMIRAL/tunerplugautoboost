package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.List;
import java.util.Locale;

/**
 * Road-going torque proxy: in a fixed gear the engine's acceleration (dRPM/dt) at a given RPM is
 * proportional to torque. Comparing candidates at the same RPM and gear cancels the unknowns
 * (inertia, drag, road), as long as the pulls are made on comparable road and conditions.
 */
public final class TorqueProxy {
    private TorqueProxy() {
    }

    /** Per-RPM-bin acceleration statistics. */
    public static final class AccelProfile {
        public final Axis rpmAxis;
        public final int[] n;
        public final double[] sum;
        public final double[] sumSq;

        public AccelProfile(Axis rpmAxis) {
            this.rpmAxis = rpmAxis;
            this.n = new int[rpmAxis.size()];
            this.sum = new double[rpmAxis.size()];
            this.sumSq = new double[rpmAxis.size()];
        }

        public void add(int bin, double accel) {
            n[bin]++;
            sum[bin] += accel;
            sumSq[bin] += accel * accel;
        }

        public void addAll(AccelProfile o) {
            for (int i = 0; i < n.length; i++) {
                n[i] += o.n[i];
                sum[i] += o.sum[i];
                sumSq[i] += o.sumSq[i];
            }
        }

        public double mean(int bin) {
            return n[bin] == 0 ? Double.NaN : sum[bin] / n[bin];
        }

        public double std(int bin) {
            if (n[bin] < 2) {
                return Double.NaN;
            }
            double m = mean(bin);
            double v = sumSq[bin] / n[bin] - m * m;
            return Math.sqrt(Math.max(0, v));
        }

        /** Standard error of the mean. */
        public double sem(int bin) {
            double sd = std(bin);
            return Double.isNaN(sd) ? Double.NaN : sd / Math.sqrt(n[bin]);
        }

        public int totalSamples() {
            int t = 0;
            for (int v : n) {
                t += v;
            }
            return t;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n.length; i++) {
                if (n[i] == 0) {
                    continue;
                }
                sb.append(String.format(Locale.US, "%.0f rpm: %.0f rpm/s (n=%d, sd %.0f)  ", rpmAxis.bin(i), mean(i), n[i], std(i)));
            }
            return sb.toString();
        }
    }

    /**
     * dRPM/dt for every sample by linear regression over a centred window. Returns NaN where the
     * window holds fewer than three samples.
     */
    public static double[] accelSeries(List<Sample> s, double windowSec) {
        int n = s.size();
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            double t0 = s.get(k).timeSec;
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            int c = 0;
            for (int j = Math.max(0, k - 50); j < Math.min(n, k + 50); j++) {
                double dt = s.get(j).timeSec - t0;
                if (Math.abs(dt) > windowSec / 2) {
                    continue;
                }
                sx += dt;
                sy += s.get(j).rpm;
                sxx += dt * dt;
                sxy += dt * s.get(j).rpm;
                c++;
            }
            double den = c * sxx - sx * sx;
            out[k] = c < 3 || Math.abs(den) < 1e-12 ? Double.NaN : (c * sxy - sx * sy) / den;
        }
        return out;
    }

    /**
     * Acceleration profile of one pull.
     * @param settleSec samples closer than this to the throttle opening are ignored
     * @param gear     0 = any gear, otherwise the pull must have been made in this gear
     */
    public static AccelProfile profile(Axis rpmAxis, Pull pull, double windowSec, double settleSec,
                                       double wotLoad, double minRpm, double maxRpm, int gear) {
        AccelProfile p = new AccelProfile(rpmAxis);
        if (gear > 0 && pull.gear() != gear) {
            return p;
        }
        List<Sample> ss = pull.samples();
        double[] acc = accelSeries(ss, windowSec);
        double start = pull.startTime();
        for (int k = 0; k < ss.size(); k++) {
            Sample x = ss.get(k);
            if (Double.isNaN(acc[k]) || x.timeSec - start < settleSec || x.tps < wotLoad
                    || x.rpm < minRpm || x.rpm > maxRpm || x.boostCut) {
                continue;
            }
            // only while the engine is actually accelerating: a lift or a shift is not a data point
            if (acc[k] <= 0) {
                continue;
            }
            p.add(rpmAxis.nearest(x.rpm), acc[k]);
        }
        return p;
    }

    /** Relative gain of b over a in percent, NaN when either is missing. */
    public static double gainPct(double a, double b) {
        if (!Stats.finite(a) || !Stats.finite(b) || a <= 0) {
            return Double.NaN;
        }
        return (b - a) / a * 100.0;
    }
}
