package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPresets;
import io.github.xadmiral.boostautotune.plugin.ecu.TableOrientation;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** ECU connection and channel / parameter mapping. */
public final class SetupPanel extends JPanel {

    /** One editable line of the binding. */
    private static final class Row {
        final String key;
        final String label;
        final String kind; // channel | param | text | load | orient | bool

        Row(String key, String label, String kind) {
            this.key = key;
            this.label = label;
            this.kind = kind;
        }
    }

    private static final Row[] ROWS = {
            new Row("rpmChannel", "RPM channel", "channel"),
            new Row("tpsChannel", "TPS channel", "channel"),
            new Row("mapChannel", "MAP channel (kPa)", "channel"),
            new Row("targetChannel", "Boost target channel (kPa)", "channel"),
            new Row("dutyChannel", "Boost duty channel (%)", "channel"),
            new Row("cltChannel", "Coolant channel", "channel"),
            new Row("cltFahrenheit", "Coolant channel is in °F", "bool"),
            new Row("gearChannel", "Gear channel (optional)", "channel"),
            new Row("boostCutChannel", "Boost cut / overboost channel (optional)", "channel"),
            new Row("boostCutMask", "Boost cut bit mask (0 = non-zero)", "text"),
            new Row("timeChannel", "Time channel (demo only, leave empty for a real ECU)", "channel"),
            new Row("targetTable", "Target table (Z)", "param"),
            new Row("targetXBins", "Target table RPM bins (X)", "param"),
            new Row("targetYBins", "Target table load bins (Y)", "param"),
            new Row("targetLoadSource", "Target table Y axis source", "load"),
            new Row("biasTable", "Bias / closed-loop duty table (Z)", "param"),
            new Row("biasXBins", "Bias table RPM bins (X)", "param"),
            new Row("biasYBins", "Bias table target-boost bins (Y)", "param"),
            new Row("openLoopTable", "Open-loop duty table (Z)", "param"),
            new Row("openLoopXBins", "Open-loop RPM bins (X)", "param"),
            new Row("openLoopYBins", "Open-loop load bins (Y)", "param"),
            new Row("openLoopLoadSource", "Open-loop Y axis source", "load"),
            new Row("pidP", "P gain", "param"),
            new Row("pidI", "I gain", "param"),
            new Row("pidD", "D gain", "param"),
            new Row("minDuty", "Minimum duty", "param"),
            new Row("maxDuty", "Maximum duty", "param"),
            new Row("overboostLimit", "ECU overboost cut limit (read only)", "param"),
            new Row("closedLoopWindowParam", "Closed-loop window / lower limit delta, kPa (optional, fast spool)", "param"),
            new Row("enableParam", "Boost control enable parameter", "param"),
            new Row("enableOption", "  ... option meaning ON", "text"),
            new Row("modeParam", "Open / closed loop switch", "param"),
            new Row("openLoopOption", "  ... open-loop option text", "text"),
            new Row("closedLoopOption", "  ... closed-loop option text", "text"),
            new Row("closedLoopExtraParam", "Extra parameter set when closing the loop", "param"),
            new Row("closedLoopExtraOption", "  ... its option text", "text"),
            new Row("fuelLoadChannel", "Fuel load channel (VVT table Y axis)", "channel"),
            new Row("vvtAngleChannel", "VVT cam angle channel", "channel"),
            new Row("vvtTargetChannel", "VVT target channel", "channel"),
            new Row("vvtTable", "VVT target table (Z)", "param"),
            new Row("vvtXBins", "VVT table RPM bins (X)", "param"),
            new Row("vvtYBins", "VVT table load bins (Y)", "param"),
            new Row("vvtLoadSource", "VVT table Y axis source", "load"),
            new Row("vvtPidP", "VVT P gain", "param"),
            new Row("vvtPidI", "VVT I gain", "param"),
            new Row("vvtPidD", "VVT D gain", "param"),
            new Row("ignLoadChannel", "Ignition load channel (spark table Y axis)", "channel"),
            new Row("advanceChannel", "Ignition advance channel", "channel"),
            new Row("knockRetardChannel", "Knock retard channel (deg)", "channel"),
            new Row("knockChannel", "Knock level channel (optional)", "channel"),
            new Row("afrChannel", "AFR channel (optional guard)", "channel"),
            new Row("sparkTable", "Spark advance table (Z)", "param"),
            new Row("sparkXBins", "Spark table RPM bins (X)", "param"),
            new Row("sparkYBins", "Spark table load bins (Y)", "param"),
            new Row("sparkLoadSource", "Spark table Y axis source", "load"),
            new Row("alsActiveChannel", "Anti-lag active channel (status bits)", "channel"),
            new Row("alsActiveMask", "Anti-lag active bit mask (0 = non-zero)", "text"),
            new Row("matChannel", "Intake air temperature channel (°C)", "channel"),
            new Row("alsTimingTable", "Anti-lag timing table (Z)", "param"),
            new Row("alsXBins", "Anti-lag table RPM bins (X)", "param"),
            new Row("alsYBins", "Anti-lag table TPS bins (Y)", "param"),
            new Row("alsAirStepsParam", "Anti-lag idle valve steps (stepper)", "param"),
            new Row("alsAirDutyParam", "Anti-lag idle valve duty (PWM)", "param"),
            new Row("idleTypeParam", "Idle valve type parameter", "param"),
            new Row("idleTypeStepperOption", "  ... option meaning stepper", "text"),
            new Row("alsEnableParam", "Anti-lag enable / input parameter", "param"),
            new Row("alsDisableOption", "  ... option meaning OFF", "text"),
            new Row("alsMaxTimeParam", "Anti-lag max time per activation, s (optional)", "param"),
            new Row("alsMinRpmParam", "Anti-lag cut-off RPM (optional)", "param"),
            new Row("alsAirDbwParam", "Anti-lag throttle opening, % TPS (drive-by-wire)", "param"),
            new Row("dbwEnableParam", "Drive-by-wire enable parameter", "param"),
            new Row("dbwEnableOption", "  ... option meaning ON", "text"),
            new Row("alsMaxTpsParam", "Anti-lag operate-below TPS parameter (optional)", "param"),
            new Row("knockThresholdTable", "Knock threshold curve (Y values)", "param"),
            new Row("knockRpmBins", "Knock threshold RPM bins (X)", "param"),
            new Row("knockGainPrefix", "Knock gain parameter prefix (knock_gain -> knock_gain01...)", "text"),
            new Row("knockCylChannelPrefix", "Knock per-cylinder channel prefix (knock_cyl -> knock_cyl01...)", "text"),
            new Row("cylindersParam", "Cylinder count parameter", "param"),
            new Row("knockPerCylParam", "Knock per-cylinder parameter", "param"),
            new Row("knockPerCylOnOption", "  ... option meaning ON", "text"),
            new Row("knockControlParam", "Knock control parameter (read only)", "param"),
            new Row("knockControlOffOption", "  ... option meaning DISABLED", "text"),
            new Row("knockMinLoadParam", "Knock minimum load parameter (optional)", "param"),
            new Row("knockLoRpmParam", "Knock RPM window low parameter (optional)", "param"),
            new Row("knockHiRpmParam", "Knock RPM window high parameter (optional)", "param"),
            new Row("orientation", "Table orientation", "orient"),
    };

    private final EcuBinding binding;
    private final EcuPort port;
    private final Properties props = new Properties();
    private final BindingModel model = new BindingModel();
    private final JTable table = new JTable(model);
    private final JComboBox<String> configCombo = new JComboBox<String>();
    private final JComboBox<String> presetCombo = new JComboBox<String>();
    private final JTextArea output = new JTextArea(12, 60);
    private final JLabel signatureLabel = new JLabel();
    private List<String> channels = new ArrayList<String>();
    private List<String> params = new ArrayList<String>();
    private final Runnable onChanged;
    private JPanel top;

    /** Adds a control to the row of buttons at the top (the main panel puts the text size choice there). */
    public void addTopControl(java.awt.Component c) {
        top.add(c);
        top.revalidate();
    }

    public SetupPanel(EcuBinding binding, EcuPort port, Runnable onChanged) {
        super(new BorderLayout(6, 6));
        this.binding = binding;
        this.port = port;
        this.onChanged = onChanged;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        top = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        top.add(new JLabel("ECU configuration:"));
        top.add(configCombo);
        top.add(new JLabel("Preset:"));
        for (String n : EcuPresets.names()) {
            presetCombo.addItem(n);
        }
        top.add(presetCombo);
        JButton apply = new JButton("Load preset");
        JButton detect = new JButton("Auto-detect");
        JButton validate = new JButton("Validate");
        JButton preview = new JButton("Read tables");
        top.add(apply);
        top.add(detect);
        top.add(validate);
        top.add(preview);
        add(top, BorderLayout.NORTH);

        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(output));
        split.setResizeWeight(0.65);
        add(split, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        bottom.add(signatureLabel);
        add(bottom, BorderLayout.SOUTH);

        apply.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                loadPreset((String) presetCombo.getSelectedItem());
            }
        });
        detect.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                autoDetect();
            }
        });
        validate.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                validateBinding();
            }
        });
        preview.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                previewTables();
            }
        });
        configCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Object sel = configCombo.getSelectedItem();
                if (sel != null) {
                    SetupPanel.this.binding.configName = sel.toString();
                    refreshNames();
                }
            }
        });
        reloadFromBinding();
        refreshConfigs();
    }

    private void refreshConfigs() {
        configCombo.removeAllItems();
        if (port == null) {
            return;
        }
        signatureLabel.setText("Signature: " + port.signature());
        for (String c : port.configurationNames()) {
            configCombo.addItem(c);
        }
        if (binding.has(binding.configName)) {
            configCombo.setSelectedItem(binding.configName);
        }
        refreshNames();
    }

    private void refreshNames() {
        if (port == null) {
            return;
        }
        String cfg = currentConfig();
        channels = new ArrayList<String>(port.channelNames(cfg));
        params = new ArrayList<String>(port.parameterNames(cfg));
        java.util.Collections.sort(channels, String.CASE_INSENSITIVE_ORDER);
        java.util.Collections.sort(params, String.CASE_INSENSITIVE_ORDER);
        model.fireTableDataChanged();
    }

    private String currentConfig() {
        Object sel = configCombo.getSelectedItem();
        if (sel != null) {
            return sel.toString();
        }
        List<String> names = port.configurationNames();
        return names.isEmpty() ? "" : names.get(0);
    }

    public void reloadFromBinding() {
        props.clear();
        binding.store(props, "");
        model.fireTableDataChanged();
        presetCombo.setSelectedItem(binding.presetName);
    }

    /** Pushes table edits into the binding object. */
    public void commit() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        binding.load(props, "");
        binding.configName = currentConfig();
        onChanged.run();
    }

    private void loadPreset(String name) {
        EcuBinding p = EcuPresets.create(name);
        p.configName = currentConfig();
        Properties tmp = new Properties();
        p.store(tmp, "");
        props.clear();
        props.putAll(tmp);
        binding.load(props, "");
        model.fireTableDataChanged();
        say("Preset '" + name + "' loaded. Validate to check every name against the ECU definition.");
        onChanged.run();
    }

    private void autoDetect() {
        if (port == null) {
            say("No ECU connection");
            return;
        }
        String name = EcuPresets.detect(port.signature(), params, channels);
        loadPreset(name);
        // refine from the INI table definitions when TunerStudio exposes them
        List<EcuPort.UiTableInfo> tables = port.uiTables(currentConfig());
        int refined = 0;
        for (EcuPort.UiTableInfo t : tables) {
            if (t.zParam == null) {
                continue;
            }
            if (t.zParam.equals(binding.targetTable)) {
                binding.targetXBins = t.xParam;
                binding.targetYBins = t.yParam;
                refined++;
            } else if (t.zParam.equals(binding.biasTable)) {
                binding.biasXBins = t.xParam;
                binding.biasYBins = t.yParam;
                refined++;
            } else if (t.zParam.equals(binding.openLoopTable)) {
                binding.openLoopXBins = t.xParam;
                binding.openLoopYBins = t.yParam;
                refined++;
            } else if (t.zParam.equals(binding.vvtTable)) {
                binding.vvtXBins = t.xParam;
                binding.vvtYBins = t.yParam;
                refined++;
            } else if (t.zParam.equals(binding.sparkTable)) {
                binding.sparkXBins = t.xParam;
                binding.sparkYBins = t.yParam;
                refined++;
            } else if (t.zParam.equals(binding.alsTimingTable)) {
                binding.alsXBins = t.xParam;
                binding.alsYBins = t.yParam;
                refined++;
            }
        }
        // fuzzy fallbacks for channels that differ between INI versions
        if (!channels.contains(binding.cltChannel)) {
            String alt = EcuPresets.find(channels, "coolant");
            if (alt.isEmpty()) {
                alt = EcuPresets.find(channels, "clt");
            }
            if (!alt.isEmpty()) {
                binding.cltChannel = alt;
            }
        }
        reloadFromBinding();
        StringBuilder sb = new StringBuilder("Detected preset: " + name + " (signature '" + port.signature() + "')\n");
        if (refined > 0) {
            sb.append(refined).append(" table axis bindings taken from the INI table definitions\n");
        }
        if (!tables.isEmpty()) {
            sb.append("Boost / VVT / spark / anti-lag tables in this INI:\n");
            for (EcuPort.UiTableInfo t : tables) {
                String l = t.toString().toLowerCase();
                if (l.contains("boost") || l.contains("vvt") || l.contains("spark") || l.contains("ignition") || l.contains("adv")
                        || l.contains("als") || l.contains("anti") || l.contains("lag")) {
                    sb.append("  ").append(t).append('\n');
                }
            }
        }
        say(sb.toString());
        validateBinding();
        onChanged.run();
    }

    public List<String> validateBinding() {
        return validateFor(io.github.xadmiral.boostautotune.plugin.mode.TuneMode.BOOST);
    }

    /** Validates the part of the binding a mode needs and shows the result. */
    public List<String> validateFor(io.github.xadmiral.boostautotune.plugin.mode.TuneMode mode) {
        commit();
        List<String> ch = port == null ? null : channels;
        List<String> pa = port == null ? null : params;
        List<String> problems;
        switch (mode) {
            case VVT_PID:
                problems = binding.validateVvt(ch, pa, true);
                break;
            case VVT_SWEEP:
                problems = binding.validateVvt(ch, pa, false);
                break;
            case IGNITION_SWEEP:
                problems = binding.validateIgnition(ch, pa);
                break;
            case ANTILAG:
                problems = binding.validateAntilag(ch, pa);
                break;
            case KNOCK_CAL:
                problems = binding.validateKnock(ch, pa);
                break;
            default:
                problems = binding.validate(ch, pa);
        }
        StringBuilder sb = new StringBuilder();
        if (problems.isEmpty()) {
            sb.append("Binding OK for ").append(mode.label()).append(".\n");
            if (!binding.hasBiasTable()) {
                sb.append("No bias table: only PID gains and targets will be tuned.\n");
            }
            if (!binding.hasOpenLoopTable()) {
                sb.append("No open-loop duty table: characterization runs are skipped, learning happens in closed loop only.\n");
            }
        } else {
            sb.append("Problems:\n");
            for (String p : problems) {
                sb.append("  - ").append(p).append('\n');
            }
        }
        say(sb.toString());
        model.fireTableDataChanged();
        return problems;
    }

    private void previewTables() {
        commit();
        if (port == null) {
            say("No ECU connection");
            return;
        }
        try {
            EcuAdapter adapter = new EcuAdapter(port, binding.copy());
            List<String> warnings = new ArrayList<String>();
            io.github.xadmiral.boostautotune.core.session.EcuState s = adapter.read(warnings);
            StringBuilder sb = new StringBuilder();
            for (String w : warnings) {
                sb.append("WARNING: ").append(w).append('\n');
            }
            sb.append("Compare these with the TunerStudio table editors. If rows and columns look swapped, change 'Table orientation'.\n\n");
            sb.append("Target table ").append(binding.targetTable).append(" (layout ").append(adapter.targetLayout()).append(")\n");
            sb.append(s.targetTable.toText("%7.1f")).append('\n');
            if (s.biasTable != null) {
                sb.append("Bias table ").append(binding.biasTable).append(" (layout ").append(adapter.biasLayout()).append(")\n");
                sb.append(s.biasTable.toText("%7.1f")).append('\n');
            }
            if (s.openLoopTable != null) {
                sb.append("Open-loop duty table ").append(binding.openLoopTable).append('\n');
                sb.append(s.openLoopTable.toText("%7.1f")).append('\n');
            }
            sb.append("PID ").append(s.pid).append(", duty ").append(Form.fmt(s.minDuty)).append("-").append(Form.fmt(s.maxDuty))
                    .append("%, closed loop: ").append(s.closedLoop).append('\n');
            double ob = adapter.readOverboostLimit();
            if (!Double.isNaN(ob)) {
                sb.append("ECU overboost cut: ").append(Form.fmt(ob)).append(" kPa\n");
            }
            say(sb.toString());
        } catch (EcuException e) {
            say("Read failed: " + e.getMessage());
        } catch (RuntimeException e) {
            say("Read failed: " + e);
        }
    }

    private void say(String s) {
        output.setText(s);
        output.setCaretPosition(0);
    }

    private final class BindingModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return ROWS.length;
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 ? "Item" : c == 1 ? "Name / value" : "Status";
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 1;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = ROWS[r];
            String v = props.getProperty(row.key, "");
            if (c == 0) {
                return row.label;
            }
            if (c == 1) {
                return v;
            }
            if (v.isEmpty()) {
                return row.kind.equals("channel") || row.kind.equals("param") ? "not used" : "";
            }
            if (row.kind.equals("channel")) {
                return channels.isEmpty() ? "?" : channels.contains(v) ? "ok" : "MISSING";
            }
            if (row.kind.equals("param")) {
                return params.isEmpty() ? "?" : params.contains(v) ? "ok" : "MISSING";
            }
            return "";
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            props.setProperty(ROWS[r].key, value == null ? "" : value.toString().trim());
            fireTableRowsUpdated(r, r);
        }
    }

    {
        table.getColumnModel().getColumn(1).setCellEditor(new RowEditor());
    }

    /** Picks an editor per row: combo boxes for channels, parameters, enums; text otherwise. */
    private final class RowEditor extends javax.swing.AbstractCellEditor implements javax.swing.table.TableCellEditor {
        private TableCellEditor delegate;

        @Override
        public Object getCellEditorValue() {
            return delegate.getCellEditorValue();
        }

        @Override
        public java.awt.Component getTableCellEditorComponent(JTable t, Object value, boolean selected, int r, int c) {
            Row row = ROWS[r];
            JComboBox<String> combo = null;
            if (row.kind.equals("channel")) {
                combo = combo(channels, true);
            } else if (row.kind.equals("param")) {
                combo = combo(params, true);
            } else if (row.kind.equals("load")) {
                List<String> names = new ArrayList<String>();
                for (io.github.xadmiral.boostautotune.core.model.LoadSource l : io.github.xadmiral.boostautotune.core.model.LoadSource.values()) {
                    names.add(l.name());
                }
                combo = combo(names, false);
            } else if (row.kind.equals("orient")) {
                List<String> names = new ArrayList<String>();
                for (TableOrientation o : TableOrientation.values()) {
                    names.add(o.name());
                }
                combo = combo(names, false);
            } else if (row.kind.equals("bool")) {
                combo = combo(Arrays.asList("false", "true"), false);
            }
            if (combo != null) {
                combo.setEditable(true);
                delegate = new DefaultCellEditor(combo);
            } else {
                delegate = new DefaultCellEditor(new javax.swing.JTextField());
            }
            return delegate.getTableCellEditorComponent(t, value, selected, r, c);
        }

        private JComboBox<String> combo(List<String> items, boolean allowEmpty) {
            JComboBox<String> cb = new JComboBox<String>();
            if (allowEmpty) {
                cb.addItem("");
            }
            for (String s : items) {
                cb.addItem(s);
            }
            return cb;
        }

        @Override
        public boolean stopCellEditing() {
            boolean ok = delegate == null || delegate.stopCellEditing();
            if (ok) {
                fireEditingStopped();
            }
            return ok;
        }
    }
}
