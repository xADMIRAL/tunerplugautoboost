package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.model.Grid;

import javax.swing.table.AbstractTableModel;
import java.util.Locale;

/** Shows a {@link Grid} like a TunerStudio table editor: Y descending, X across, axis labels. */
public final class GridTableModel extends AbstractTableModel {
    private Grid grid;
    private Grid reference;
    private String fmt = "%.1f";
    private String yLabel = "Y";

    public void setGrid(Grid g, Grid reference) {
        this.grid = g;
        this.reference = reference;
        fireTableStructureChanged();
    }

    public void setFormat(String fmt) {
        this.fmt = fmt;
    }

    public void setYLabel(String label) {
        this.yLabel = label;
    }

    public Grid grid() {
        return grid;
    }

    @Override
    public int getRowCount() {
        return grid == null ? 0 : grid.height();
    }

    @Override
    public int getColumnCount() {
        return grid == null ? 0 : grid.width() + 1;
    }

    @Override
    public String getColumnName(int col) {
        if (grid == null) {
            return "";
        }
        return col == 0 ? yLabel : String.format(Locale.US, "%.0f", grid.xAxis().bin(col - 1));
    }

    /** Grid Y index of a view row (rows are displayed top = highest Y). */
    public int yIndex(int row) {
        return grid.height() - 1 - row;
    }

    @Override
    public Object getValueAt(int row, int col) {
        if (grid == null) {
            return "";
        }
        int yi = yIndex(row);
        if (col == 0) {
            return String.format(Locale.US, "%.0f", grid.yAxis().bin(yi));
        }
        return String.format(Locale.US, fmt, grid.get(col - 1, yi));
    }

    /** Difference against the reference grid, or 0. */
    public double delta(int row, int col) {
        if (grid == null || reference == null || col == 0 || !reference.sameShape(grid)) {
            return 0;
        }
        int yi = yIndex(row);
        return grid.get(col - 1, yi) - reference.get(col - 1, yi);
    }
}
