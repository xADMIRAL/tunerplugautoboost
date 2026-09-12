package io.github.xadmiral.boostautotune.core.session;

import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.LoadSource;
import io.github.xadmiral.boostautotune.core.model.PidGains;

/**
 * Everything the session knows or wants about the ECU's boost controller. The plugin fills it from
 * the ECU and writes it back after a commit. Optional tables are null when the firmware lacks them.
 */
public final class EcuState {
    public Grid targetTable;
    public LoadSource targetLoadSource = LoadSource.TPS;
    /** Bias / feed-forward duty table, X = RPM, Y = target boost. Null when the ECU has none. */
    public Grid biasTable;
    /** Open-loop duty table, X = RPM, Y = load. Null when the ECU has none or must not be touched. */
    public Grid openLoopTable;
    public LoadSource openLoopLoadSource = LoadSource.TPS;
    public PidGains pid = new PidGains(0, 0, 0);
    public double minDuty = 0;
    public double maxDuty = 100;
    public boolean closedLoop;
    /** Largest value accepted by the target table. */
    public double targetTableMax = 400;

    public EcuState copy() {
        EcuState c = new EcuState();
        c.targetTable = targetTable == null ? null : targetTable.copy();
        c.targetLoadSource = targetLoadSource;
        c.biasTable = biasTable == null ? null : biasTable.copy();
        c.openLoopTable = openLoopTable == null ? null : openLoopTable.copy();
        c.openLoopLoadSource = openLoopLoadSource;
        c.pid = pid;
        c.minDuty = minDuty;
        c.maxDuty = maxDuty;
        c.closedLoop = closedLoop;
        c.targetTableMax = targetTableMax;
        return c;
    }

    public boolean hasBiasTable() {
        return biasTable != null;
    }

    public boolean hasOpenLoopTable() {
        return openLoopTable != null;
    }
}
