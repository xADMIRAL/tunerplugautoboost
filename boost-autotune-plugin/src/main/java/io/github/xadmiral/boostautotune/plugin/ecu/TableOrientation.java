package io.github.xadmiral.boostautotune.plugin.ecu;

/** How a {@code double[][]} from TunerStudio maps onto table axes. */
public enum TableOrientation {
    /** Decide from the parameter shape; falls back to ROWS_ARE_Y for square tables. */
    AUTO("Auto (rows = Y axis for square tables)"),
    ROWS_ARE_Y("Rows = Y axis (load), columns = X axis (RPM)"),
    ROWS_ARE_X("Rows = X axis (RPM), columns = Y axis (load)");

    private final String label;

    TableOrientation(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
