package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.Grid;

/** Remembers the raw layout of an ECU table so it can be written back exactly as it was read. */
public final class TableLayout {
    public final int rawRows;
    public final int rawCols;
    public final boolean rowsAreY;

    public TableLayout(int rawRows, int rawCols, boolean rowsAreY) {
        this.rawRows = rawRows;
        this.rawCols = rawCols;
        this.rowsAreY = rowsAreY;
    }

    /** Converts raw ECU values to {@code [y][x]}. */
    public double[][] toYX(double[][] raw) {
        if (rowsAreY) {
            return raw;
        }
        double[][] out = new double[rawCols][rawRows];
        for (int r = 0; r < rawRows; r++) {
            for (int c = 0; c < rawCols; c++) {
                out[c][r] = raw[r][c];
            }
        }
        return out;
    }

    /** Converts a grid back to the raw ECU layout. */
    public double[][] fromGrid(Grid g) {
        double[][] yx = g.valuesYX();
        if (rowsAreY) {
            return yx;
        }
        double[][] out = new double[rawRows][rawCols];
        for (int y = 0; y < g.height(); y++) {
            for (int x = 0; x < g.width(); x++) {
                out[x][y] = yx[y][x];
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return rawRows + "x" + rawCols + (rowsAreY ? " rows=Y" : " rows=X");
    }
}
