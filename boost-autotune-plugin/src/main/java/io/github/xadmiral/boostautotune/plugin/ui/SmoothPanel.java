package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.learn.TableSmoother;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;
import io.github.xadmiral.boostautotune.plugin.ecu.TableLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Table smoothing: pick a table (VE by default, or any other from the INI), read it from the
 * ECU, preview the smoothed version next to it with the changes coloured, write it with an undo
 * copy. The ECU interpolates between cells, so a jagged VE table makes the mixture swing on the
 * way through it; a smooth one responds the same way every time.
 */
public final class SmoothPanel extends JPanel {
    /** Where the panel reports what it wrote. */
    public interface Log {
        void line(String s);
    }

    private static final class Choice {
        final String label;
        final String z;
        final String x;
        final String y;

        Choice(String label, String z, String x, String y) {
            this.label = label;
            this.z = z;
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String CUSTOM = "Custom (type the parameter names)";

    private final EcuBinding binding;
    private final EcuPort port;
    private final Log log;
    private final JComboBox<Choice> tables = new JComboBox<Choice>();
    private final JTextField zField = new JTextField(12);
    private final JTextField xField = new JTextField(12);
    private final JTextField yField = new JTextField(12);
    private final TableSmoother.Settings settings = new TableSmoother.Settings();
    private final Form form = new Form();
    private final JComboBox<TableSmoother.Direction> direction = new JComboBox<TableSmoother.Direction>(TableSmoother.Direction.values());
    private final JLabel status = new JLabel(" ");
    private final JTextArea stats = new JTextArea(3, 60);
    private final GridTableModel currentModel = new GridTableModel();
    private final GridTableModel previewModel = new GridTableModel();
    private final JTable currentTable = new JTable(currentModel);
    private final JTable previewTable = new JTable(previewModel);
    private final JButton readBtn = new JButton("Read from ECU");
    private final JButton previewBtn = new JButton("Preview");
    private final JButton writeBtn = new JButton("Write to ECU");
    private final JButton restoreBtn = new JButton("Restore original");
    private String cfg = "";
    private String zName;
    private String xName;
    private String yName;
    private TableLayout layout;
    private Grid current;
    private Grid original;
    private TableSmoother.Result preview;
    private double tableMin = Double.NaN;
    private double tableMax = Double.NaN;
    private int tableDecimals = 1;

    public SmoothPanel(EcuBinding binding, EcuPort port, Log log) {
        super(new BorderLayout(6, 6));
        this.binding = binding;
        this.port = port;
        this.log = log;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JTextArea intro = new JTextArea(
                "Table smoothing. The ECU interpolates between cells: a jagged VE table makes the mixture swing on the way through it, "
                        + "and the same throttle input gives a different answer each time. Read the table, preview: every cell in the chosen region "
                        + "moves part of the way towards the average of its neighbours (a peak comes down, a hole fills), at most the cap per "
                        + "cell. Blue = raised, red = lowered. Write it with an undo copy, burn in TunerStudio. Smooth a table that is already "
                        + "close (after VE Analyze), not one that still needs tuning.");
        intro.setEditable(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        intro.setBackground(getBackground());

        JPanel pick = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        pick.add(new JLabel("Table:"));
        pick.add(tables);
        pick.add(new JLabel("Z:"));
        pick.add(zField);
        pick.add(new JLabel("X (RPM):"));
        pick.add(xField);
        pick.add(new JLabel("Y (load):"));
        pick.add(yField);
        pick.add(readBtn);
        JPanel north = new JPanel(new BorderLayout());
        north.add(intro, BorderLayout.NORTH);
        north.add(pick, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        form.section("Smoothing");
        form.addDouble("Strength, % (0 = none, 100 = the neighbours' average)", "How far a cell moves towards its neighbours in one pass",
                new Form.DoubleGet() { public double get() { return settings.strength * 100; } },
                new Form.DoubleSet() { public void set(double v) { settings.strength = v / 100.0; } });
        form.addDouble("Passes", "Repeat the pass this many times", new Form.DoubleGet() { public double get() { return settings.passes; } },
                new Form.DoubleSet() { public void set(double v) { settings.passes = (int) Math.max(1, v); } });
        form.addComponent("Direction", direction);
        form.addDouble("Max change per cell, % (0 = no cap)", "", new Form.DoubleGet() { public double get() { return settings.maxChangePct; } },
                new Form.DoubleSet() { public void set(double v) { settings.maxChangePct = v; } });
        form.section("Region (blank = whole table)");
        form.addOptionalDouble("RPM from", "", new Form.DoubleGet() { public double get() { return settings.xMin; } },
                new Form.DoubleSet() { public void set(double v) { settings.xMin = v; } });
        form.addOptionalDouble("RPM to", "", new Form.DoubleGet() { public double get() { return settings.xMax; } },
                new Form.DoubleSet() { public void set(double v) { settings.xMax = v; } });
        form.addOptionalDouble("Load from", "", new Form.DoubleGet() { public double get() { return settings.yMin; } },
                new Form.DoubleSet() { public void set(double v) { settings.yMin = v; } });
        form.addOptionalDouble("Load to", "", new Form.DoubleGet() { public double get() { return settings.yMax; } },
                new Form.DoubleSet() { public void set(double v) { settings.yMax = v; } });
        JPanel buttons = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        buttons.add(previewBtn);
        buttons.add(writeBtn);
        buttons.add(restoreBtn);
        buttons.add(status);
        stats.setEditable(false);
        stats.setLineWrap(true);
        stats.setWrapStyleWord(true);
        stats.setBackground(getBackground());
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(new JScrollPane(form.panel()), BorderLayout.CENTER);
        JPanel leftSouth = new JPanel(new BorderLayout());
        leftSouth.add(buttons, BorderLayout.NORTH);
        leftSouth.add(stats, BorderLayout.CENTER);
        left.add(leftSouth, BorderLayout.SOUTH);

        JPanel grids = new JPanel(new GridLayout(2, 1, 4, 4));
        currentTable.setDefaultRenderer(Object.class, new DeltaRenderer(currentModel, false));
        previewTable.setDefaultRenderer(Object.class, new DeltaRenderer(previewModel, true));
        grids.add(titled("Current table (from the ECU)", currentTable));
        grids.add(titled("Smoothed preview (blue = raised, red = lowered, grey = outside the region)", previewTable));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, grids);
        split.setResizeWeight(0.35);
        add(split, BorderLayout.CENTER);

        writeBtn.setEnabled(false);
        restoreBtn.setEnabled(false);
        previewBtn.setEnabled(false);
        tables.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Choice c = (Choice) tables.getSelectedItem();
                if (c != null && c.z != null) {
                    zField.setText(c.z);
                    xField.setText(c.x);
                    yField.setText(c.y);
                }
            }
        });
        readBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                read();
            }
        });
        previewBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                preview();
            }
        });
        writeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                write(true);
            }
        });
        restoreBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                restore();
            }
        });
        form.refresh();
        fillChoices();
    }

    private static JPanel titled(String title, JTable t) {
        JPanel p = new JPanel(new BorderLayout());
        JLabel l = new JLabel(title);
        p.add(l, BorderLayout.NORTH);
        p.add(new JScrollPane(t), BorderLayout.CENTER);
        return p;
    }

    private String config() {
        if (binding.has(binding.configName)) {
            return binding.configName;
        }
        List<String> names = port == null ? new ArrayList<String>() : port.configurationNames();
        return names.isEmpty() ? "" : names.get(0);
    }

    /** Lists the INI tables (VE first) plus a custom entry. */
    public void fillChoices() {
        tables.removeAllItems();
        Choice ve = null;
        List<Choice> rest = new ArrayList<Choice>();
        if (port != null) {
            for (EcuPort.UiTableInfo t : port.uiTables(config())) {
                if (t.zParam == null || t.xParam == null || t.yParam == null) {
                    continue;
                }
                Choice c = new Choice(t.name + " (" + t.zParam + ")", t.zParam, t.xParam, t.yParam);
                if (ve == null && t.zParam.toLowerCase(Locale.US).startsWith("vetable")) {
                    ve = c;
                } else {
                    rest.add(c);
                }
            }
        }
        if (ve == null && port != null && port.parameterNames(config()).contains("veTable1")) {
            ve = new Choice("Fuel VE Table 1 (veTable1)", "veTable1", "frpm_table1", "fmap_table1");
        }
        if (ve != null) {
            tables.addItem(ve);
        }
        for (Choice c : rest) {
            tables.addItem(c);
        }
        tables.addItem(new Choice(CUSTOM, null, null, null));
        if (tables.getItemCount() > 0) {
            tables.setSelectedIndex(0);
        }
    }

    /** Picks the choice whose Z parameter has this name (tests and callers); false when absent. */
    public boolean selectTable(String zParam) {
        for (int i = 0; i < tables.getItemCount(); i++) {
            Choice c = tables.getItemAt(i);
            if (zParam.equals(c.z)) {
                tables.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    public boolean read() {
        if (port == null) {
            status.setText("No ECU connection");
            return false;
        }
        String z = zField.getText().trim();
        String x = xField.getText().trim();
        String y = yField.getText().trim();
        if (z.isEmpty() || x.isEmpty() || y.isEmpty()) {
            status.setText("Give the table (Z) and both axis (X, Y) parameter names");
            return false;
        }
        try {
            EcuAdapter a = new EcuAdapter(port, binding);
            cfg = a.config();
            EcuPort.ParamInfo info = port.parameterInfo(cfg, z);
            layout = a.layoutFor(cfg, z, x, y, info);
            current = a.readGrid(cfg, z, x, y, layout);
            tableMin = info != null && info.max > info.min ? info.min : Double.NaN;
            tableMax = info != null && info.max > info.min ? info.max : Double.NaN;
            tableDecimals = info == null ? 1 : Math.max(0, Math.min(3, info.decimals));
            if (!z.equals(zName)) {
                original = null;
                restoreBtn.setEnabled(false);
            }
            zName = z;
            xName = x;
            yName = y;
            preview = null;
            currentModel.setFormat(fmt());
            currentModel.setYLabel("load \\ rpm");
            currentModel.setGrid(current, null);
            previewModel.setFormat(fmt());
            previewModel.setYLabel("load \\ rpm");
            previewModel.setGrid(null, null);
            writeBtn.setEnabled(false);
            previewBtn.setEnabled(true);
            boolean[][] all = TableSmoother.region(current, new TableSmoother.Settings());
            stats.setText(String.format(Locale.US, "%s: %d x %d, values %.1f..%.1f, roughness %.2f", z, current.width(), current.height(),
                    current.min(), current.max(), TableSmoother.roughness(current, all)));
            status.setText("Read " + z + " from the ECU");
            return true;
        } catch (EcuException e) {
            status.setText("Read failed: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            status.setText("Read failed: " + e);
            return false;
        }
    }

    private String fmt() {
        return "%." + tableDecimals + "f";
    }

    public TableSmoother.Result preview() {
        if (current == null) {
            status.setText("Read the table first");
            return null;
        }
        try {
            form.apply();
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            return null;
        }
        settings.direction = (TableSmoother.Direction) direction.getSelectedItem();
        settings.minValue = tableMin;
        settings.maxValue = tableMax;
        settings.decimals = tableDecimals;
        String problem = settings.validate();
        if (problem != null) {
            status.setText(problem);
            return null;
        }
        preview = TableSmoother.smooth(current, settings);
        previewModel.setGrid(preview.smoothed, current);
        stats.setText(preview.summary());
        writeBtn.setEnabled(preview.changedCells > 0);
        status.setText(preview.changedCells > 0 ? "Preview ready: check the coloured cells, then Write to ECU" : "Nothing to change with these settings");
        return preview;
    }

    /** Writes the previewed table; returns true when written. */
    public boolean write(boolean confirm) {
        if (preview == null || current == null || port == null) {
            status.setText("Preview first");
            return false;
        }
        if (confirm) {
            int ok = JOptionPane.showConfirmDialog(this, "Write the smoothed " + zName + " to the ECU (RAM)?\n" + preview.summary()
                    + "\nThe table read before the first write is kept for 'Restore original'.", "Table smoothing", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return false;
            }
        }
        try {
            if (original == null) {
                original = current.copy();
            }
            port.writeArray2D(cfg, zName, layout.fromGrid(preview.smoothed));
            log.line("[smooth] " + zName + " written: " + preview.summary());
            current = preview.smoothed.copy();
            currentModel.setGrid(current, original);
            previewModel.setGrid(null, null);
            preview = null;
            writeBtn.setEnabled(false);
            restoreBtn.setEnabled(true);
            status.setText(zName + " written to ECU RAM. Burn in TunerStudio to keep it; Restore original undoes.");
            return true;
        } catch (EcuException e) {
            status.setText("Write failed: " + e.getMessage());
            return false;
        }
    }

    public boolean restore() {
        if (original == null || port == null) {
            return false;
        }
        try {
            port.writeArray2D(cfg, zName, layout.fromGrid(original));
            log.line("[smooth] " + zName + " restored to the table read before the first write");
            current = original.copy();
            original = null;
            currentModel.setGrid(current, null);
            previewModel.setGrid(null, null);
            preview = null;
            writeBtn.setEnabled(false);
            restoreBtn.setEnabled(false);
            status.setText(zName + " restored");
            return true;
        } catch (EcuException e) {
            status.setText("Restore failed: " + e.getMessage());
            return false;
        }
    }

    public TableSmoother.Settings settings() {
        return settings;
    }

    /** Shows the settings object's values in the form (after changing them from code). */
    public void refresh() {
        form.refresh();
        direction.setSelectedItem(settings.direction);
    }

    public Grid currentGrid() {
        return current;
    }

    public JTextField zField() {
        return zField;
    }

    /** Colours a cell by its change against the reference grid; region-less cells in the preview stay grey. */
    private final class DeltaRenderer extends DefaultTableCellRenderer {
        private final GridTableModel model;
        private final boolean isPreview;

        DeltaRenderer(GridTableModel model, boolean isPreview) {
            this.model = model;
            this.isPreview = isPreview;
            setHorizontalAlignment(RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            Color bg = Color.WHITE;
            if (col == 0) {
                bg = new Color(235, 235, 235);
            } else if (model.grid() != null) {
                double d = model.delta(row, col);
                if (d > 0.005) {
                    bg = new Color(200, 225, 255);
                } else if (d < -0.005) {
                    bg = new Color(255, 215, 215);
                } else if (isPreview && preview != null) {
                    int yi = model.yIndex(row);
                    int xi = col - 1;
                    if (yi < preview.inRegion.length && xi < preview.inRegion[yi].length && !preview.inRegion[yi][xi]) {
                        bg = new Color(225, 225, 225);
                    }
                }
            }
            c.setBackground(sel ? bg.darker() : bg);
            c.setForeground(Palette.textOn(bg));
            return c;
        }
    }
}
