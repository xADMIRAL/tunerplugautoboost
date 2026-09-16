package io.github.xadmiral.boostautotune.plugin.mode;

import io.github.xadmiral.boostautotune.core.als.AlsConfig;
import io.github.xadmiral.boostautotune.core.als.AlsSession;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Anti-lag autotune wired to the ECU: ALS timing table plus the idle valve air. */
public final class AntilagDriver implements ModeDriver {
    private final EcuAdapter adapter;
    private final AlsSession session;
    private final Grid originalTiming;
    private final double originalAir;
    private final double originalMaxTime;
    private final double originalMinRpm;
    private final String originalEnable;
    private final List<String> startupLog = new ArrayList<String>();
    private double runPeak = Double.NaN;

    public AntilagDriver(EcuAdapter adapter, AlsConfig cfg) throws EcuException {
        this.adapter = adapter;
        AlsConfig c = cfg.copy();
        Grid timing = adapter.readAlsTiming();
        EcuPort.ParamInfo tInfo = adapter.alsTableInfo();
        if (tInfo != null && tInfo.max > tInfo.min) {
            c.minTimingDeg = Math.max(c.minTimingDeg, tInfo.min);
            c.maxTimingDeg = Math.min(c.maxTimingDeg, tInfo.max);
        }
        double air = adapter.readAlsAir();
        EcuPort.ParamInfo aInfo = adapter.alsAirInfo();
        if (aInfo != null && aInfo.max > aInfo.min) {
            c.airMin = Math.max(c.airMin, aInfo.min);
            c.airMax = Math.min(c.airMax, aInfo.max);
        }
        originalTiming = timing.copy();
        originalAir = air;
        originalMaxTime = adapter.readAlsMaxTime();
        originalMinRpm = adapter.readAlsMinRpm();
        originalEnable = adapter.readAlsEnable();
        if (originalEnable != null && adapter.binding().alsDisableOption.equalsIgnoreCase(originalEnable.trim())) {
            startupLog.add("WARNING: the anti-lag is switched off in the ECU (" + adapter.binding().alsEnableParam + " = "
                    + originalEnable + "). Write a drift preset first, otherwise no ALS events will happen.");
        }
        session = new AlsSession(c);
        session.initialize(timing, air);
        startupLog.add(String.format(Locale.US, "Anti-lag target %.0f kPa off throttle (+/- %.0f), hold >= %.0f rpm for %.0f s; timing %.0f..%.0f deg, idle valve %s %.0f..%.0f",
                c.targetKpa, c.tolKpa, c.holdRpm, c.holdSec, c.minTimingDeg, c.maxTimingDeg, adapter.alsAirParam(), c.airMin, c.airMax));
        if (adapter.binding().has(adapter.binding().alsMaxTimeParam)) {
            startupLog.add(String.format(Locale.US, "%s will be set to %.0f s and %s to %.0f rpm with the first plan",
                    adapter.binding().alsMaxTimeParam, c.holdSec, adapter.binding().alsMinRpmParam, c.ecuMinRpm()));
        }
        startupLog.add(String.format(Locale.US, "Guards: MAT warn %.0f / abort %.0f C, stall %.0f rpm, max %.0f s ALS per run",
                c.maxMatC, c.abortMatC, c.stallRpm, c.maxActiveSecPerRun));
        startupLog.add("First plan: " + session.plan().title());
    }

    public List<String> startupLog() {
        return startupLog;
    }

    public AlsSession session() {
        return session;
    }

    @Override
    public TuneMode mode() {
        return TuneMode.ANTILAG;
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

    private void write(Grid timing, double air) throws EcuException {
        adapter.writeAlsTiming(timing);
        adapter.writeAlsAir(air);
    }

    private void writeHold(double sec, double minRpm) throws EcuException {
        adapter.writeAlsMaxTime(sec);
        adapter.writeAlsMinRpm(minRpm);
    }

    @Override
    public void writePlan() throws EcuException {
        if (session.plan() == null) {
            throw new EcuException("No plan to write");
        }
        write(session.plan().timing, session.plan().air);
        writeHold(session.plan().holdSec, session.plan().ecuMinRpm);
    }

    @Override
    public void startRun() {
        runPeak = Double.NaN;
        session.startRun();
    }

    @Override
    public void onSample(Sample s) {
        session.onSample(s);
        if (s.alsActive && !Double.isNaN(s.map)) {
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
        AlsSession.Report r = session.lastReport();
        if (r == null || session.state() != SessionState.REVIEW) {
            throw new EcuException("Nothing to apply");
        }
        AlsSession.Plan plan = session.commit(r);
        write(plan.timing, plan.air);
        writeHold(plan.holdSec, plan.ecuMinRpm);
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
        write(originalTiming, originalAir);
        writeHold(originalMaxTime, originalMinRpm);
        adapter.writeAlsEnable(originalEnable);
        if (session.state() != SessionState.DONE) {
            session.abort("Original anti-lag settings restored by user");
        }
    }

    /** Original table and air, and the anti-lag switched off until the driver restores it. */
    @Override
    public void writeSafeState() throws EcuException {
        write(originalTiming, originalAir);
        writeHold(originalMaxTime, originalMinRpm);
        if (adapter.binding().has(adapter.binding().alsDisableOption)) {
            adapter.writeAlsEnable(adapter.binding().alsDisableOption);
        }
    }

    @Override
    public boolean hasOriginal() {
        return originalTiming != null;
    }

    @Override
    public int pullsInRun() {
        return session.eventsInRun();
    }

    @Override
    public double runPeak() {
        return runPeak;
    }

    @Override
    public String lastReportText() {
        return session.lastReport() == null ? "" : session.lastReport().summary(session.config());
    }

    @Override
    public String analysisText() {
        return lastReportText();
    }

    @Override
    public List<TableView> tables() {
        List<TableView> out = new ArrayList<TableView>();
        if (session.plan() != null) {
            out.add(new TableView("ALS timing table for the next run vs original (deg)", "tps \\ rpm", session.plan().timing, originalTiming, null));
        }
        return out;
    }

    @Override
    public String logPrefix() {
        return "[als] ";
    }
}
