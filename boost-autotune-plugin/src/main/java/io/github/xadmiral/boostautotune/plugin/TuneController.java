package io.github.xadmiral.boostautotune.plugin;

import io.github.xadmiral.boostautotune.core.als.AlsConfig;
import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.knock.KnockCalConfig;
import io.github.xadmiral.boostautotune.core.learn.SampleClassifier;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.log.LogColumnMapping;
import io.github.xadmiral.boostautotune.core.log.MslLogReader;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.AutotuneSession;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.core.sweep.SweepConfig;
import io.github.xadmiral.boostautotune.core.vvt.VvtPidConfig;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;
import io.github.xadmiral.boostautotune.plugin.ecu.LiveFeed;
import io.github.xadmiral.boostautotune.plugin.mode.AntilagDriver;
import io.github.xadmiral.boostautotune.plugin.mode.BoostDriver;
import io.github.xadmiral.boostautotune.plugin.mode.KnockCalDriver;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates a session of any mode against a real or simulated ECU: snapshot, plan writing,
 * live feed, run analysis, commit, safety reaction, undo. Public methods are safe to call from
 * the EDT; live samples arrive on the ECU's thread and are serialized through {@code lock}.
 * <p>
 * The live feed is independent of sessions: {@link #monitor(EcuBinding)} subscribes to the
 * bound channels as soon as the plugin knows them, so the live values show whether the ECU
 * delivers data before anything is tuned. When TunerStudio's callbacks stay silent the
 * channels are polled instead; {@link #progress()} says which path is active and what is silent.
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
    private String feedConfig = "";
    private List<String> feedChannels = Collections.emptyList();
    private volatile String feedError;
    private volatile boolean feedActive;
    private Poller poller;
    private SampleClassifier liveClassifier;
    private volatile boolean safetyPending;
    private boolean ecuPrepared;
    private boolean autoEndRun;
    private boolean autoApply;
    private volatile Sample lastSample;
    private volatile SampleState lastState;
    private boolean firstSampleLogged;
    private int loggedPulls;
    private long recordingStartMs;
    private volatile int recordingSamples;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss");

    public TuneController(Listener listener) {
        this.listener = listener;
    }

    public void setPort(EcuPort port) {
        synchronized (lock) {
            stopMonitor();
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

    /** True while the live channels are subscribed (the feed runs whether or not a session exists). */
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

    // ---- live feed -----------------------------------------------------------------------------

    private String configFor(EcuBinding b) {
        if (b.has(b.configName)) {
            return b.configName;
        }
        List<String> names = port.configurationNames();
        return names.isEmpty() ? "" : names.get(0);
    }

    /**
     * Subscribes to the channels of this binding and keeps the live values flowing. Safe to call
     * again: nothing happens while the channels and the configuration are unchanged.
     */
    public void monitor(EcuBinding binding) {
        synchronized (lock) {
            if (port == null) {
                return;
            }
            EcuBinding b = binding.copy();
            String cfg = configFor(b);
            LiveFeed f = new LiveFeed(b, new LiveFeed.SampleListener() {
                @Override
                public void sample(Sample s) {
                    onSample(s);
                }
            });
            List<String> wanted = f.channels();
            if (feed != null && feedActive && cfg.equals(feedConfig) && wanted.equals(feedChannels)) {
                return;
            }
            stopMonitor();
            feed = f;
            feedConfig = cfg;
            feedChannels = wanted;
            feedError = null;
            firstSampleLogged = false;
            List<String> required = f.requiredChannels();
            List<String> subscribedNames = new ArrayList<String>();
            try {
                port.subscribe(cfg, required, f);
                subscribedNames.addAll(required);
                feedActive = true;
            } catch (EcuException e) {
                feedError = e.getMessage();
                feedActive = false;
                log("Live feed: subscribing failed: " + e.getMessage() + " (Setup -> Validate)");
            }
            int optional = 0;
            for (String ch : f.optionalChannels()) {
                try {
                    port.subscribe(cfg, Collections.singletonList(ch), f);
                    subscribedNames.add(ch);
                    optional++;
                } catch (EcuException e) {
                    // this ECU has fewer cylinders / no per-cylinder knock: fine
                }
            }
            f.setSubscribed(subscribedNames.size());
            if (feedActive) {
                log("Live feed: " + required.size() + " channels subscribed on '" + cfg + "'"
                        + (optional > 0 ? " plus " + optional + " per-cylinder knock channels" : "") + ". Waiting for values...");
            }
            poller = new Poller(f, subscribedNames.isEmpty() ? required : subscribedNames, cfg);
            poller.start();
        }
    }

    private void stopMonitor() {
        if (poller != null) {
            poller.stopPolling();
            poller = null;
        }
        if (feed != null && port != null) {
            port.unsubscribe(feed);
        }
        feed = null;
        feedActive = false;
    }

    /** Stops the live feed for good (plugin closing). */
    public void shutdown() {
        synchronized (lock) {
            stopMonitor();
        }
    }

    /** Reads the channels by polling while TunerStudio's callbacks stay silent. */
    private final class Poller extends Thread {
        private final LiveFeed target;
        private final List<String> names;
        private final String cfg;
        private volatile boolean stop;
        private boolean polling;

        Poller(LiveFeed target, List<String> names, String cfg) {
            super("boost-autotune-poll");
            this.target = target;
            this.names = names;
            this.cfg = cfg;
            setDaemon(true);
        }

        void stopPolling() {
            stop = true;
            interrupt();
        }

        @Override
        public void run() {
            EcuPort p = port;
            while (!stop && p != null) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
                if (stop) {
                    return;
                }
                if (target.secondsSinceLastCallback() < 1.0) {
                    if (polling) {
                        polling = false;
                        target.setSource("callbacks");
                        log("Live feed: TunerStudio channel callbacks resumed");
                    }
                    continue;
                }
                double[] v = p.pollChannels(cfg, names);
                if (v == null) {
                    return; // this port cannot poll (the demo streams its values)
                }
                if (!polling) {
                    polling = true;
                    target.setSource("polling");
                    log("Live feed: no channel callbacks from TunerStudio for 1 s, reading the channels by polling (20 Hz)");
                }
                target.pushPolled(names, v);
            }
        }
    }

    /** One line for the cockpit: what the feed does and where the run stands. */
    public String progress() {
        StringBuilder sb = new StringBuilder();
        LiveFeed f = feed;
        if (f == null) {
            sb.append(feedError != null ? "Live feed: " + feedError : port == null ? "No ECU connection" : "Live feed: not started");
        } else {
            LiveFeed.Status st = f.status();
            if (st.samples == 0) {
                sb.append(String.format(Locale.US, "Waiting for data from the ECU (%d channels via %s", st.subscribed, st.source));
                if (feedError != null) {
                    sb.append(", subscribe error: ").append(feedError);
                }
                sb.append(')');
                if (st.sinceStartSec > 3) {
                    sb.append(String.format(Locale.US, ": nothing for %.0f s. Is TunerStudio connected to the ECU? Setup -> Validate / Read live values", st.sinceStartSec));
                }
            } else {
                sb.append(String.format(Locale.US, "Live: %.0f samples/s via %s", st.samplesPerSec, st.source));
                if (!Double.isNaN(st.sinceLastSampleSec) && st.sinceLastSampleSec > 2) {
                    sb.append(String.format(Locale.US, ", last sample %.0f s ago", st.sinceLastSampleSec));
                }
                if (!st.silent.isEmpty()) {
                    sb.append(", silent: ").append(join(st.silent));
                }
            }
        }
        ModeDriver d = driver;
        if (d == null) {
            return sb.toString();
        }
        switch (d.state()) {
            case RECORDING: {
                double sec = (System.currentTimeMillis() - recordingStartMs) / 1000.0;
                double peak = d.runPeak();
                sb.append(String.format(Locale.US, " | Recording %.0f s, %d samples, pulls %d%s", sec, recordingSamples, d.pullsInRun(),
                        Double.isNaN(peak) ? "" : String.format(Locale.US, " (peak %.0f kPa)", peak)));
                String hint = hint(d.mode());
                if (hint != null) {
                    sb.append(": ").append(hint);
                }
                break;
            }
            case READY:
                sb.append(ecuPrepared ? " | Plan written: press Start run" : " | Press Write plan to ECU");
                break;
            case REVIEW:
                sb.append(" | Run analysed: Apply & prepare next run, or Repeat run");
                break;
            case DONE:
                sb.append(" | Done: review the tables and Burn");
                break;
            case ABORTED:
                sb.append(" | Aborted: ").append(d.abortReason());
                break;
            default:
                break;
        }
        return sb.toString();
    }

    private String hint(TuneMode mode) {
        Sample s = lastSample;
        SampleState st = lastState;
        if (s == null) {
            return "no samples yet";
        }
        if (mode == TuneMode.ANTILAG) {
            return s.alsActive ? "anti-lag active" : "waiting for a lift with the anti-lag armed";
        }
        if (mode == TuneMode.VVT_PID) {
            return "drive normally";
        }
        if (st == null) {
            return null;
        }
        switch (st) {
            case NOT_WOT: return "waiting for full throttle";
            case TRANSIENT: return "in a pull, settling";
            case STEADY: return "in a pull, learning";
            case COLD: return "coolant too cold, samples ignored";
            case RPM_OUT_OF_RANGE: return "RPM outside the window";
            case BOOST_CUT: return "BOOST CUT active";
            case OVERBOOST: return "OVERBOOST";
            default: return st.label();
        }
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(x);
        }
        return sb.toString();
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

    public void startAntilagSession(AlsConfig cfg, EcuBinding binding) throws EcuException {
        synchronized (lock) {
            begin(binding);
            AntilagDriver d = new AntilagDriver(adapter, cfg);
            install(d, d.startupLog());
        }
    }

    public void startKnockCalSession(KnockCalConfig cfg, EcuBinding binding) throws EcuException {
        synchronized (lock) {
            begin(binding);
            KnockCalDriver d = new KnockCalDriver(adapter, cfg);
            install(d, d.startupLog());
        }
    }

    private void begin(EcuBinding binding) throws EcuException {
        if (port == null) {
            throw new EcuException("No ECU connection");
        }
        adapter = new EcuAdapter(port, binding.copy());
        liveClassifier = null;
        monitor(adapter.binding());
    }

    private void install(ModeDriver d, List<String> startupLog) {
        driver = d;
        ecuPrepared = false;
        log("Session started (" + d.mode().label() + ") on '" + adapter.config() + "'. Undo copy taken.");
        for (String l : startupLog) {
            log(l);
        }
        log("Live feed: " + progress());
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
            if (feed == null) {
                monitor(adapter.binding());
            }
            if (!feedActive && feedError != null) {
                log("WARNING: the channel subscription failed (" + feedError + "); the run relies on polling the channels");
            }
            driver.startRun();
            recordingStartMs = System.currentTimeMillis();
            recordingSamples = 0;
            loggedPulls = 0;
            log("Recording. " + driver.instructions());
            listener.stateChanged();
        }
    }

    /** Ends the run; returns the mode-specific report object. */
    public Object endRun() throws EcuException {
        Object report;
        synchronized (lock) {
            requireSession();
            if (driver.state() != SessionState.RECORDING) {
                throw new EcuException("No run in progress");
            }
            log(String.format(Locale.US, "Run ended after %.0f s: %d samples, %d pull(s)",
                    (System.currentTimeMillis() - recordingStartMs) / 1000.0, recordingSamples, driver.pullsInRun()));
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
            recordingStartMs = System.currentTimeMillis();
            recordingSamples = 0;
            loggedPulls = 0;
            for (Sample s : r.samples) {
                driver.onSample(s);
                recordingSamples++;
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

    private void onSample(Sample s) {
        boolean finish = false;
        boolean aborted = false;
        String reason = null;
        SampleState state;
        synchronized (lock) {
            lastSample = s;
            state = classify(s);
            lastState = state;
            if (!firstSampleLogged) {
                firstSampleLogged = true;
                log(String.format(Locale.US, "Live data from the ECU: rpm %.0f, tps %.1f %%, map %.1f kPa, clt %.0f C", s.rpm, s.tps, s.map, s.clt));
            }
            if (driver == null || driver.state() != SessionState.RECORDING) {
                listener.sample(s, state);
                return;
            }
            driver.onSample(s);
            recordingSamples++;
            int pulls = driver.pullsInRun();
            if (pulls > loggedPulls) {
                loggedPulls = pulls;
                double peak = driver.runPeak();
                log(String.format(Locale.US, "Pull %d recorded%s", pulls, Double.isNaN(peak) ? "" : String.format(Locale.US, " (peak so far %.0f kPa)", peak)));
            }
            if (driver.state() == SessionState.ABORTED) {
                aborted = true;
                reason = driver.abortReason();
            } else if (autoEndRun && driver.runLooksFinished()) {
                finish = true;
            }
        }
        listener.sample(s, state);
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
        safetyPending = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (lock) {
                        try {
                            d.writeSafeState();
                            log("Safe state written");
                        } catch (EcuException e) {
                            log("The safe state could not be written: " + e.getMessage() + " - LIFT OFF");
                        }
                    }
                    listener.stateChanged();
                } finally {
                    safetyPending = false;
                }
            }
        }, "boost-autotune-safety");
        t.setDaemon(true);
        t.start();
    }

    /** Waits until a pending safety write has finished (tests and orderly shutdown). */
    public void awaitSafety(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (safetyPending && System.currentTimeMillis() < deadline) {
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
