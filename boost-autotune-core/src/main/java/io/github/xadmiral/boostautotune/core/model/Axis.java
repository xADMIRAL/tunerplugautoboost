package io.github.xadmiral.boostautotune.core.model;

import java.util.Arrays;

/** Monotonic table axis (bins) with interpolation helpers. */
public final class Axis {
    private final double[] bins;

    public Axis(double[] bins) {
        if (bins == null || bins.length == 0) {
            throw new IllegalArgumentException("Axis needs at least one bin");
        }
        this.bins = bins.clone();
    }

    public static Axis of(double... bins) {
        return new Axis(bins);
    }

    public int size() {
        return bins.length;
    }

    public double bin(int i) {
        return bins[i];
    }

    public double[] bins() {
        return bins.clone();
    }

    public double min() {
        return bins[0];
    }

    public double max() {
        return bins[bins.length - 1];
    }

    /** True when bins are strictly increasing (the only layout the learners accept). */
    public boolean isStrictlyIncreasing() {
        for (int i = 1; i < bins.length; i++) {
            if (bins[i] <= bins[i - 1]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Position of a value on the axis: lower index, upper index and the weight of the upper index.
     * Values outside the axis clamp to the end bins.
     */
    public Pos locate(double value) {
        if (bins.length == 1 || value <= bins[0]) {
            return new Pos(0, 0, 0.0);
        }
        int last = bins.length - 1;
        if (value >= bins[last]) {
            return new Pos(last, last, 0.0);
        }
        int hi = 1;
        while (hi < last && bins[hi] < value) {
            hi++;
        }
        int lo = hi - 1;
        double span = bins[hi] - bins[lo];
        double w = span <= 0 ? 0.0 : (value - bins[lo]) / span;
        return new Pos(lo, hi, w);
    }

    /** Index of the bin closest to the value. */
    public int nearest(double value) {
        Pos p = locate(value);
        return p.w1 >= 0.5 ? p.i1 : p.i0;
    }

    @Override
    public String toString() {
        return Arrays.toString(bins);
    }

    /** Interpolation position on an axis. */
    public static final class Pos {
        public final int i0;
        public final int i1;
        /** Weight of i1; weight of i0 is 1 - w1. */
        public final double w1;

        Pos(int i0, int i1, double w1) {
            this.i0 = i0;
            this.i1 = i1;
            this.w1 = w1;
        }

        public double w0() {
            return 1.0 - w1;
        }
    }
}
