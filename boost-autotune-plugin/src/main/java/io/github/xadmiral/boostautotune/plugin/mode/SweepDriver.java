package io.github.xadmiral.boostautotune.plugin.mode;

import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.sweep.SweepConfig;
import io.github.xadmiral.boostautotune.core.sweep.SweepKind;
import io.github.xadmiral.boostautotune.core.sweep.SweepPlan;
import io.github.xadmiral.boostautotune.core.sweep.SweepReport;
import io.github.xadmiral.boostautotune.core.sweep.SweepSession;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** VVT target or ignition advance sweep wired to the ECU. */
public final class SweepDriver implements ModeDriver {
    private final EcuAdapter adapter;
    private final SweepSession session;
    private final SweepKind kind;
    private final Grid original;
    private final List<String> startupLog = new ArrayList<String>();
    private double runPeak = Double.NaN;

    public SweepDriver(EcuAdapter adapter, SweepConfig cfg) throws EcuException {
        this.adapter = adapter;
        this.kind = cfg.kind;
        SweepConfig c = cfg.copy();
        Grid table = kind == SweepKind.VVT ? adapter.readVvtTable() : adapter.readSparkTable();
        EcuPort.ParamInfo info = kind == SweepKind.VVT ? adapter.vvtTableInfo() : adapter.sparkTableInfo();
        if (info != null && info.max > info.min) {
            c.absoluteMin = Math.max(c.absoluteMin, info.min);
            c.absoluteMax = Math.min(c.absoluteMax, info.max);
        }
        if (!table.xAxis().isStrictlyIncreasing() || !table.yAxis().isStrictlyIncreasing()) {
            startupLog.add("WARNING: the table axes are not strictly increasing: " + table.xAxis() + " / " + table.yAxis());
        }
        int wotRows = 0;
        for (int yi = 0; yi < table.height(); yi++) {
            if (table.yAxis().bin(yi) >= c.minLoad) {
                wotRows++;
            }
        }
        if (wotRows == 0) {
            throw new EcuException(String.format(Locale.US, "No table row has a load bin >= %.0f; lower 'min load' (axis: %s)", c.minLoad, table.yAxis()));
        }
        original = table.copy();
        session = new SweepSession(c, kind == SweepKind.VVT ? adapter.binding().vvtLoadSource : adapter.binding().sparkLoadSource);
        SweepPlan plan = session.initialize(table);
        startupLog.add(String.format(Locale.US, "%s sweep: candidates %s deg, %d pass(es), rows with load >= %.0f, RPM %.0f-%.0f%s",
                kind.label(), c.candidateOffsets, c.passes, c.minLoad, c.minRpm, c.maxRpm, c.gear > 0 ? ", gear " + c.gear : ""));
        if (kind == SweepKind.IGNITION) {
            startupLog.add(String.format(Locale.US,
                    "Ignition guards: knock retard >= %.1f deg caps the cell, >= %.1f deg aborts, AFR > %.1f at WOT aborts, never more than +%.1f deg over the original",
                    c.knockRetardTriggerDeg, c.knockAbortRetardDeg, c.maxWotAfr, c.maxAdvanceOverOriginalDeg));
        }
        startupLog.add("First plan: " + plan.title());
    }

    public List<String> startupLog() {
        return startupLog;
    }

    public SweepSession session() {
        return session;
    }

    @Override
    public TuneMode mode() {
        return kind == SweepKind.VVT ? TuneMode.VVT_SWEEP : TuneMode.IGNITION_SWEEP;
    }

    @Override
    public SessionState state() {
        return session.state();
    }

    @Override
    public String abortReason() {
        return session.abortReason();
    }

    @Override
    public String phase() {
        return session.progress();
    }

    @Override
    public String planTitle() {
        return session.plan() == null ? "" : session.plan().title();
    }

    @Override
    public List<String> planNotes() {
        return session.plan() == null ? Collections.<String>emptyList() : session.plan().notes;
    }

    @Override
    public String instructions() {
        return session.plan() == null ? "" : session.plan().driverInstructions();
    }

    private void writeTable(Grid g) throws EcuException {
        if (kind == SweepKind.VVT) {
            adapter.writeVvtTable(g);
        } else {
            adapter.writeSparkTable(g);
        }
    }

    @Override
    public void writePlan() throws EcuException {
        if (session.plan() == null) {
            throw new EcuException("No plan to write");
        }
        writeTable(session.plan().table);
    }

    @Override
    public void startRun() {
        runPeak = Double.NaN;
        session.startRun();
    }

    @Override
    public void onSample(Sample s) {
        session.onSample(s);
        if (!Double.isNaN(s.map)) {
            runPeak = Double.isNaN(runPeak) ? s.map : Math.max(runPeak, s.map);
        }
    }

    @Override
    public boolean runLooksFinished() {
        return session.runLooksFinished();
    }

    @Override
    public Object endRun() {
        return session.endRun();
    }

    @Override
    public void commitAndWrite() throws EcuException {
        SweepReport r = session.lastReport();
        if (r == null || session.state() != SessionState.REVIEW) {
            throw new EcuException("Nothing to apply");
        }
        SweepPlan plan = session.commit(r);
        writeTable(plan.table);
    }

    @Override
    public void repeatRun() {
        session.repeatRun();
    }

    @Override
    public void abort(String reason) {
        session.abort(reason);
    }

    @Override
    public void restoreOriginal() throws EcuException {
        writeTable(original);
        if (session.state() != SessionState.DONE) {
            session.abort("Original table restored by user");
        }
    }

    @Override
    public void writeSafeState() throws EcuException {
        writeTable(original);
    }

    @Override
    public boolean hasOriginal() {
        return original != null;
    }

    @Override
    public int pullsInRun() {
        return session.pullsInRun();
    }

    @Override
    public double runPeak() {
        return runPeak;
    }

    @Override
    public String lastReportText() {
        return session.lastReport() == null ? "" : session.lastReport().summary();
    }

    @Override
    public String analysisText() {
        StringBuilder sb = new StringBuilder(lastReportText());
        if (kind == SweepKind.IGNITION && session.knockGuard() != null && session.knockGuard().cappedCells() > 0) {
            sb.append("\nKnock caps (max advance per cell, blank = none):\n");
            Grid caps = session.knockGuard().capsGrid();
            sb.append(caps.toText("%7.1f").replace("    NaN", "      -"));
        }
        return sb.toString();
    }

    @Override
    public List<TableView> tables() {
        List<TableView> out = new ArrayList<TableView>();
        if (session.plan() == null) {
            return out;
        }
        String title = session.plan().finalResult ? kind.label() + " table: optimised result vs original"
                : kind.label() + " table for the next run vs original";
        out.add(new TableView(title, "load \\ rpm", session.plan().table, original, null));
        return out;
    }

    @Override
    public String logPrefix() {
        return kind == SweepKind.VVT ? "[vvt] " : "[ign] ";
    }
}
