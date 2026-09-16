package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.als.AlsConfig;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;
import io.github.xadmiral.boostautotune.plugin.mode.AntilagPresets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anti-lag tab: ready-made drift presets (anti-lag, flat shift, over-run) written straight to the
 * ECU with an undo copy, plus the settings of the anti-lag autotune.
 */
public final class AntilagPanel extends JPanel {
    /** Where the panel reports what it wrote. */
    public interface Log {
        void line(String s);
    }

    private static final String[] COLS = {"Group", "Parameter", "Value to write", "Current in ECU", "Note"};

    private static final class Row {
        final AntilagPresets.Setting setting;
        String value;
        String current = "";
        boolean missing;

        Row(AntilagPresets.Setting s) {
            setting = s;
            value = s.value;
        }
    }

    private final class PresetModel extends AbstractTableModel {
        final List<Row> rows = new ArrayList<Row>();

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 2;
        }

        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            switch (c) {
                case 0:
                    return row.setting.group;
                case 1:
                    return row.setting.param;
                case 2:
                    return row.value;
                case 3:
                    return row.missing ? "(not in this INI)" : row.current;
                default:
                    return row.setting.note;
            }
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 2) {
                rows.get(r).value = v == null ? "" : v.toString().trim();
                fireTableCellUpdated(r, c);
            }
        }
    }

    private final AlsConfig cfg;
    private final EcuBinding binding;
    private final EcuPort port;
    private final Runnable onChanged;
    private final Log log;
    private final Form form = new Form();
    private final Form goals = new Form();
    private final JCheckBox advanced = new JCheckBox("Advanced settings", false);
    private JScrollPane advancedScroll;
    private final JComboBox<String> presetCombo = new JComboBox<String>();
    private final JCheckBox groupAls = new JCheckBox(AntilagPresets.GROUP_ALS, true);
    private final JCheckBox groupFlat = new JCheckBox(AntilagPresets.GROUP_FLATSHIFT, true);
    private final JCheckBox groupOverrun = new JCheckBox(AntilagPresets.GROUP_OVERRUN, true);
    private final PresetModel model = new PresetModel();
    private final JTable table = new JTable(model);
    private final JLabel presetStatus = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JButton restoreBtn = new JButton("Restore original");
    /** Value of every parameter before the first write from this tab, for undo. */
    private final Map<String, String> originals = new LinkedHashMap<String, String>();

    public AntilagPanel(final AlsConfig cfg, EcuBinding binding, EcuPort port, Runnable onChanged, Log log) {
        super(new BorderLayout(6, 6));
        this.cfg = cfg;
        this.binding = binding;
        this.port = port;
        this.onChanged = onChanged;
        this.log = log;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JTextArea intro = new JTextArea(
                "Pick a drift mode (light / medium / hard): it fills the MS3 anti-lag (ALS), flat shift and over-run settings "
                        + "below and the three autotune goals. Untick a group to leave it alone, edit any value before writing "
                        + "(e.g. the ALS switch input pin). The plugin keeps the old values for 'Restore original'; burn in "
                        + "TunerStudio when happy.\n"
                        + "Autotune: with the anti-lag on, do full lifts from WOT and hold the throttle closed for the hold time. "
                        + "Ignition retard is tuned per RPM for the boost you want off throttle, idle-valve air so the engine "
                        + "does not fall below the RPM you want, and the ECU's anti-lag time is set to the seconds you want. "
                        + "Anti-lag cooks the turbo and the manifold: short runs, watch MAT, cool-down laps between runs.");
        intro.setEditable(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        intro.setBackground(getBackground());
        add(intro, BorderLayout.NORTH);

        // ---- presets ----
        JPanel presets = new JPanel(new BorderLayout(4, 4));
        presets.setBorder(BorderFactory.createTitledBorder("Drift mode: ECU settings to write (MS3 parameter names)"));
        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        presetRow.add(new JLabel("Drift mode:"));
        for (String n : AntilagPresets.names()) {
            presetCombo.addItem(n);
        }
        presetCombo.setSelectedItem(AntilagPresets.DRIFT_MEDIUM);
        presetRow.add(presetCombo);
        JButton loadBtn = new JButton("Load mode");
        presetRow.add(loadBtn);
        presetRow.add(new JLabel("  Write groups:"));
        presetRow.add(groupAls);
        presetRow.add(groupFlat);
        presetRow.add(groupOverrun);
        presets.add(presetRow, BorderLayout.NORTH);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(170);
        table.getColumnModel().getColumn(3).setPreferredWidth(170);
        table.getColumnModel().getColumn(4).setPreferredWidth(330);
        presets.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel presetButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton readBtn = new JButton("Read current from ECU");
        JButton writeBtn = new JButton("Write selected groups to ECU");
        presetButtons.add(readBtn);
        presetButtons.add(writeBtn);
        presetButtons.add(restoreBtn);
        presetButtons.add(presetStatus);
        restoreBtn.setEnabled(false);
        presets.add(presetButtons, BorderLayout.SOUTH);

        // ---- autotune settings ----
        JPanel tune = new JPanel(new BorderLayout(4, 4));
        tune.setBorder(BorderFactory.createTitledBorder("Anti-lag autotune: what you want"));
        goals.addDouble("Boost off throttle, kPa (absolute)", "Manifold pressure to hold while the anti-lag is active and the throttle is closed; tuned with ignition retard per RPM",
                new Form.DoubleGet() { public double get() { return cfg.targetKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.targetKpa = v; } });
        goals.addDouble("Do not let RPM fall below", "The anti-lag holds the engine at or above this off throttle; tuned with idle-valve air",
                new Form.DoubleGet() { public double get() { return cfg.holdRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.holdRpm = v; } });
        goals.addDouble("Hold for, s", "Seconds one activation may hold boost and RPM (written to the ECU's anti-lag time)",
                new Form.DoubleGet() { public double get() { return cfg.holdSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.holdSec = v; } });

        form.section("Tolerances");
        form.addDouble("Boost tolerance, kPa", "", new Form.DoubleGet() { public double get() { return cfg.tolKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.tolKpa = v; } });
        form.addDouble("RPM tolerance", "", new Form.DoubleGet() { public double get() { return cfg.holdTolRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.holdTolRpm = v; } });
        form.addDouble("Good runs required", "", new Form.DoubleGet() { public double get() { return cfg.runsRequired; } },
                new Form.DoubleSet() { public void set(double v) { cfg.runsRequired = (int) Math.max(1, v); } });
        form.section("Event detection");
        form.addDouble("Off throttle at or below, % TPS", "Used when the ECU does not report an ALS-active flag",
                new Form.DoubleGet() { public double get() { return cfg.offThrottleTps; } },
                new Form.DoubleSet() { public void set(double v) { cfg.offThrottleTps = v; } });
        form.addDouble("Back on throttle above, % TPS", "", new Form.DoubleGet() { public double get() { return cfg.onThrottleTps; } },
                new Form.DoubleSet() { public void set(double v) { cfg.onThrottleTps = v; } });
        form.addDouble("Min RPM", "", new Form.DoubleGet() { public double get() { return cfg.minRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minRpm = v; } });
        form.addDouble("Max RPM", "", new Form.DoubleGet() { public double get() { return cfg.maxRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxRpm = v; } });
        form.addDouble("Settle time ignored per event, s", "", new Form.DoubleGet() { public double get() { return cfg.settleSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.settleSec = v; } });
        form.addDouble("Minimum event length, s", "", new Form.DoubleGet() { public double get() { return cfg.minEventSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minEventSec = v; } });
        form.addDouble("Samples needed per RPM column", "", new Form.DoubleGet() { public double get() { return cfg.minSamplesPerColumn; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minSamplesPerColumn = v; } });
        form.section("Knobs");
        form.addDouble("Timing step per run, deg", "Adaptive: up to 3x this when far from the target",
                new Form.DoubleGet() { public double get() { return cfg.timingStepDeg; } },
                new Form.DoubleSet() { public void set(double v) { cfg.timingStepDeg = v; } });
        form.addDouble("Most retard allowed, deg (absolute)", "Negative = after TDC; the INI limit is applied as well",
                new Form.DoubleGet() { public double get() { return cfg.minTimingDeg; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minTimingDeg = v; } });
        form.addDouble("Least retard allowed, deg", "", new Form.DoubleGet() { public double get() { return cfg.maxTimingDeg; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxTimingDeg = v; } });
        form.addDouble("Change rows with TPS bin at or below", "", new Form.DoubleGet() { public double get() { return cfg.maxRowTps; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxRowTps = v; } });
        form.addBool("Tune idle valve air for the RPM hold", "Off = the air stays as it is and only the timing is tuned",
                new Form.BoolGet() { public boolean get() { return cfg.tuneAir; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.tuneAir = v; } });
        form.addDouble("Air step per run (steps or % duty)", "", new Form.DoubleGet() { public double get() { return cfg.airStep; } },
                new Form.DoubleSet() { public void set(double v) { cfg.airStep = v; } });
        form.addDouble("Air minimum", "", new Form.DoubleGet() { public double get() { return cfg.airMin; } },
                new Form.DoubleSet() { public void set(double v) { cfg.airMin = v; } });
        form.addDouble("Air maximum", "", new Form.DoubleGet() { public double get() { return cfg.airMax; } },
                new Form.DoubleSet() { public void set(double v) { cfg.airMax = v; } });
        form.section("Guards");
        form.addDouble("MAT warning, °C", "The run is not counted as good above this intake temperature",
                new Form.DoubleGet() { public double get() { return cfg.maxMatC; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxMatC = v; } });
        form.addDouble("MAT abort, °C", "Aborts the session and restores the original table",
                new Form.DoubleGet() { public double get() { return cfg.abortMatC; } },
                new Form.DoubleSet() { public void set(double v) { cfg.abortMatC = v; } });
        form.addDouble("Stall guard, RPM", "", new Form.DoubleGet() { public double get() { return cfg.stallRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.stallRpm = v; } });
        form.addDouble("Overboost abort, kPa", "", new Form.DoubleGet() { public double get() { return cfg.maxBoostKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxBoostKpa = v; } });
        form.addDouble("Max ALS-active seconds per run", "Heat budget; the plugin asks for a cool-down beyond it",
                new Form.DoubleGet() { public double get() { return cfg.maxActiveSecPerRun; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxActiveSecPerRun = v; } });
        form.addDouble("Re-spool target, kPa", "Boost the car must reach after the lift...",
                new Form.DoubleGet() { public double get() { return cfg.respoolTargetKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.respoolTargetKpa = v; } });
        form.addDouble("... within, s", "A re-spool this fast counts as done even if the off-throttle boost is a bit low",
                new Form.DoubleGet() { public double get() { return cfg.respoolMaxSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.respoolMaxSec = v; } });
        form.addDouble("Auto end run after idle, s", "0 = manual", new Form.DoubleGet() { public double get() { return cfg.autoEndRunIdleSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.autoEndRunIdleSec = v; } });
        JPanel goalsPanel = new JPanel(new BorderLayout());
        goalsPanel.add(goals.panel(), BorderLayout.NORTH);
        JPanel advancedRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        advancedRow.add(advanced);
        goalsPanel.add(advancedRow, BorderLayout.SOUTH);
        tune.add(goalsPanel, BorderLayout.NORTH);
        advancedScroll = new JScrollPane(form.panel());
        advancedScroll.setVisible(false);
        tune.add(advancedScroll, BorderLayout.CENTER);
        JPanel tuneButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton applyBtn = new JButton("Apply");
        tuneButtons.add(applyBtn);
        tuneButtons.add(status);
        tune.add(tuneButtons, BorderLayout.SOUTH);
        advanced.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                advancedScroll.setVisible(advanced.isSelected());
                tune.revalidate();
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, presets, tune);
        split.setResizeWeight(0.6);
        add(split, BorderLayout.CENTER);

        loadBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                loadPreset((String) presetCombo.getSelectedItem());
            }
        });
        readBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                readCurrent(true);
            }
        });
        writeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                writeSelected(true);
            }
        });
        restoreBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                restoreOriginal();
            }
        });
        applyBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                apply();
            }
        });
        form.refresh();
        goals.refresh();
        loadPreset(AntilagPresets.DRIFT_MEDIUM);
    }

    // ---- presets ------------------------------------------------------------------------------

    public void loadPreset(String name) {
        model.rows.clear();
        for (AntilagPresets.Setting s : AntilagPresets.create(name)) {
            model.rows.add(new Row(s));
        }
        model.fireTableDataChanged();
        AntilagPresets.Goals g = AntilagPresets.goals(name);
        if (g != null) {
            g.applyTo(cfg);
            goals.refresh();
            status.setText(String.format(java.util.Locale.US, "Goals from '%s': %.0f kPa, hold >= %.0f rpm for %.0f s",
                    name, g.targetKpa, g.holdRpm, g.holdSec));
            onChanged.run();
        }
        presetStatus.setText("Mode '" + name + "' loaded, nothing written yet.");
        readCurrent(false);
    }

    private EcuAdapter adapter() throws EcuException {
        if (port == null) {
            throw new EcuException("No ECU connection");
        }
        return new EcuAdapter(port, binding);
    }

    /** Fills the "current" column from the ECU. Returns the number of parameters found. */
    public int readCurrent(boolean verbose) {
        if (port == null) {
            if (verbose) {
                presetStatus.setText("No ECU connection");
            }
            return 0;
        }
        int found = 0;
        try {
            EcuAdapter a = adapter();
            for (Row r : model.rows) {
                r.missing = !a.hasParameter(r.setting.param);
                if (r.missing) {
                    r.current = "";
                    continue;
                }
                try {
                    r.current = a.readAny(r.setting.param);
                    found++;
                } catch (EcuException e) {
                    r.current = "? " + e.getMessage();
                }
            }
        } catch (EcuException e) {
            presetStatus.setText(e.getMessage());
        }
        model.fireTableDataChanged();
        if (verbose) {
            presetStatus.setText(found + " of " + model.rows.size() + " parameters read from the ECU.");
        }
        return found;
    }

    private boolean groupSelected(String group) {
        if (AntilagPresets.GROUP_ALS.equals(group)) {
            return groupAls.isSelected();
        }
        if (AntilagPresets.GROUP_FLATSHIFT.equals(group)) {
            return groupFlat.isSelected();
        }
        return groupOverrun.isSelected();
    }

    /** Writes the selected groups; returns the number of parameters written (0 on cancel or error). */
    public int writeSelected(boolean confirm) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        if (port == null) {
            presetStatus.setText("No ECU connection");
            return 0;
        }
        List<Row> todo = new ArrayList<Row>();
        for (Row r : model.rows) {
            if (groupSelected(r.setting.group) && !r.value.isEmpty()) {
                todo.add(r);
            }
        }
        if (todo.isEmpty()) {
            presetStatus.setText("Nothing selected to write.");
            return 0;
        }
        if (confirm) {
            int ok = JOptionPane.showConfirmDialog(this,
                    "Write " + todo.size() + " anti-lag / drift parameters to the ECU (RAM)?\n\n"
                            + "The old values are kept for 'Restore original'. Anti-lag makes the exhaust side very hot;\n"
                            + "make sure the turbo, manifold and clutch switch (flat shift) are up to it.",
                    "Anti-lag presets", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return 0;
            }
        }
        int written = 0;
        int skipped = 0;
        StringBuilder errors = new StringBuilder();
        try {
            EcuAdapter a = adapter();
            for (Row r : todo) {
                if (!a.hasParameter(r.setting.param)) {
                    skipped++;
                    continue;
                }
                try {
                    if (!originals.containsKey(r.setting.param)) {
                        originals.put(r.setting.param, a.readAny(r.setting.param));
                    }
                    a.writeAny(r.setting.param, r.value);
                    written++;
                    log.line("[als] " + r.setting.param + " = " + r.value);
                } catch (EcuException e) {
                    errors.append(r.setting.param).append(": ").append(e.getMessage()).append("; ");
                }
            }
        } catch (EcuException e) {
            errors.append(e.getMessage());
        }
        restoreBtn.setEnabled(!originals.isEmpty());
        readCurrent(false);
        String msg = written + " written" + (skipped > 0 ? ", " + skipped + " not in this INI" : "")
                + (errors.length() > 0 ? ", errors: " + errors : "") + ". Burn in TunerStudio to keep them.";
        presetStatus.setText(msg);
        log.line("[als] Preset '" + presetCombo.getSelectedItem() + "': " + msg);
        return written;
    }

    /** Writes back everything this tab changed. */
    public void restoreOriginal() {
        if (originals.isEmpty() || port == null) {
            return;
        }
        int restored = 0;
        StringBuilder errors = new StringBuilder();
        try {
            EcuAdapter a = adapter();
            for (Map.Entry<String, String> e : originals.entrySet()) {
                try {
                    a.writeAny(e.getKey(), e.getValue());
                    restored++;
                } catch (EcuException ex) {
                    errors.append(e.getKey()).append(": ").append(ex.getMessage()).append("; ");
                }
            }
        } catch (EcuException e) {
            errors.append(e.getMessage());
        }
        if (errors.length() == 0) {
            originals.clear();
            restoreBtn.setEnabled(false);
        }
        readCurrent(false);
        presetStatus.setText(restored + " parameters restored" + (errors.length() > 0 ? ", errors: " + errors : "") + ".");
        log.line("[als] Presets undone: " + restored + " parameters restored.");
    }

    public int presetRowCount() {
        return model.rows.size();
    }

    public boolean hasUndo() {
        return !originals.isEmpty();
    }

    // ---- autotune settings ----------------------------------------------------------------------

    public boolean apply() {
        try {
            goals.apply();
            form.apply();
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            return false;
        }
        List<String> problems = cfg.validate();
        if (!problems.isEmpty()) {
            status.setText(problems.get(0));
            return false;
        }
        status.setText("Settings applied");
        onChanged.run();
        return true;
    }

    public void refresh() {
        goals.refresh();
        form.refresh();
    }

    public boolean advancedShown() {
        return advanced.isSelected();
    }
}
