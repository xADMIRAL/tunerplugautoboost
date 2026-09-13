package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.sweep.SweepConfig;
import io.github.xadmiral.boostautotune.core.vvt.VvtPidConfig;
import io.github.xadmiral.boostautotune.plugin.TuneController;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPresets;
import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import io.github.xadmiral.boostautotune.plugin.mode.TuneMode;
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
    private final SweepConfig vvtSweep = SweepConfig.vvtDefaults();
    private final SweepConfig ignSweep = SweepConfig.ignitionDefaults();
    private final VvtPidConfig vvtPid = new VvtPidConfig();
    private final EcuBinding binding;
    private final SettingsStore store;
    private final TuneController ctl;
    private final SetupPanel setup;
    private final TargetsPanel targets;
    private final VvtPanel vvtPanel;
    private final IgnitionPanel ignitionPanel;
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
                store.load(cfg, vvtSweep, ignSweep, vvtPid, b, new Properties());
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
        if (port instanceof SimEcuPort) {
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
        vvtPanel = new VvtPanel(vvtSweep, vvtPid, save);
        ignitionPanel = new IgnitionPanel(ignSweep, save);
        autotune = new AutotunePanel(ctl, new Runnable() {
            public void run() {
                startSession();
            }
        });
        analysis = new AnalysisPanel(ctl);
        tabs.addTab("Autotune", autotune);
        tabs.addTab("Boost", targets);
        tabs.addTab("VVT", vvtPanel);
        tabs.addTab("Ignition", ignitionPanel);
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

    public SweepConfig vvtSweepConfig() {
        return vvtSweep;
    }

    public SweepConfig ignitionSweepConfig() {
        return ignSweep;
    }

    public VvtPidConfig vvtPidConfig() {
        return vvtPid;
    }

    public EcuBinding binding() {
        return binding;
    }

    public TuneController controller() {
        return ctl;
    }

    public AutotunePanel autotunePanel() {
        return autotune;
    }

    private void saveSettings() {
        if (store == null) {
            return;
        }
        try {
            store.save(cfg, vvtSweep, ignSweep, vvtPid, binding, new Properties());
        } catch (IOException e) {
            logPanel.append("Could not save settings: " + e.getMessage());
        }
    }

    private void startSession() {
        TuneMode mode = autotune.selectedMode();
        JPanel settingsTab;
        boolean ok;
        switch (mode) {
            case VVT_PID:
            case VVT_SWEEP:
                settingsTab = vvtPanel;
                ok = vvtPanel.apply();
                break;
            case IGNITION_SWEEP:
                settingsTab = ignitionPanel;
                ok = ignitionPanel.apply();
                break;
            default:
                settingsTab = targets;
                ok = targets.apply();
        }
        if (!ok) {
            tabs.setSelectedComponent(settingsTab);
            JOptionPane.showMessageDialog(this, "Fix the settings of this mode first.", "Boost Autotune", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> problems = setup.validateFor(mode);
        if (!problems.isEmpty()) {
            tabs.setSelectedComponent(setup);
            JOptionPane.showMessageDialog(this, "Fix the ECU binding first:\n" + problems.get(0), "Boost Autotune",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        String text;
        switch (mode) {
            case VVT_PID:
                text = "Start a VVT PID session?\n\nThe plugin will write VVT gains to the ECU between runs; a copy is kept for 'Restore original'.";
                break;
            case VVT_SWEEP:
                text = "Start a VVT target sweep?\n\nCandidates " + vvtSweep.candidateOffsets + " deg on rows with load >= " + vvtSweep.minLoad
                        + ".\nThe plugin writes the VVT table between runs; a copy is kept for 'Restore original'.";
                break;
            case IGNITION_SWEEP:
                if (!ignitionPanel.acknowledged()) {
                    tabs.setSelectedComponent(ignitionPanel);
                    JOptionPane.showMessageDialog(this, "Tick the acknowledgement on the Ignition tab first.", "Boost Autotune",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                text = "START AN IGNITION ADVANCE SWEEP?\n\nCandidates " + ignSweep.candidateOffsets + " deg on rows with load >= " + ignSweep.minLoad
                        + " kPa, never more than +" + ignSweep.maxAdvanceOverOriginalDeg + " deg over the original table.\n"
                        + "Knock retard >= " + ignSweep.knockRetardTriggerDeg + " deg caps a cell, >= " + ignSweep.knockAbortRetardDeg
                        + " deg aborts and restores the original table.\n"
                        + "The ECU's own knock control must be enabled and a wideband must be connected.";
                break;
            default:
                text = "Start a boost autotune session?\n\n"
                        + "Targets: " + cfg.targetStagesKpa + " kPa, hard limit " + cfg.maxBoostKpa + " kPa.\n"
                        + "The plugin will write boost tables and gains to the ECU between runs.\n"
                        + "A copy of the current boost settings is kept for 'Restore original'.\n"
                        + "Keep a hand on the throttle: lift if boost runs away.";
        }
        int r = JOptionPane.showConfirmDialog(this, text, "Boost Autotune", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            switch (mode) {
                case VVT_PID:
                    ctl.startVvtPidSession(vvtPid, binding);
                    break;
                case VVT_SWEEP:
                    ctl.startSweepSession(vvtSweep, binding);
                    break;
                case IGNITION_SWEEP:
                    ctl.startSweepSession(ignSweep, binding);
                    break;
                default:
                    ctl.startSession(cfg, binding);
            }
            analysis.refresh();
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
    public void reportReady(Object report) {
        onEdt(new Runnable() {
            public void run() {
                autotune.showReport();
                analysis.refresh();
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
