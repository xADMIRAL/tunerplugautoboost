package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.learn.BiasBuildResult;
import io.github.xadmiral.boostautotune.core.learn.DutyEstimate;
import io.github.xadmiral.boostautotune.core.log.LogColumnMapping;
import io.github.xadmiral.boostautotune.core.session.AutotuneSession;
import io.github.xadmiral.boostautotune.core.session.RunReport;
import io.github.xadmiral.boostautotune.plugin.TuneController;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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

/** Tables and figures produced by the last run, plus offline datalog analysis. */
public final class AnalysisPanel extends JPanel {
    private final TuneController ctl;
    private final GridTableModel biasModel = new GridTableModel();
    private final GridTableModel targetModel = new GridTableModel();
    private final JTextArea details = new JTextArea(12, 80);
    private RunReport current;

    public AnalysisPanel(final TuneController ctl) {
        super(new BorderLayout(6, 6));
        this.ctl = ctl;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        biasModel.setYLabel("target \\ rpm");
        targetModel.setYLabel("load \\ rpm");
        JTable biasTable = new JTable(biasModel);
        JTable targetTable = new JTable(targetModel);
        biasTable.setDefaultRenderer(Object.class, new QualityRenderer(biasModel, true));
        targetTable.setDefaultRenderer(Object.class, new QualityRenderer(targetModel, false));

        JPanel tables = new JPanel(new GridLayout(1, 2, 6, 6));
        tables.add(titled("Bias / feed-forward duty table for the next run (green = measured, yellow = interpolated, orange = extrapolated, grey = borrowed)", biasTable));
        tables.add(titled("Target table for the next run", targetTable));

        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tables, new JScrollPane(details));
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton analyze = new JButton("Analyze datalog as next run...");
        top.add(analyze);
        top.add(new JLabel("Feed a TunerStudio .msl/.csv log of pulls made with the current plan instead of recording live."));
        add(top, BorderLayout.NORTH);
        analyze.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                analyzeLog();
            }
        });
    }

    private static JPanel titled(String title, JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(11f));
        p.add(l, BorderLayout.NORTH);
        p.add(new JScrollPane(c), BorderLayout.CENTER);
        return p;
    }

    public void showReport(RunReport r) {
        current = r;
        if (r == null) {
            return;
        }
        AutotuneSession s = ctl.session();
        if (r.nextPlan != null) {
            biasModel.setGrid(r.nextPlan.ecu.biasTable, r.plan.ecu.biasTable);
            targetModel.setGrid(r.nextPlan.ecu.targetTable, r.plan.ecu.targetTable);
        }
        StringBuilder sb = new StringBuilder(r.summary());
        if (s != null && s.plantModel() != null) {
            sb.append("\nPlant model (duty -> boost per RPM column):\n").append(s.plantModel().describe());
        }
        if (s != null && s.spoolTrim() != null && s.spoolTrim().max() > 0) {
            sb.append("\nSpool trim applied to the bias table (%):\n").append(s.spoolTrim().toText("%7.1f"));
        }
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    public void showPlanOnly() {
        AutotuneSession s = ctl.session();
        if (s == null || s.plan() == null) {
            return;
        }
        biasModel.setGrid(s.plan().ecu.biasTable, ctl.original() == null ? null : ctl.original().biasTable);
        targetModel.setGrid(s.plan().ecu.targetTable, ctl.original() == null ? null : ctl.original().targetTable);
    }

    private void analyzeLog() {
        if (ctl.session() == null) {
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
        final JTextField time = new JTextField("Time");
        final JTextField rpm = new JTextField("RPM");
        final JTextField tps = new JTextField("TPS");
        final JTextField map = new JTextField("MAP");
        final JTextField target = new JTextField("Boost target 1");
        final JTextField duty = new JTextField("Boost duty");
        final JTextField clt = new JTextField("CLT");
        final JTextField gear = new JTextField("Gear");
        preset.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                LogColumnMapping m = preset.getSelectedIndex() == 1 ? LogColumnMapping.speeduino()
                        : preset.getSelectedIndex() == 2 ? LogColumnMapping.rusefi() : LogColumnMapping.ms3();
                time.setText(m.time);
                rpm.setText(m.rpm);
                tps.setText(m.tps);
                map.setText(m.map);
                target.setText(m.target);
                duty.setText(m.duty);
                clt.setText(m.clt);
                gear.setText(m.gear == null ? "" : m.gear);
            }
        });
        JPanel p = new JPanel(new GridLayout(0, 2, 4, 4));
        p.add(new JLabel("Preset"));
        p.add(preset);
        String[] labels = {"Time column", "RPM column", "TPS column", "MAP column", "Boost target column", "Boost duty column", "CLT column", "Gear column"};
        JTextField[] fields = {time, rpm, tps, map, target, duty, clt, gear};
        for (int i = 0; i < labels.length; i++) {
            p.add(new JLabel(labels[i]));
            p.add(fields[i]);
        }
        int r = JOptionPane.showConfirmDialog(this, p, "Datalog columns", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return null;
        }
        LogColumnMapping m = new LogColumnMapping();
        m.time = time.getText().trim();
        m.rpm = rpm.getText().trim();
        m.tps = tps.getText().trim();
        m.map = map.getText().trim();
        m.target = target.getText().trim();
        m.duty = duty.getText().trim();
        m.clt = clt.getText().trim();
        m.gear = gear.getText().trim().isEmpty() ? null : gear.getText().trim();
        return m;
    }

    /** Colours cells by estimate quality (bias) or by change (targets). */
    private final class QualityRenderer extends DefaultTableCellRenderer {
        private final GridTableModel model;
        private final boolean quality;

        QualityRenderer(GridTableModel model, boolean quality) {
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
            } else if (quality && current != null && current.biasResult != null && model.grid() != null) {
                BiasBuildResult b = current.biasResult;
                int yi = model.yIndex(row);
                int xi = col - 1;
                if (yi < b.quality.length && xi < b.quality[yi].length) {
                    DutyEstimate.Quality q = b.quality[yi][xi];
                    if (q == DutyEstimate.Quality.MEASURED) bg = new Color(200, 240, 200);
                    else if (q == DutyEstimate.Quality.INTERPOLATED) bg = new Color(245, 240, 180);
                    else if (q == DutyEstimate.Quality.EXTRAPOLATED) bg = new Color(250, 215, 170);
                    else if (q == DutyEstimate.Quality.BORROWED) bg = new Color(220, 220, 220);
                }
            } else if (!quality) {
                double d = model.delta(row, col);
                if (d > 0.5) bg = new Color(200, 225, 255);
                else if (d < -0.5) bg = new Color(255, 215, 215);
            }
            c.setBackground(sel ? bg.darker() : bg);
            return c;
        }
    }
}
