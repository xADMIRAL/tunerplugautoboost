package io.github.xadmiral.boostautotune.plugin;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.log.LogColumnMapping;
import io.github.xadmiral.boostautotune.core.log.MslLogReader;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.AutotuneSession;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import io.github.xadmiral.boostautotune.core.session.RunPlan;
import io.github.xadmiral.boostautotune.core.session.RunReport;
import io.github.xadmiral.boostautotune.core.session.SafetyListener;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;
import io.github.xadmiral.boostautotune.plugin.ecu.LiveFeed;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates a session against a real or simulated ECU: snapshot, plan writing, live feed,
 * run analysis, commit, safety reaction, undo. All public methods are safe to call from the EDT;
 * live samples arrive on the ECU's thread and are serialized through {@code lock}.
 */
public final class TuneController {

    /** UI callbacks; invoked on the calling thread (samples: ECU thread; the rest: caller). */
    public interface Listener {
        void log(String line);

        void stateChanged();

        void sample(Sample s, SampleState state);

        void reportReady(RunReport report);
    }

    private final Object lock = new Object();
    private final Listener listener;
    private EcuPort port;
    private EcuAdapter adapter;
    private AutotuneSession session;
    private EcuState original;
    private LiveFeed feed;
    private boolean feedActive;
    private boolean ecuPrepared;
    private boolean autoEndRun;
    private boolean autoApply;
    private volatile Sample lastSample;
    private volatile SampleState lastState;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public TuneController(Listener listener) {
        this.listener = listener;
    }

    public void setPort(EcuPort port) {
        synchronized (lock) {
            stopFeed();
            this.port = port;
            this.adapter = null;
            this.session = null;
            this.original = null;
            this.ecuPrepared = false;
        }
        listener.stateChanged();
    }

    public EcuPort port() {
        return port;
    }

    public EcuAdapter adapter() {
        return adapter;
    }

    public AutotuneSession session() {
        return session;
    }

    public EcuState original() {
        return original;
    }

    public Sample lastSample() {
        return lastSample;
    }

    public SampleState lastSampleState() {
        return lastState;
    }

    public boolean isEcuPrepared() {
        return ecuPrepared;
    }

    public boolean isFeedActive() {
        return feedActive;
    }

    public void setAutoEndRun(boolean v) {
        autoEndRun = v;
    }

    public void setAutoApply(boolean v) {
        autoApply = v;
    }

    public SessionState state() {
        AutotuneSession s = session;
        return s == null ? SessionState.IDLE : s.state();
    }

    // ------------------------------------------------------------------------------------------

    /** Reads the ECU, keeps an undo copy, and creates the session with its first plan. */
    public RunPlan startSession(AutotuneConfig cfg, EcuBinding binding) throws EcuException {
        synchronized (lock) {
            if (port == null) {
                throw new EcuException("No ECU connection");
            }
            stopFeed();
            adapter = new EcuAdapter(port, binding.copy());
            List<String> warnings = new ArrayList<String>();
            EcuState state = adapter.read(warnings);
            for (String w : warnings) {
                log("WARNING: " + w);
            }
            AutotuneConfig c = cfg.copy();
            applyEcuGainLimits(c);
            double overboost = adapter.readOverboostLimit();
            if (!Double.isNaN(overboost) && overboost > 0 && c.maxBoostKpa >= overboost) {
                log(String.format(Locale.US,
                        "WARNING: plugin hard limit %.0f kPa is not below the ECU overboost cut %.0f kPa; lowering it to %.0f",
                        c.maxBoostKpa, overboost, overboost - 5));
                c.maxBoostKpa = overboost - 5;
            }
            original = state.copy();
            session = new AutotuneSession(c);
            session.setSafetyListener(new SafetyListener() {
                @Override
                public void overboost(Sample sample, double limitKpa) {
                    onOverboost(sample, limitKpa);
                }
            });
            RunPlan plan = session.initialize(state);
            ecuPrepared = false;
            log("Session started on '" + adapter.config() + "'. Undo copy of the boost settings taken.");
            log("Targets: " + c.targetStagesKpa + " kPa, hard limit " + c.maxBoostKpa + " kPa");
            log("First plan: " + plan.title());
            for (String n : plan.notes) {
                log("  " + n);
            }
            listener.stateChanged();
            return plan;
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
    }

    /** Writes the current plan to the ECU (RAM). */
    public void writePlanToEcu() throws EcuException {
        synchronized (lock) {
            requireSession();
            RunPlan plan = session.plan();
            if (plan == null) {
                throw new EcuException("No plan to write");
            }
            boolean openLoop = !plan.ecu.closedLoop;
            List<String> done = adapter.write(plan.ecu, true, true, openLoop || plan.ecu.openLoopTable != null);
            ecuPrepared = true;
            log("Written to ECU for " + plan.title() + ": " + join(done));
            listener.stateChanged();
        }
    }

    public void startRun() throws EcuException {
        synchronized (lock) {
            requireSession();
            if (!ecuPrepared) {
                throw new EcuException("Write the plan to the ECU first");
            }
            session.startRun();
            startFeed();
            log("Run " + session.runNumber() + " recording. " + session.plan().driverInstructions());
            listener.stateChanged();
        }
    }

    public RunReport endRun() throws EcuException {
        RunReport report;
        synchronized (lock) {
            requireSession();
            stopFeed();
            if (session.state() != SessionState.RECORDING) {
                throw new EcuException("No run in progress");
            }
            report = session.endRun();
            log(report.summary());
            listener.stateChanged();
        }
        listener.reportReady(report);
        if (autoApply && !report.sessionDone && session.state() == SessionState.REVIEW) {
            applyAndPrepareNext();
        }
        return report;
    }

    /** Commits the report's plan and writes it to the ECU. */
    public RunPlan applyAndPrepareNext() throws EcuException {
        synchronized (lock) {
            requireSession();
            RunReport report = session.lastReport();
            if (report == null || session.state() != SessionState.REVIEW) {
                throw new EcuException("Nothing to apply");
            }
            RunPlan plan = session.commit(report);
            ecuPrepared = false;
            if (session.state() == SessionState.DONE) {
                adapter.write(plan.ecu, true, true, plan.ecu.openLoopTable != null);
                ecuPrepared = true;
                log("Final tune written to ECU RAM. Review it in TunerStudio and burn.");
            } else {
                writePlanToEcu();
            }
            listener.stateChanged();
            return plan;
        }
    }

    public RunPlan repeatRun() throws EcuException {
        synchronized (lock) {
            requireSession();
            RunPlan plan = session.repeatRun();
            log("Repeating: " + plan.title());
            listener.stateChanged();
            return plan;
        }
    }

    public void abort(String reason) {
        synchronized (lock) {
            stopFeed();
            if (session != null) {
                session.abort(reason);
            }
            log("Aborted: " + reason);
            listener.stateChanged();
        }
    }

    /** Puts the boost settings back to what they were when the session started. */
    public void restoreOriginal() throws EcuException {
        synchronized (lock) {
            if (adapter == null || original == null) {
                throw new EcuException("No undo copy available");
            }
            stopFeed();
            List<String> done = adapter.write(original, true, original.biasTable != null, original.openLoopTable != null);
            if (session != null && session.state() != SessionState.DONE) {
                session.abort("Original settings restored by user");
            }
            ecuPrepared = false;
            log("Original boost settings restored: " + join(done));
            listener.stateChanged();
        }
    }

    public void burn() throws EcuException {
        synchronized (lock) {
            if (adapter == null) {
                throw new EcuException("No session");
            }
            adapter.burn();
            log("Burn command sent");
        }
    }

    /** Runs an already recorded datalog through the session as if it were live. */
    public RunReport analyzeLog(File file, LogColumnMapping mapping) throws EcuException, IOException {
        synchronized (lock) {
            requireSession();
            if (session.state() != SessionState.READY) {
                throw new EcuException("The session must be READY (plan written) to analyse a log as the next run");
            }
            InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
            MslLogReader.Result r;
            try {
                r = MslLogReader.read(reader, mapping);
            } finally {
                reader.close();
            }
            for (String w : r.warnings) {
                log("WARNING: " + w);
            }
            log("Log " + file.getName() + ": " + r.samples.size() + " samples, " + r.skippedLines + " skipped lines");
            ecuPrepared = true;
            session.startRun();
            for (Sample s : r.samples) {
                session.onSample(s);
                if (session.state() == SessionState.ABORTED) {
                    break;
                }
            }
            if (session.state() == SessionState.ABORTED) {
                log("Log analysis stopped: " + session.abortReason());
                listener.stateChanged();
                return null;
            }
            RunReport report = session.endRun();
            log(report.summary());
            listener.stateChanged();
            listener.reportReady(report);
            return report;
        }
    }

    // ------------------------------------------------------------------------------------------

    private void startFeed() throws EcuException {
        if (feedActive) {
            return;
        }
        feed = new LiveFeed(adapter.binding(), new LiveFeed.SampleListener() {
            @Override
            public void sample(Sample s) {
                onSample(s);
            }
        });
        port.subscribe(adapter.config(), feed.channels(), feed);
        feedActive = true;
    }

    private void stopFeed() {
        if (feed != null && port != null) {
            port.unsubscribe(feed);
        }
        feedActive = false;
    }

    private void onSample(Sample s) {
        SampleState st;
        boolean finish = false;
        synchronized (lock) {
            if (session == null || session.state() != SessionState.RECORDING) {
                return;
            }
            st = session.onSample(s);
            lastSample = s;
            lastState = st;
            if (autoEndRun && session.runLooksFinished()) {
                finish = true;
            }
        }
        listener.sample(s, st);
        if (finish) {
            try {
                log("Run finished automatically (no WOT for " + session.config().autoEndRunIdleSec + " s)");
                endRun();
            } catch (EcuException e) {
                log("Auto end failed: " + e.getMessage());
            }
        }
    }

    private void onOverboost(final Sample sample, final double limitKpa) {
        // Called on the ECU's channel thread from session.onSample: the session is already ABORTED,
        // the ECU writes go to a separate thread so TunerStudio's own comm thread is never re-entered.
        log(String.format(Locale.US, "OVERBOOST %.1f kPa (limit %.0f) at %.0f rpm - writing safe state", sample.map, limitKpa, sample.rpm));
        final EcuAdapter a = adapter;
        final EcuState st = session.currentEcuState();
        ecuPrepared = false;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    stopFeed();
                    try {
                        List<String> done = a.writeSafeState(st);
                        log("Safe state written: " + join(done));
                    } catch (EcuException e) {
                        log("The safe state could not be written: " + e.getMessage() + " - LIFT OFF");
                    }
                }
                listener.stateChanged();
            }
        }, "boost-autotune-safety");
        t.setDaemon(true);
        t.start();
    }

    /** Waits until a pending safety write has finished (tests and orderly shutdown). */
    public void awaitSafety(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (feedActive && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void requireSession() throws EcuException {
        if (session == null) {
            throw new EcuException("Start a session first");
        }
    }

    private void log(String s) {
        listener.log(time.format(new Date()) + "  " + s);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
