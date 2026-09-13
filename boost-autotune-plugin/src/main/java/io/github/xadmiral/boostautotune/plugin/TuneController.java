package io.github.xadmiral.boostautotune.plugin;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.SampleClassifier;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.log.LogColumnMapping;
import io.github.xadmiral.boostautotune.core.log.MslLogReader;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.AutotuneSession;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import io.github.xadmiral.boostautotune.core.session.RunReport;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.sweep.SweepConfig;
import io.github.xadmiral.boostautotune.core.vvt.VvtPidConfig;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;
import io.github.xadmiral.boostautotune.plugin.ecu.LiveFeed;
import io.github.xadmiral.boostautotune.plugin.mode.BoostDriver;
import io.github.xadmiral.boostautotune.plugin.mode.ModeDriver;
import io.github.xadmiral.boostautotune.plugin.mode.SweepDriver;
import io.github.xadmiral.boostautotune.plugin.mode.TableView;
import io.github.xadmiral.boostautotune.plugin.mode.TuneMode;
import io.github.xadmiral.boostautotune.plugin.mode.VvtPidDriver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Orchestrates a session of any mode against a real or simulated ECU: snapshot, plan writing,
 * live feed, run analysis, commit, safety reaction, undo. Public methods are safe to call from
 * the EDT; live samples arrive on the ECU's thread and are serialized through {@code lock}.
 */
public final class TuneController {

    /** UI callbacks; invoked on the calling thread (samples: ECU thread; the rest: caller). */
    public interface Listener {
        void log(String line);

        void stateChanged();

        void sample(Sample s, SampleState state);

        void reportReady(Object report);
    }

    private final Object lock = new Object();
    private final Listener listener;
    private EcuPort port;
    private EcuAdapter adapter;
    private ModeDriver driver;
    private LiveFeed feed;
    private SampleClassifier liveClassifier;
    private boolean feedActive;
    private boolean ecuPrepared;
    private boolean autoEndRun;
    private boolean autoApply;
    private volatile Sample lastSample;
    private volatile SampleState lastState;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss");

    public TuneController(Listener listener) {
        this.listener = listener;
    }

    public void setPort(EcuPort port) {
        synchronized (lock) {
            stopFeed();
            this.port = port;
            this.adapter = null;
            this.driver = null;
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

    public ModeDriver driver() {
        return driver;
    }

    public TuneMode mode() {
        return driver == null ? null : driver.mode();
    }

    /** The boost session when the current mode is boost, otherwise null. */
    public AutotuneSession session() {
        return driver instanceof BoostDriver ? ((BoostDriver) driver).session() : null;
    }

    /** The boost undo copy when the current mode is boost, otherwise null. */
    public EcuState original() {
        return driver instanceof BoostDriver ? ((BoostDriver) driver).original() : null;
    }

    public boolean hasOriginal() {
        return driver != null && driver.hasOriginal();
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
        ModeDriver d = driver;
        return d == null ? SessionState.IDLE : d.state();
    }

    public String planTitle() {
        return driver == null ? "" : driver.planTitle();
    }

    public List<String> planNotes() {
        return driver == null ? Collections.<String>emptyList() : driver.planNotes();
    }

    public String instructions() {
        return driver == null ? "" : driver.instructions();
    }

    public String phase() {
        return driver == null ? "" : driver.phase();
    }

    public String abortReason() {
        return driver == null ? null : driver.abortReason();
    }

    public int pullsInRun() {
        return driver == null ? 0 : driver.pullsInRun();
    }

    public double runPeak() {
        return driver == null ? Double.NaN : driver.runPeak();
    }

    public String lastReportText() {
        return driver == null ? "" : driver.lastReportText();
    }

    public String analysisText() {
        return driver == null ? "" : driver.analysisText();
    }

    public List<TableView> tables() {
        return driver == null ? Collections.<TableView>emptyList() : driver.tables();
    }

    // ---- session start per mode ----------------------------------------------------------------

    public void startSession(AutotuneConfig cfg, EcuBinding binding) throws EcuException {
        synchronized (lock) {
            begin(binding);
            BoostDriver d = new BoostDriver(adapter, cfg);
            install(d, d.startupLog());
        }
    }

    public void startVvtPidSession(VvtPidConfig cfg, EcuBinding binding) throws EcuException {
        synchronized (lock) {
            begin(binding);
            VvtPidDriver d = new VvtPidDriver(adapter, cfg);
            install(d, d.startupLog());
        }
    }

    public void startSweepSession(SweepConfig cfg, EcuBinding binding) throws EcuException {
        synchronized (lock) {
            begin(binding);
            SweepDriver d = new SweepDriver(adapter, cfg);
            install(d, d.startupLog());
        }
    }

    private void begin(EcuBinding binding) throws EcuException {
        if (port == null) {
            throw new EcuException("No ECU connection");
        }
        stopFeed();
        adapter = new EcuAdapter(port, binding.copy());
        liveClassifier = null;
    }

    private void install(ModeDriver d, List<String> startupLog) {
        driver = d;
        ecuPrepared = false;
        log("Session started (" + d.mode().label() + ") on '" + adapter.config() + "'. Undo copy taken.");
        for (String l : startupLog) {
            log(l);
        }
        listener.stateChanged();
    }

    // ---- run control -------------------------------------------------------------------------

    /** Writes the current plan to the ECU (RAM). */
    public void writePlanToEcu() throws EcuException {
        synchronized (lock) {
            requireSession();
            driver.writePlan();
            ecuPrepared = true;
            log("Written to ECU: " + driver.planTitle());
            listener.stateChanged();
        }
    }

    public void startRun() throws EcuException {
        synchronized (lock) {
            requireSession();
            if (!ecuPrepared) {
                throw new EcuException("Write the plan to the ECU first");
            }
            driver.startRun();
            startFeed();
            log("Recording. " + driver.instructions());
            listener.stateChanged();
        }
    }

    /** Ends the run; returns the mode-specific report object. */
    public Object endRun() throws EcuException {
        Object report;
        synchronized (lock) {
            requireSession();
            stopFeed();
            if (driver.state() != SessionState.RECORDING) {
                throw new EcuException("No run in progress");
            }
            report = driver.endRun();
            log(driver.lastReportText());
            listener.stateChanged();
        }
        listener.reportReady(report);
        if (autoApply && driver.state() == SessionState.REVIEW) {
            applyAndPrepareNext();
        }
        return report;
    }

    /** Commits the report's plan and writes it to the ECU. */
    public void applyAndPrepareNext() throws EcuException {
        synchronized (lock) {
            requireSession();
            driver.commitAndWrite();
            ecuPrepared = true;
            if (driver.state() == SessionState.DONE) {
                log("Final result written to ECU RAM. Review it in TunerStudio and burn.");
            } else {
                log("Written to ECU: " + driver.planTitle());
            }
            listener.stateChanged();
        }
    }

    public void repeatRun() throws EcuException {
        synchronized (lock) {
            requireSession();
            driver.repeatRun();
            log("Repeating: " + driver.planTitle());
            listener.stateChanged();
        }
    }

    public void abort(String reason) {
        synchronized (lock) {
            stopFeed();
            if (driver != null) {
                driver.abort(reason);
            }
            log("Aborted: " + reason);
            listener.stateChanged();
        }
    }

    /** Puts the tuned settings back to what they were when the session started. */
    public void restoreOriginal() throws EcuException {
        synchronized (lock) {
            if (driver == null || !driver.hasOriginal()) {
                throw new EcuException("No undo copy available");
            }
            stopFeed();
            driver.restoreOriginal();
            ecuPrepared = false;
            log("Original settings restored");
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
    public Object analyzeLog(File file, LogColumnMapping mapping) throws EcuException, IOException {
        Object report;
        synchronized (lock) {
            requireSession();
            if (driver.state() != SessionState.READY) {
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
            driver.startRun();
            for (Sample s : r.samples) {
                driver.onSample(s);
                if (driver.state() == SessionState.ABORTED) {
                    break;
                }
            }
            if (driver.state() == SessionState.ABORTED) {
                log("Log analysis stopped: " + driver.abortReason());
                listener.stateChanged();
                return null;
            }
            report = driver.endRun();
            log(driver.lastReportText());
            listener.stateChanged();
        }
        listener.reportReady(report);
        return report;
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
        boolean finish = false;
        boolean aborted = false;
        String reason = null;
        synchronized (lock) {
            if (driver == null || driver.state() != SessionState.RECORDING) {
                return;
            }
            driver.onSample(s);
            lastSample = s;
            lastState = classify(s);
            if (driver.state() == SessionState.ABORTED) {
                aborted = true;
                reason = driver.abortReason();
            } else if (autoEndRun && driver.runLooksFinished()) {
                finish = true;
            }
        }
        listener.sample(s, lastState);
        if (aborted) {
            onSafetyAbort(reason);
            return;
        }
        if (finish) {
            try {
                log("Run finished automatically (no WOT for a while)");
                endRun();
            } catch (EcuException e) {
                log("Auto end failed: " + e.getMessage());
            }
        }
    }

    private SampleState classify(Sample s) {
        if (driver instanceof BoostDriver) {
            AutotuneSession bs = ((BoostDriver) driver).session();
            if (liveClassifier == null) {
                liveClassifier = new SampleClassifier(bs.config());
            }
        } else if (liveClassifier == null) {
            liveClassifier = new SampleClassifier(new AutotuneConfig());
        }
        return liveClassifier.classify(s);
    }

    private void onSafetyAbort(final String reason) {
        // The session is already ABORTED; the ECU write goes to a separate thread so TunerStudio's
        // own communication thread is never re-entered from its channel callback.
        log("SAFETY ABORT: " + reason + " - writing safe state");
        final ModeDriver d = driver;
        ecuPrepared = false;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    stopFeed();
                    try {
                        d.writeSafeState();
                        log("Safe state written");
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
        if (driver == null) {
            throw new EcuException("Start a session first");
        }
    }

    private void log(String s) {
        listener.log(time.format(new Date()) + "  " + (driver == null ? "" : driver.logPrefix()) + s);
    }
}
