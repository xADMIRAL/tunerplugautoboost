package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.model.Grid;

import java.util.ArrayList;
import java.util.List;

/** Outcome of rebuilding the bias (feed-forward duty) table from the plant model. */
public final class BiasBuildResult {
    public final Grid grid;
    public final DutyEstimate.Quality[][] quality; // [y][x]
    public final boolean[][] saturated;            // [y][x]
    public final double maxChange;
    /** Largest change among cells backed by data (measured or interpolated); drives convergence. */
    public final double maxChangeMeasured;
    public final int measuredCells;
    public final int interpolatedCells;
    public final int extrapolatedCells;
    public final int borrowedCells;
    public final int unchangedCells;
    public final List<String> notes = new ArrayList<String>();
    /** Largest cell changes, most significant first: "rpm / target: old -> new (quality)". */
    public final List<CellChange> changes = new ArrayList<CellChange>();

    public static final class CellChange {
        public final int xi;
        public final int yi;
        public final double rpm;
        public final double target;
        public final double oldValue;
        public final double newValue;
        public final DutyEstimate.Quality quality;

        public CellChange(int xi, int yi, double rpm, double target, double oldValue, double newValue, DutyEstimate.Quality quality) {
            this.xi = xi;
            this.yi = yi;
            this.rpm = rpm;
            this.target = target;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.quality = quality;
        }

        public double delta() {
            return newValue - oldValue;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%.0f rpm / %.0f kPa: %.1f -> %.1f (%s)", rpm, target, oldValue, newValue, quality);
        }
    }

    BiasBuildResult(Grid grid, DutyEstimate.Quality[][] quality, boolean[][] saturated, double maxChange,
                    double maxChangeMeasured, int measured, int interpolated, int extrapolated, int borrowed, int unchanged) {
        this.grid = grid;
        this.quality = quality;
        this.saturated = saturated;
        this.maxChange = maxChange;
        this.maxChangeMeasured = maxChangeMeasured;
        this.measuredCells = measured;
        this.interpolatedCells = interpolated;
        this.extrapolatedCells = extrapolated;
        this.borrowedCells = borrowed;
        this.unchangedCells = unchanged;
    }

    public double maxAbsDiffFrom(Grid other) {
        return grid.maxAbsDiff(other);
    }

    public int coveredCells() {
        return measuredCells + interpolatedCells + extrapolatedCells + borrowedCells;
    }

    public String summary() {
        return String.format(java.util.Locale.US,
                "bias table: %d measured, %d interpolated, %d extrapolated, %d borrowed, %d unchanged; max change %.1f%% (%.1f%% in measured cells)",
                measuredCells, interpolatedCells, extrapolatedCells, borrowedCells, unchangedCells, maxChange, maxChangeMeasured);
    }
}
