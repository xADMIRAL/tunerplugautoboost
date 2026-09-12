package io.github.xadmiral.boostautotune.core.util;

import java.util.Arrays;
import java.util.List;

/** Small numeric helpers (no external dependencies). */
public final class Stats {
    private Stats() {
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double mean(List<Double> xs) {
        if (xs.isEmpty()) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : xs) {
            s += x;
        }
        return s / xs.size();
    }

    public static double median(List<Double> xs) {
        if (xs.isEmpty()) {
            return Double.NaN;
        }
        double[] a = new double[xs.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = xs.get(i);
        }
        Arrays.sort(a);
        int n = a.length;
        return n % 2 == 1 ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }

    public static double max(List<Double> xs) {
        double m = Double.NEGATIVE_INFINITY;
        for (double x : xs) {
            m = Math.max(m, x);
        }
        return m;
    }

    public static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
