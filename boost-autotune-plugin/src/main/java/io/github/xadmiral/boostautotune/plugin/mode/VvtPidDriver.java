package io.github.xadmiral.boostautotune.plugin.mode;

import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.vvt.VvtPidConfig;
import io.github.xadmiral.boostautotune.core.vvt.VvtPidSession;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** VVT closed-loop gain tuning wired to the ECU. */
public final class VvtPidDriver implements ModeDriver {
    private final EcuAdapter adapter;
    private final VvtPidSession session;
    private final PidGains original;
    private final List<String> startupLog = new ArrayList<String>();

    public VvtPidDriver(EcuAdapter adapter, VvtPidConfig cfg) throws EcuException {
        this.adapter = adapter;
        VvtPidConfig c = cfg.copy();
        PidGains gains = adapter.readVvtGains();
        EcuPort.ParamInfo p = adapter.vvtPidInfo(0);
        EcuPort.ParamInfo i = adapter.vvtPidInfo(1);
        EcuPort.ParamInfo d = adapter.vvtPidInfo(2);
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
        original = gains;
        session = new VvtPidSession(c);
        session.initialize(gains);
        startupLog.add("VVT gains in ECU: " + gains);
        startupLog.add("First plan: " + session.plan().title());
    }

    public List<String> startupLog() {
        return startupLog;
    }

    public VvtPidSession session() {
        return session;
    }

    @Override
    public TuneMode mode() {
        return TuneMode.VVT_PID;
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
        return "run " + session.runNumber();
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
        if (session.plan() == null) {
            throw new EcuException("No plan to write");
        }
        adapter.writeVvtGains(session.plan().gains);
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
        return false;
    }

    @Override
    public Object endRun() {
        return session.endRun();
    }

    @Override
    public void commitAndWrite() throws EcuException {
        VvtPidSession.Report r = session.lastReport();
        if (r == null || session.state() != SessionState.REVIEW) {
            throw new EcuException("Nothing to apply");
        }
        VvtPidSession.Plan plan = session.commit(r);
        adapter.writeVvtGains(plan.gains);
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
        adapter.writeVvtGains(original);
        if (session.state() != SessionState.DONE) {
            session.abort("Original gains restored by user");
        }
    }

    @Override
    public void writeSafeState() throws EcuException {
        adapter.writeVvtGains(original);
    }

    @Override
    public boolean hasOriginal() {
        return original != null;
    }

    @Override
    public int pullsInRun() {
        return 0;
    }

    @Override
    public double runPeak() {
        return Double.NaN;
    }

    @Override
    public String lastReportText() {
        return session.lastReport() == null ? "" : session.lastReport().summary();
    }

    @Override
    public String analysisText() {
        return lastReportText();
    }

    @Override
    public List<TableView> tables() {
        return Collections.emptyList();
    }

    @Override
    public String logPrefix() {
        return "[vvt-pid] ";
    }
}
