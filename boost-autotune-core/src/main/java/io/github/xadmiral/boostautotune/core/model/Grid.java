package io.github.xadmiral.boostautotune.core.model;

import java.util.Locale;

/**
 * 2-D lookup table. Values are stored as {@code [yIndex][xIndex]} regardless of how the ECU
 * lays them out; the plugin's ECU adapter is responsible for the translation.
 */
public final class Grid {
    private final Axis x;
    private final Axis y;
    private final double[][] v;

    public Grid(Axis x, Axis y) {
        this.x = x;
        this.y = y;
        this.v = new double[y.size()][x.size()];
    }

    public Grid(Axis x, Axis y, double[][] valuesYX) {
        this(x, y);
        if (valuesYX.length != y.size()) {
            throw new IllegalArgumentException("Row count " + valuesYX.length + " != y size " + y.size());
        }
        for (int yi = 0; yi < y.size(); yi++) {
            if (valuesYX[yi].length != x.size()) {
                throw new IllegalArgumentException("Column count " + valuesYX[yi].length + " != x size " + x.size());
            }
            System.arraycopy(valuesYX[yi], 0, v[yi], 0, x.size());
        }
    }

    public static Grid filled(Axis x, Axis y, double value) {
        Grid g = new Grid(x, y);
        g.fill(value);
        return g;
    }

    public Axis xAxis() {
        return x;
    }

    public Axis yAxis() {
        return y;
    }

    public int width() {
        return x.size();
    }

    public int height() {
        return y.size();
    }

    public double get(int xi, int yi) {
        return v[yi][xi];
    }

    public void set(int xi, int yi, double value) {
        v[yi][xi] = value;
    }

    public void fill(double value) {
        for (double[] row : v) {
            java.util.Arrays.fill(row, value);
        }
    }

    public Grid copy() {
        return new Grid(x, y, v);
    }

    /** Values as {@code [y][x]} (defensive copy). */
    public double[][] valuesYX() {
        double[][] out = new double[height()][];
        for (int yi = 0; yi < height(); yi++) {
            out[yi] = v[yi].clone();
        }
        return out;
    }

    /** Bilinear interpolation at (xv, yv); clamps outside the axes like an ECU does. */
    public double lookup(double xv, double yv) {
        Axis.Pos px = x.locate(xv);
        Axis.Pos py = y.locate(yv);
        double a = v[py.i0][px.i0] * px.w0() + v[py.i0][px.i1] * px.w1;
        double b = v[py.i1][px.i0] * px.w0() + v[py.i1][px.i1] * px.w1;
        return a * py.w0() + b * py.w1;
    }

    /** Largest absolute cell difference against another grid of identical shape. */
    public double maxAbsDiff(Grid other) {
        double m = 0;
        for (int yi = 0; yi < height(); yi++) {
            for (int xi = 0; xi < width(); xi++) {
                m = Math.max(m, Math.abs(v[yi][xi] - other.v[yi][xi]));
            }
        }
        return m;
    }

    public boolean sameShape(Grid other) {
        return other != null && other.width() == width() && other.height() == height();
    }

    public double min() {
        double m = Double.POSITIVE_INFINITY;
        for (double[] row : v) {
            for (double d : row) {
                m = Math.min(m, d);
            }
        }
        return m;
    }

    public double max() {
        double m = Double.NEGATIVE_INFINITY;
        for (double[] row : v) {
            for (double d : row) {
                m = Math.max(m, d);
            }
        }
        return m;
    }

    /** Human readable dump, y descending like a TunerStudio table editor. */
    public String toText(String fmt) {
        StringBuilder sb = new StringBuilder();
        for (int yi = height() - 1; yi >= 0; yi--) {
            sb.append(String.format(Locale.US, "%8.1f |", y.bin(yi)));
            for (int xi = 0; xi < width(); xi++) {
                sb.append(' ').append(String.format(Locale.US, fmt, v[yi][xi]));
            }
            sb.append('\n');
        }
        sb.append("         +");
        for (int xi = 0; xi < width(); xi++) {
            sb.append(String.format(Locale.US, " %7.0f", x.bin(xi)));
        }
        sb.append('\n');
        return sb.toString();
    }

    @Override
    public String toString() {
        return toText("%7.1f");
    }
}
