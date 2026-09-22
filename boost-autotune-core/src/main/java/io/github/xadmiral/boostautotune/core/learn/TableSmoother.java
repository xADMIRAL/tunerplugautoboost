package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.util.Stats;

/**
 * Smooths a lookup table (VE, spark, AFR...) so the ECU's interpolation between cells does not
 * swing on the way through a jagged region. Every cell inside the chosen region moves part of
 * the way ({@link Settings#strength}) towards the weighted average of its neighbours (a 3x3
 * kernel, or a 1x3 one along a single axis); cells outside the region stay put but still act
 * as boundary values. The change per cell is capped, the result clamped to the table's range.
 */
public final class TableSmoother {

    public enum Direction {
        BOTH("both axes"), ALONG_X("along RPM (rows)"), ALONG_Y("along load (columns)");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class Settings {
        /** 0..1: how far a cell moves towards its neighbours' average in one pass. */
        public double strength = 0.5;
        public int passes = 1;
        public Direction direction = Direction.BOTH;
        /** Region on the axes' values (NaN = no limit on that side). */
        public double xMin = Double.NaN;
        public double xMax = Double.NaN;
        public double yMin = Double.NaN;
        public double yMax = Double.NaN;
        /** Largest change of any cell, % of its original value (0 = no cap). */
        public double maxChangePct = 5;
        /** Largest change of any cell in table units (0 = no cap). */
        public double maxChangeAbs = 0;
        /** Table range from the ECU definition (NaN = none). */
        public double minValue = Double.NaN;
        public double maxValue = Double.NaN;
        public int decimals = 1;

        public String validate() {
            if (strength < 0 || strength > 1) {
                return "Strength must be between 0 and 1";
            }
            if (passes < 1 || passes > 20) {
                return "Passes must be between 1 and 20";
            }
            if (Stats.finite(xMin) && Stats.finite(xMax) && xMin > xMax) {
                return "RPM from must not exceed RPM to";
            }
            if (Stats.finite(yMin) && Stats.finite(yMax) && yMin > yMax) {
                return "Load from must not exceed load to";
            }
            return null;
        }
    }

    public static final class Result {
        public final Grid smoothed;
        public final boolean[][] inRegion;
        public int regionCells;
        public int changedCells;
        public int cappedCells;
        public double maxAbsChange;
        public double meanAbsChange;
        public double roughnessBefore;
        public double roughnessAfter;

        Result(Grid smoothed, boolean[][] inRegion) {
            this.smoothed = smoothed;
            this.inRegion = inRegion;
        }

        public String summary() {
            return String.format(java.util.Locale.US,
                    "%d of %d cells changed (max %.2f, mean %.2f%s); roughness %.2f -> %.2f (%.0f %% smoother)",
                    changedCells, regionCells, maxAbsChange, meanAbsChange, cappedCells > 0 ? ", " + cappedCells + " capped" : "",
                    roughnessBefore, roughnessAfter, roughnessBefore > 1e-9 ? 100 * (1 - roughnessAfter / roughnessBefore) : 0.0);
        }
    }

    private TableSmoother() {
    }

    public static boolean[][] region(Grid g, Settings s) {
        Axis x = g.xAxis();
        Axis y = g.yAxis();
        boolean[][] in = new boolean[g.height()][g.width()];
        for (int yi = 0; yi < g.height(); yi++) {
            boolean yOk = (!Stats.finite(s.yMin) || y.bin(yi) >= s.yMin - 1e-9) && (!Stats.finite(s.yMax) || y.bin(yi) <= s.yMax + 1e-9);
            for (int xi = 0; xi < g.width(); xi++) {
                boolean xOk = (!Stats.finite(s.xMin) || x.bin(xi) >= s.xMin - 1e-9) && (!Stats.finite(s.xMax) || x.bin(xi) <= s.xMax + 1e-9);
                in[yi][xi] = xOk && yOk;
            }
        }
        return in;
    }

    /** Mean absolute second difference over the region's cells: 0 for a plane, large for a jagged table. */
    public static double roughness(Grid g, boolean[][] in) {
        double sum = 0;
        int n = 0;
        for (int yi = 0; yi < g.height(); yi++) {
            for (int xi = 0; xi < g.width(); xi++) {
                if (!in[yi][xi]) {
                    continue;
                }
                if (xi > 0 && xi < g.width() - 1) {
                    sum += Math.abs(g.get(xi - 1, yi) - 2 * g.get(xi, yi) + g.get(xi + 1, yi));
                    n++;
                }
                if (yi > 0 && yi < g.height() - 1) {
                    sum += Math.abs(g.get(xi, yi - 1) - 2 * g.get(xi, yi) + g.get(xi, yi + 1));
                    n++;
                }
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double weight(int dx, int dy, Direction d) {
        if (dx == 0 && dy == 0) {
            return 0;
        }
        switch (d) {
            case ALONG_X:
                return dy == 0 ? 1 : 0;
            case ALONG_Y:
                return dx == 0 ? 1 : 0;
            default:
                return dx != 0 && dy != 0 ? 1 : 2;
        }
    }

    /**
     * The neighbour's value, or its linear extrapolation across the edge when it lies outside the
     * table (mirrored about the cell), so edge cells of a plane stay where they are; NaN when
     * neither side exists.
     */
    private static double neighbour(Grid g, int xi, int yi, int dx, int dy) {
        int nx = xi + dx;
        int ny = yi + dy;
        if (nx >= 0 && ny >= 0 && nx < g.width() && ny < g.height()) {
            return g.get(nx, ny);
        }
        int ox = xi - dx;
        int oy = yi - dy;
        if (ox >= 0 && oy >= 0 && ox < g.width() && oy < g.height()) {
            return 2 * g.get(xi, yi) - g.get(ox, oy);
        }
        return Double.NaN;
    }

    public static Result smooth(Grid original, Settings s) {
        String problem = s.validate();
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        boolean[][] in = region(original, s);
        Grid work = original.copy();
        for (int pass = 0; pass < s.passes; pass++) {
            Grid next = work.copy();
            for (int yi = 0; yi < work.height(); yi++) {
                for (int xi = 0; xi < work.width(); xi++) {
                    if (!in[yi][xi]) {
                        continue;
                    }
                    double wsum = 0;
                    double vsum = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            double w = weight(dx, dy, s.direction);
                            if (w == 0) {
                                continue;
                            }
                            double nv = neighbour(work, xi, yi, dx, dy);
                            if (Double.isNaN(nv)) {
                                continue;
                            }
                            wsum += w;
                            vsum += w * nv;
                        }
                    }
                    if (wsum > 0) {
                        double v = work.get(xi, yi);
                        next.set(xi, yi, v + s.strength * (vsum / wsum - v));
                    }
                }
            }
            work = next;
        }
        Result r = new Result(work, in);
        double sum = 0;
        for (int yi = 0; yi < work.height(); yi++) {
            for (int xi = 0; xi < work.width(); xi++) {
                if (!in[yi][xi]) {
                    continue;
                }
                r.regionCells++;
                double o = original.get(xi, yi);
                double v = work.get(xi, yi);
                double cap = Double.POSITIVE_INFINITY;
                if (s.maxChangePct > 0) {
                    cap = Math.abs(o) * s.maxChangePct / 100.0;
                }
                if (s.maxChangeAbs > 0) {
                    cap = Math.min(cap, s.maxChangeAbs);
                }
                if (Math.abs(v - o) > cap) {
                    v = o + Math.signum(v - o) * cap;
                    r.cappedCells++;
                }
                if (Stats.finite(s.minValue)) {
                    v = Math.max(v, s.minValue);
                }
                if (Stats.finite(s.maxValue)) {
                    v = Math.min(v, s.maxValue);
                }
                v = Stats.round(v, s.decimals);
                work.set(xi, yi, v);
                double d = Math.abs(v - o);
                if (d > 1e-9) {
                    r.changedCells++;
                }
                sum += d;
                r.maxAbsChange = Math.max(r.maxAbsChange, d);
            }
        }
        r.meanAbsChange = r.regionCells == 0 ? 0 : sum / r.regionCells;
        r.roughnessBefore = roughness(original, in);
        r.roughnessAfter = roughness(work, in);
        return r;
    }
}
