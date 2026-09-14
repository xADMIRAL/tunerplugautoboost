package io.github.xadmiral.boostautotune.plugin.mode;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.AutotuneSession;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import io.github.xadmiral.boostautotune.core.session.RunPlan;
import io.github.xadmiral.boostautotune.core.session.RunReport;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Boost closed-loop autotune wired to the ECU. */
public final class BoostDriver implements ModeDriver {
    private final EcuAdapter adapter;
    private final AutotuneSession session;
    private final EcuState original;
    private final List<String> startupLog = new ArrayList<String>();

    public BoostDriver(EcuAdapter adapter, AutotuneConfig cfg) throws EcuException {
        this.adapter = adapter;
        List<String> warnings = new ArrayList<String>();
        EcuState state = adapter.read(warnings);
        for (String w : warnings) {
            startupLog.add("WARNING: " + w);
        }
        AutotuneConfig c = cfg.copy();
        applyEcuGainLimits(c);
        double overboost = adapter.readOverboostLimit();
        if (!Double.isNaN(overboost) && overboost > 0 && c.maxBoostKpa >= overboost) {
            startupLog.add(String.format(Locale.US,
                    "WARNING: plugin hard limit %.0f kPa is not below the ECU overboost cut %.0f kPa; lowering it to %.0f",
                    c.maxBoostKpa, overboost, overboost - 5));
            c.maxBoostKpa = overboost - 5;
        }
        original = state.copy();
        session = new AutotuneSession(c);
        RunPlan plan = session.initialize(state);
        startupLog.add("Targets: " + c.targetStagesKpa + " kPa, hard limit " + c.maxBoostKpa + " kPa");
        startupLog.add("First plan: " + plan.title());
        for (String n : plan.notes) {
            startupLog.add("  " + n);
        }
    }

    private void applyEcuGainLimits(AutotuneConfig c) {
        EcuPort.ParamInfo p = adapter.pidInfo(0);
        EcuPort.ParamInfo i = adapter.pidInfo(1);
        EcuPort.ParamInfo d = adapter.pidInfo(2);
        if (p != null && p.max > p.min) {
            c.pMin = Math.max(p.min, p.max > 10 ? 1 : p.min);
            c.pMax = p.max;
            c.pidDecimals = p.decimals;
        }
        if (i != null && i.max > i.min) {
            c.iMin = i.min;
            c.iMax = i.max;
        }
        if (d != null && d.max > d.min) {
            c.dMin = d.min;
            c.dMax = d.max;
        }
        EcuPort.ParamInfo w = adapter.windowInfo();
        if (w != null && w.max > w.min) {
            c.windowMinKpa = Math.max(c.windowMinKpa, w.min);
            c.windowMaxKpa = Math.min(c.windowMaxKpa, w.max);
        }
    }

    public List<String> startupLog() {
        return startupLog;
    }

    public AutotuneSession session() {
        return session;
    }

    public EcuState original() {
        return original;
    }

    @Override
    public TuneMode mode() {
        return TuneMode.BOOST;
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
        return session.phase().name();
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

    @Override
    public void writePlan() throws EcuException {
        RunPlan plan = session.plan();
        if (plan == null) {
            throw new EcuException("No plan to write");
        }
        boolean openLoop = !plan.ecu.closedLoop;
        adapter.write(plan.ecu, true, true, openLoop || plan.ecu.openLoopTable != null);
    }

    @Override
    public void startRun() {
        session.startRun();
    }

    @Override
    public void onSample(Sample s) {
        session.onSample(s);
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
        RunReport report = session.lastReport();
        if (report == null || session.state() != SessionState.REVIEW) {
            throw new EcuException("Nothing to apply");
        }
        RunPlan plan = session.commit(report);
        adapter.write(plan.ecu, true, true, plan.ecu.openLoopTable != null);
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
        adapter.write(original, true, original.biasTable != null, original.openLoopTable != null);
        if (session.state() != SessionState.DONE) {
            session.abort("Original settings restored by user");
        }
    }

    @Override
    public void writeSafeState() throws EcuException {
        adapter.writeSafeState(session.currentEcuState());
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
        return session.runPeak();
    }

    @Override
    public String lastReportText() {
        return session.lastReport() == null ? "" : session.lastReport().summary();
    }

    @Override
    public String analysisText() {
        StringBuilder sb = new StringBuilder(lastReportText());
        if (session.plantModel() != null) {
            sb.append("\nPlant model (duty -> boost per RPM column):\n").append(session.plantModel().describe());
        }
        if (session.spoolTrim() != null && session.spoolTrim().max() > 0) {
            sb.append("\nSpool trim applied to the bias table (%):\n").append(session.spoolTrim().toText("%7.1f"));
        }
        return sb.toString();
    }

    @Override
    public List<TableView> tables() {
        List<TableView> out = new ArrayList<TableView>();
        RunPlan plan = session.plan();
        RunReport r = session.lastReport();
        if (plan == null) {
            return out;
        }
        EcuState ref = r != null && r.nextPlan == plan ? r.plan.ecu : original;
        out.add(new TableView("Bias / feed-forward duty table for the next run (green = measured, yellow = interpolated, orange = extrapolated, grey = borrowed)",
                "target \\ rpm", plan.ecu.biasTable, ref.biasTable, r == null ? null : r.biasResult));
        out.add(new TableView("Target table for the next run", "load \\ rpm", plan.ecu.targetTable, ref.targetTable, null));
        return out;
    }

    @Override
    public String logPrefix() {
        return "[boost] ";
    }
}
