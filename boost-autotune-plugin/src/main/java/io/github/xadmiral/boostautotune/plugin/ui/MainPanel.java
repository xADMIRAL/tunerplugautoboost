package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.RunReport;
import io.github.xadmiral.boostautotune.plugin.TuneController;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPresets;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Root component of the plugin: tabs plus the wiring between them and the controller. */
public final class MainPanel extends JPanel implements TuneController.Listener {
    private final AutotuneConfig cfg = new AutotuneConfig();
    private final EcuBinding binding;
    private final SettingsStore store;
    private final TuneController ctl;
    private final SetupPanel setup;
    private final TargetsPanel targets;
    private final AutotunePanel autotune;
    private final AnalysisPanel analysis;
    private final LogPanel logPanel = new LogPanel();
    private final JTabbedPane tabs = new JTabbedPane();

    public MainPanel(EcuPort port, SettingsStore store) {
        super(new BorderLayout());
        this.store = store;
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        if (store != null && store.exists()) {
            try {
                store.load(cfg, b, new Properties());
            } catch (IOException e) {
                b = EcuPresets.create(EcuPresets.STEALTH_PCM);
            }
        }
        if (port != null && !(store != null && store.exists())) {
            String guess = EcuPresets.detect(port.signature(), null, null);
            if (!EcuPresets.CUSTOM.equals(guess)) {
                b = EcuPresets.create(guess);
            }
        }
        if (port instanceof io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort) {
            b.timeChannel = "seconds";
        }
        this.binding = b;
        ctl = new TuneController(this);
        ctl.setPort(port);
        Runnable save = new Runnable() {
            public void run() {
                saveSettings();
            }
        };
        setup = new SetupPanel(binding, port, save);
        targets = new TargetsPanel(cfg, binding, port, save);
        autotune = new AutotunePanel(ctl, new Runnable() {
            public void run() {
                startSession();
            }
        });
        analysis = new AnalysisPanel(ctl);
        tabs.addTab("Autotune", autotune);
        tabs.addTab("Targets", targets);
        tabs.addTab("Setup", setup);
        tabs.addTab("Analysis", analysis);
        tabs.addTab("Log", logPanel);
        add(tabs, BorderLayout.CENTER);
        logPanel.append("Boost Autotune ready. ECU: " + (port == null ? "none" : port.signature()));
        if (store != null) {
            logPanel.append("Settings file: " + store.file());
        }
    }

    public AutotuneConfig config() {
        return cfg;
    }

    public EcuBinding binding() {
        return binding;
    }

    public TuneController controller() {
        return ctl;
    }

    private void saveSettings() {
        if (store == null) {
            return;
        }
        try {
            store.save(cfg, binding, new Properties());
        } catch (IOException e) {
            logPanel.append("Could not save settings: " + e.getMessage());
        }
    }

    private void startSession() {
        if (!targets.apply()) {
            tabs.setSelectedComponent(targets);
            JOptionPane.showMessageDialog(this, "Fix the Targets settings first.", "Boost Autotune", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> problems = setup.validateBinding();
        if (!problems.isEmpty()) {
            tabs.setSelectedComponent(setup);
            JOptionPane.showMessageDialog(this, "Fix the ECU binding first:\n" + problems.get(0), "Boost Autotune",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "Start a boost autotune session?\n\n"
                        + "Targets: " + cfg.targetStagesKpa + " kPa, hard limit " + cfg.maxBoostKpa + " kPa.\n"
                        + "The plugin will write boost tables and gains to the ECU between runs.\n"
                        + "A copy of the current boost settings is kept for 'Restore original'.\n"
                        + "Keep a hand on the throttle: lift if boost runs away.",
                "Boost Autotune", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            ctl.startSession(cfg, binding);
            analysis.showPlanOnly();
            tabs.setSelectedComponent(autotune);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Boost Autotune", JOptionPane.ERROR_MESSAGE);
        }
        autotune.updateState();
    }

    // ---- TuneController.Listener (may be called off the EDT) -----------------------------------

    @Override
    public void log(final String line) {
        onEdt(new Runnable() {
            public void run() {
                logPanel.append(line);
            }
        });
    }

    @Override
    public void stateChanged() {
        onEdt(new Runnable() {
            public void run() {
                if (autotune != null) {
                    autotune.updateState();
                }
            }
        });
    }

    @Override
    public void sample(Sample s, SampleState state) {
        // the Autotune tab polls the controller on a timer; nothing to do per sample
    }

    @Override
    public void reportReady(final RunReport report) {
        onEdt(new Runnable() {
            public void run() {
                autotune.showReport(report);
                analysis.showReport(report);
                autotune.updateState();
            }
        });
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    public void dispose() {
        ctl.abort("Plugin closed");
        autotune.dispose();
        saveSettings();
    }
}
