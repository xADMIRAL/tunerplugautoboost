package io.github.xadmiral.boostautotune.core.sweep;

import io.github.xadmiral.boostautotune.core.model.Grid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The table to drive with on the next run. */
public final class SweepPlan {
    public final int runNumber;
    public final SweepKind kind;
    public final int pass;
    public final int candidateIndex;
    public final double offset;
    public final boolean finalResult;
    public final Grid table;
    public final List<String> notes = new ArrayList<String>();

    SweepPlan(int runNumber, SweepKind kind, int pass, int candidateIndex, double offset, boolean finalResult, Grid table) {
        this.runNumber = runNumber;
        this.kind = kind;
        this.pass = pass;
        this.candidateIndex = candidateIndex;
        this.offset = offset;
        this.finalResult = finalResult;
        this.table = table;
    }

    public String title() {
        if (finalResult) {
            return kind.label() + " sweep complete: optimised table";
        }
        return String.format(Locale.US, "Run %d: %s, pass %d, candidate %+.1f deg%s", runNumber, kind.label(), pass + 1,
                offset, offset == 0 ? " (baseline)" : "");
    }

    public String driverInstructions() {
        if (finalResult) {
            return "Review the table, then burn.";
        }
        return "Do one or two full-throttle pulls in the SAME gear on the SAME stretch of road as the other runs, "
                + "from below the RPM window to redline. The plugin compares engine acceleration per RPM between candidates.";
    }
}
