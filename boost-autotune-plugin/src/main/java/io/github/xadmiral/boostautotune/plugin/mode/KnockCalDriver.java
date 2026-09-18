package io.github.xadmiral.boostautotune.plugin.mode;

import io.github.xadmiral.boostautotune.core.knock.KnockCalConfig;
import io.github.xadmiral.boostautotune.core.knock.KnockCalSession;
import io.github.xadmiral.boostautotune.core.model.Axis;
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

/** Knock sensor calibration wired to the ECU: the threshold curve plus the (per-cylinder) gains. */
public final class KnockCalDriver implements ModeDriver {
    private final EcuAdapter adapter;
    private final KnockCalSession session;
    private final double[] rpmBins;
    private final double[] originalThresholds;
    private final double[] originalGains;
    private final List<String> gainParams;
    private final List<String> startupLog = new ArrayList<String>();
    private double runPeak = Double.NaN;

    public KnockCalDriver(EcuAdapter adapter, KnockCalConfig cfg) throws EcuException {
        this.adapter = adapter;
        KnockCalConfig c = cfg.copy();
        rpmBins = adapter.readKnockRpmBins();
        double[] thresholds = adapter.readKnockThresholds();
        if (thresholds.length != rpmBins.length) {
            throw new EcuException(String.format(Locale.US, "The knock threshold curve has %d values but %d RPM bins", thresholds.length, rpmBins.length));
        }
        EcuPort.ParamInfo tInfo = adapter.knockThresholdInfo();
        if (tInfo != null && tInfo.max > tInfo.min) {
            c.minThresholdPct = Math.max(c.minThresholdPct, tInfo.min);
            c.maxThresholdPct = Math.min(c.maxThresholdPct, tInfo.max);
        }
        int cylinders = adapter.cylinders();
        boolean perCyl = adapter.knockPerCylinder();
        gainParams = adapter.knockGainParams(cylinders, perCyl);
        double[] gains = gainParams.isEmpty() ? new double[]{1.0} : adapter.readKnockGains(gainParams);
        double[] options = gainParams.isEmpty() ? null : adapter.knockGainOptions(gainParams);
        if (c.windowFromEcu) {
            double v = adapter.readKnockMinLoad();
            if (!Double.isNaN(v) && v > 0) {
                c.minLoad = v;
            }
            v = adapter.readKnockLoRpm();
            if (!Double.isNaN(v) && v > 0) {
                c.minRpm = Math.max(c.minRpm, v);
            }
            v = adapter.readKnockHiRpm();
            if (!Double.isNaN(v) && v > c.minRpm) {
                c.maxRpm = v;
            }
        }
        String control = adapter.readKnockControl();
        if (control != null && adapter.binding().has(adapter.binding().knockControlOffOption)
                && adapter.binding().knockControlOffOption.equalsIgnoreCase(control.trim())) {
            startupLog.add("WARNING: knock control is disabled in the ECU (" + adapter.binding().knockControlParam + " = " + control
                    + "): the curve and gains are calibrated all the same, but no knock retard can show up until it is enabled.");
        }
        if (gainParams.isEmpty()) {
            startupLog.add("No knock gain parameter bound: only the threshold curve is calibrated.");
        } else if (gainParams.size() > 1 && adapter.knockCylChannels(cylinders).isEmpty()) {
            startupLog.add("Per-cylinder knock channels are not bound: all cylinders get the same gain change.");
        }
        originalThresholds = thresholds.clone();
        originalGains = gains.clone();
        session = new KnockCalSession(c);
        KnockCalSession.Plan plan = session.initialize(rpmBins, thresholds, gains, options);
        startupLog.add(String.format(Locale.US,
                "Knock calibration: %d RPM bins %s, %d gain(s) %s, noise target %.0f %% (%.0f..%.0f), margin %.0f %%, samples at load >= %.0f between %.0f and %.0f rpm",
                rpmBins.length, adapter.binding().knockRpmBins, gains.length, gainParams.isEmpty() ? "(none)" : gainParams.toString(),
                c.noiseTargetPct, c.noiseBandLowPct, c.noiseBandHighPct, c.marginPct, c.minLoad, c.minRpm, c.maxRpm));
        startupLog.add("First plan: " + plan.title());
    }

    public List<String> startupLog() {
        return startupLog;
    }

    public KnockCalSession session() {
        return session;
    }

    public List<String> gainParams() {
        return gainParams;
    }

    @Override
    public TuneMode mode() {
        return TuneMode.KNOCK_CAL;
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
        return (session.phase() == KnockCalSession.Phase.SURVEY ? "survey" : "verify") + " run " + session.runNumber();
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

    private void write(double[] thresholds, double[] gains) throws EcuException {
        adapter.writeKnockThresholds(thresholds);
        if (!gainParams.isEmpty()) {
            adapter.writeKnockGains(gainParams, gains);
        }
    }

    @Override
    public void writePlan() throws EcuException {
        if (session.plan() == null) {
            throw new EcuException("No plan to write");
        }
        write(session.plan().thresholds, session.plan().gains);
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
        KnockCalSession.Report r = session.lastReport();
        if (r == null || session.state() != SessionState.REVIEW) {
            throw new EcuException("Nothing to apply");
        }
        KnockCalSession.Plan plan = session.commit(r);
        write(plan.thresholds, plan.gains);
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
        write(originalThresholds, originalGains);
        if (session.state() != SessionState.DONE) {
            session.abort("Original knock settings restored by user");
        }
    }

    @Override
    public void writeSafeState() throws EcuException {
        write(originalThresholds, originalGains);
    }

    @Override
    public boolean hasOriginal() {
        return originalThresholds != null;
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
        return session.lastReport() == null ? "" : session.lastReport().summary(session.config());
    }

    @Override
    public String analysisText() {
        return lastReportText();
    }

    private static Grid row(double[] x, double[] values) {
        return new Grid(new Axis(x), Axis.of(0), new double[][]{values});
    }

    @Override
    public List<TableView> tables() {
        List<TableView> out = new ArrayList<TableView>();
        KnockCalSession.Plan plan = session.plan();
        if (plan == null) {
            return out;
        }
        String when = plan.finalResult ? "result" : "next run";
        out.add(new TableView("Knock thresholds (" + when + " vs original), % per RPM bin", "% \\ rpm", row(rpmBins, plan.thresholds), row(rpmBins, originalThresholds), null));
        if (plan.gains.length > 1) {
            double[] cyl = new double[plan.gains.length];
            for (int i = 0; i < cyl.length; i++) {
                cyl[i] = i + 1;
            }
            out.add(new TableView("Knock gains (" + when + " vs original), per cylinder", "gain \\ cyl", row(cyl, plan.gains), row(cyl, originalGains), null, "%.3f"));
        }
        return out;
    }

    @Override
    public String logPrefix() {
        return "[knock] ";
    }
}
