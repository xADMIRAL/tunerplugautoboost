package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.learn.BiasBuildResult;
import io.github.xadmiral.boostautotune.core.learn.DutyEstimate;
import io.github.xadmiral.boostautotune.core.log.LogColumnMapping;
import io.github.xadmiral.boostautotune.plugin.TuneController;
import io.github.xadmiral.boostautotune.plugin.mode.TableView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
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
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;

/** Tables and figures produced by the last run of any mode, plus offline datalog analysis. */
public final class AnalysisPanel extends JPanel {
    private final TuneController ctl;
    private final JPanel tables = new JPanel(new GridLayout(1, 1, 6, 6));
    private final JTextArea details = new JTextArea(12, 80);

    public AnalysisPanel(final TuneController ctl) {
        super(new BorderLayout(6, 6));
        this.ctl = ctl;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(tables), new JScrollPane(details));
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);

        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        JButton analyze = new JButton("Analyze datalog as next run...");
        top.add(analyze);
        top.add(new JLabel("Feed a TunerStudio .msl/.csv log recorded with the current plan instead of recording live."));
        add(top, BorderLayout.NORTH);
        analyze.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                analyzeLog();
            }
        });
        refresh();
    }

    /** Rebuilds the tables and text from the controller's current driver. */
    public void refresh() {
        tables.removeAll();
        refreshTables();
        Fonts.apply(tables, Fonts.current());
        tables.revalidate();
        tables.repaint();
    }

    private void refreshTables() {
        List<TableView> views = ctl.tables();
        tables.setLayout(new GridLayout(1, Math.max(1, views.size()), 6, 6));
        for (TableView v : views) {
            GridTableModel model = new GridTableModel();
            model.setYLabel(v.yLabel);
            model.setGrid(v.next, v.reference);
            JTable table = new JTable(model);
            table.setDefaultRenderer(Object.class, new CellRenderer(model, v.quality));
            JPanel p = new JPanel(new BorderLayout());
            JLabel l = new JLabel(v.title);
            l.setFont(l.getFont().deriveFont(11f));
            p.add(l, BorderLayout.NORTH);
            p.add(new JScrollPane(table), BorderLayout.CENTER);
            tables.add(p);
        }
        if (views.isEmpty()) {
            tables.add(new JLabel("No tables for this mode / no session yet."));
        }
        tables.revalidate();
        tables.repaint();
        details.setText(ctl.analysisText());
        details.setCaretPosition(0);
    }

    private void analyzeLog() {
        if (ctl.driver() == null) {
            JOptionPane.showMessageDialog(this, "Start a session first: the log is analysed as the next run of the current plan.",
                    "Boost Autotune", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a TunerStudio datalog (.msl or .csv)");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        LogColumnMapping mapping = askMapping();
        if (mapping == null) {
            return;
        }
        try {
            ctl.analyzeLog(f, mapping);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Boost Autotune", JOptionPane.ERROR_MESSAGE);
        }
    }

    private LogColumnMapping askMapping() {
        final JComboBox<String> preset = new JComboBox<String>(new String[]{"MS3 / Stealth PCM", "Speeduino", "rusEFI"});
        final LogColumnMapping m0 = LogColumnMapping.ms3();
        final JTextField[] fields = {
                new JTextField(m0.time), new JTextField(m0.rpm), new JTextField(m0.tps), new JTextField(m0.map),
                new JTextField(m0.target), new JTextField(m0.duty), new JTextField(m0.clt), new JTextField(m0.gear),
                new JTextField(m0.vvtAngle), new JTextField(m0.vvtTarget), new JTextField(m0.advance),
                new JTextField(m0.knockRetard), new JTextField(m0.knock), new JTextField(m0.afr),
                new JTextField(m0.fuelLoad), new JTextField(m0.ignLoad)};
        final String[] labels = {"Time", "RPM", "TPS", "MAP", "Boost target", "Boost duty", "CLT", "Gear",
                "VVT angle", "VVT target", "Advance", "Knock retard", "Knock level", "AFR", "Fuel load", "Ign load"};
        preset.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                LogColumnMapping m = preset.getSelectedIndex() == 1 ? LogColumnMapping.speeduino()
                        : preset.getSelectedIndex() == 2 ? LogColumnMapping.rusefi() : LogColumnMapping.ms3();
                String[] v = {m.time, m.rpm, m.tps, m.map, m.target, m.duty, m.clt, m.gear, m.vvtAngle, m.vvtTarget,
                        m.advance, m.knockRetard, m.knock, m.afr, m.fuelLoad, m.ignLoad};
                for (int i = 0; i < fields.length; i++) {
                    fields[i].setText(v[i] == null ? "" : v[i]);
                }
            }
        });
        JPanel p = new JPanel(new GridLayout(0, 2, 4, 4));
        p.add(new JLabel("Preset"));
        p.add(preset);
        for (int i = 0; i < labels.length; i++) {
            p.add(new JLabel(labels[i] + " column"));
            p.add(fields[i]);
        }
        int r = JOptionPane.showConfirmDialog(this, p, "Datalog columns", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return null;
        }
        LogColumnMapping m = new LogColumnMapping();
        m.time = t(fields[0]);
        m.rpm = t(fields[1]);
        m.tps = t(fields[2]);
        m.map = t(fields[3]);
        m.target = t(fields[4]);
        m.duty = t(fields[5]);
        m.clt = t(fields[6]);
        m.gear = t(fields[7]);
        m.vvtAngle = t(fields[8]);
        m.vvtTarget = t(fields[9]);
        m.advance = t(fields[10]);
        m.knockRetard = t(fields[11]);
        m.knock = t(fields[12]);
        m.afr = t(fields[13]);
        m.fuelLoad = t(fields[14]);
        m.ignLoad = t(fields[15]);
        return m;
    }

    private static String t(JTextField f) {
        String s = f.getText().trim();
        return s.isEmpty() ? null : s;
    }

    /** Colours cells by estimate quality (bias table) or by change against the reference. */
    private static final class CellRenderer extends DefaultTableCellRenderer {
        private final GridTableModel model;
        private final BiasBuildResult quality;

        CellRenderer(GridTableModel model, BiasBuildResult quality) {
            this.model = model;
            this.quality = quality;
            setHorizontalAlignment(RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            Color bg = Color.WHITE;
            if (col == 0) {
                bg = new Color(235, 235, 235);
            } else if (quality != null && model.grid() != null) {
                int yi = model.yIndex(row);
                int xi = col - 1;
                if (yi < quality.quality.length && xi < quality.quality[yi].length) {
                    DutyEstimate.Quality q = quality.quality[yi][xi];
                    if (q == DutyEstimate.Quality.MEASURED) bg = new Color(200, 240, 200);
                    else if (q == DutyEstimate.Quality.INTERPOLATED) bg = new Color(245, 240, 180);
                    else if (q == DutyEstimate.Quality.EXTRAPOLATED) bg = new Color(250, 215, 170);
                    else if (q == DutyEstimate.Quality.BORROWED) bg = new Color(220, 220, 220);
                }
            } else {
                double d = model.delta(row, col);
                if (d > 0.05) bg = new Color(200, 225, 255);
                else if (d < -0.05) bg = new Color(255, 215, 215);
            }
            c.setBackground(sel ? bg.darker() : bg);
            return c;
        }
    }
}
