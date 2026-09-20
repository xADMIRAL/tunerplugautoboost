package io.github.xadmiral.boostautotune.plugin.assistant;

import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Edits the assistant proposed: each is a parameter plus either a value text (scalar, option,
 * fill value, or bin list, as {@link EcuAdapter#writeAny}) or a list of table cells. The user
 * applies or rejects them; the value before the first apply is kept so everything can be put
 * back with one click. Never burns: that stays a TunerStudio button.
 */
public final class PendingChanges {
    public static final String PENDING = "pending";
    public static final String APPLIED = "applied";
    public static final String REJECTED = "rejected";
    public static final String FAILED = "failed";
    public static final String RESTORED = "restored";

    public static final class Cell {
        public final int row;
        public final int col;
        public final double value;

        public Cell(int row, int col, double value) {
            this.row = row;
            this.col = col;
            this.value = value;
        }
    }

    public static final class Change {
        public final int id;
        public final String param;
        /** Value text for {@link EcuAdapter#writeAny}; null for a cell edit. */
        public final String value;
        /** Cells of a 2-D table edit; null for a value edit. */
        public final List<Cell> cells;
        public final String reason;
        public String status = PENDING;
        public String error = "";
        /** Value before this change was applied (value text or raw table), for undo. */
        String previousValue;
        double[][] previousTable;

        Change(int id, String param, String value, List<Cell> cells, String reason) {
            this.id = id;
            this.param = param;
            this.value = value;
            this.cells = cells;
            this.reason = reason == null ? "" : reason;
        }

        public String describe() {
            if (cells != null) {
                StringBuilder sb = new StringBuilder();
                for (Cell c : cells) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append('[').append(c.row).append(',').append(c.col).append("]=").append(fmt(c.value));
                }
                return param + " cells " + sb;
            }
            return param + " = " + value;
        }
    }

    private final List<Change> changes = new ArrayList<Change>();
    private int nextId = 1;

    public synchronized Change propose(String param, String value, String reason) {
        Change c = new Change(nextId++, param, value, null, reason);
        changes.add(c);
        return c;
    }

    public synchronized Change proposeCells(String param, List<Cell> cells, String reason) {
        Change c = new Change(nextId++, param, null, new ArrayList<Cell>(cells), reason);
        changes.add(c);
        return c;
    }

    public synchronized List<Change> list() {
        return new ArrayList<Change>(changes);
    }

    public synchronized Change get(int id) {
        for (Change c : changes) {
            if (c.id == id) {
                return c;
            }
        }
        return null;
    }

    public synchronized int pendingCount() {
        int n = 0;
        for (Change c : changes) {
            if (PENDING.equals(c.status)) {
                n++;
            }
        }
        return n;
    }

    public synchronized boolean hasApplied() {
        for (Change c : changes) {
            if (APPLIED.equals(c.status)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void reject(int id) {
        Change c = get(id);
        if (c != null && PENDING.equals(c.status)) {
            c.status = REJECTED;
        }
    }

    public synchronized void clearFinished() {
        List<Change> keep = new ArrayList<Change>();
        for (Change c : changes) {
            if (PENDING.equals(c.status) || APPLIED.equals(c.status)) {
                keep.add(c);
            }
        }
        changes.clear();
        changes.addAll(keep);
    }

    /** Writes one pending change to the ECU (RAM). Returns the change with its new status. */
    public synchronized Change apply(EcuAdapter a, int id) {
        Change c = get(id);
        if (c == null) {
            return null;
        }
        if (!PENDING.equals(c.status)) {
            return c;
        }
        try {
            if (!a.hasParameter(c.param)) {
                throw new EcuException("parameter '" + c.param + "' is not in this INI");
            }
            if (c.cells != null) {
                EcuPort port = a.port();
                String cfg = a.config();
                double[][] raw = port.readArray2D(cfg, c.param);
                c.previousTable = copy(raw);
                double[][] edited = copy(raw);
                EcuPort.ParamInfo info = port.parameterInfo(cfg, c.param);
                for (Cell cell : c.cells) {
                    if (cell.row < 0 || cell.row >= edited.length || cell.col < 0 || cell.col >= edited[cell.row].length) {
                        throw new EcuException("cell [" + cell.row + "," + cell.col + "] is outside " + edited.length + "x" + edited[0].length);
                    }
                    if (info != null && info.max > info.min && (cell.value < info.min || cell.value > info.max)) {
                        throw new EcuException("value " + fmt(cell.value) + " is outside " + fmt(info.min) + ".." + fmt(info.max));
                    }
                    edited[cell.row][cell.col] = cell.value;
                }
                port.writeArray2D(cfg, c.param, edited);
            } else {
                c.previousValue = a.readAny(c.param);
                a.writeAny(c.param, c.value);
            }
            c.status = APPLIED;
            c.error = "";
        } catch (EcuException e) {
            c.status = FAILED;
            c.error = e.getMessage();
        } catch (RuntimeException e) {
            c.status = FAILED;
            c.error = String.valueOf(e.getMessage());
        }
        return c;
    }

    /** Applies every pending change; the number written. */
    public synchronized int applyAll(EcuAdapter a) {
        int n = 0;
        for (Change c : list()) {
            if (PENDING.equals(c.status) && APPLIED.equals(apply(a, c.id).status)) {
                n++;
            }
        }
        return n;
    }

    /** Puts back everything applied, newest first; the problems, empty when all went back. */
    public synchronized List<String> restoreAll(EcuAdapter a) {
        List<String> problems = new ArrayList<String>();
        List<Change> applied = new ArrayList<Change>();
        for (Change c : changes) {
            if (APPLIED.equals(c.status)) {
                applied.add(c);
            }
        }
        Collections.reverse(applied);
        for (Change c : applied) {
            try {
                if (c.previousTable != null) {
                    a.port().writeArray2D(a.config(), c.param, copy(c.previousTable));
                } else if (c.previousValue != null) {
                    a.writeAny(c.param, c.previousValue);
                }
                c.status = RESTORED;
            } catch (EcuException e) {
                problems.add(c.param + ": " + e.getMessage());
            }
        }
        return problems;
    }

    static double[][] copy(double[][] a) {
        double[][] c = new double[a.length][];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i].clone();
        }
        return c;
    }

    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e12) {
            return Long.toString((long) v);
        }
        return String.format(Locale.US, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
