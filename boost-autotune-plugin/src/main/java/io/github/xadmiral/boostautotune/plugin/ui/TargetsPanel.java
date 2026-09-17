package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.learn.TargetTableBuilder;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/** Target stages, safety limits and the tuning knobs. */
public final class TargetsPanel extends JPanel {
    private final AutotuneConfig cfg;
    private final EcuBinding binding;
    private final EcuPort port;
    private final Form form = new Form();
    private final JTextField stagesField;
    private final GridTableModel previewModel = new GridTableModel();
    private final JLabel status = new JLabel(" ");
    private final Runnable onChanged;

    public TargetsPanel(AutotuneConfig cfg, EcuBinding binding, EcuPort port, Runnable onChanged) {
        super(new BorderLayout(6, 6));
        this.cfg = cfg;
        this.binding = binding;
        this.port = port;
        this.onChanged = onChanged;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        form.section("Targets");
        stagesField = form.addText("Target stages, kPa absolute (comma separated)",
                "Each stage is tuned to convergence before the next one starts, e.g. 150, 170",
                new Form.TextGet() { public String get() { return stagesText(); } },
                new Form.TextSet() { public void set(String v) { setStages(v); } });
        form.addDouble("Wastegate spring pressure, kPa", "Boost with the valve fully open; targets below spool RPM",
                new Form.DoubleGet() { public double get() { return cfg.wastegateKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.wastegateKpa = v; } });
        form.addDouble("Spool start RPM", "Ramp mode: target ramps up from wastegate pressure here. Fast spool: wastegate pressure up to here, the stage target right above (0 = flat)",
                new Form.DoubleGet() { public double get() { return cfg.spoolStartRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.spoolStartRpm = v; } });
        form.addDouble("Full target RPM", "Ramp mode only: target reaches the stage value here (0 = flat target)",
                new Form.DoubleGet() { public double get() { return cfg.fullTargetRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.fullTargetRpm = v; } });

        form.section("Spool");
        form.addBool("Fastest spool to target", "Valve held shut wherever the target is out of reach, flat target instead of the ramp, then trim / feed-forward / P / closed-loop window are pushed while the target keeps arriving earlier without overshoot",
                new Form.BoolGet() { public boolean get() { return cfg.fastSpool; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.fastSpool = v; } });
        form.addDouble("Valve counts as shut above, % duty", "A bias cell whose estimate asks for at least this much duty is written as maximum duty",
                new Form.DoubleGet() { public double get() { return cfg.spoolShutDutyPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.spoolShutDutyPct = v; } });
        form.addDouble("Push runs on the last stage", "Extra runs spent pushing the spool once the loop is settled (0 = none)",
                new Form.DoubleGet() { public double get() { return cfg.maxSpoolPushesPerStage; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxSpoolPushesPerStage = (int) Math.max(0, v); } });
        form.addDouble("A push must gain at least, RPM", "The target has to arrive this much earlier for a push to be kept going",
                new Form.DoubleGet() { public double get() { return cfg.spoolImproveRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.spoolImproveRpm = v; } });
        form.addDouble("Feed-forward push step, %", "Bias raised above the steady duty around the RPM where the target arrives, per push",
                new Form.DoubleGet() { public double get() { return cfg.spoolBoostStepPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.spoolBoostStepPct = v; } });
        form.addBool("Tune the closed-loop window", "MS3 'lower limit delta' (needs the Setup binding): widened by a push, narrowed when overshoot persists",
                new Form.BoolGet() { public boolean get() { return cfg.tuneClosedLoopWindow; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.tuneClosedLoopWindow = v; } });
        form.addDouble("Window step, kPa", "", new Form.DoubleGet() { public double get() { return cfg.windowStepKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.windowStepKpa = v; } });
        form.addDouble("Window minimum, kPa", "", new Form.DoubleGet() { public double get() { return cfg.windowMinKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.windowMinKpa = v; } });
        form.addDouble("Window maximum, kPa", "", new Form.DoubleGet() { public double get() { return cfg.windowMaxKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.windowMaxKpa = v; } });
        form.addDouble("High-RPM taper, kPa", "Lower the target by this much at the last RPM bin (0 = off)",
                new Form.DoubleGet() { public double get() { return cfg.highRpmTaperKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.highRpmTaperKpa = v; } });
        form.addDouble("Taper start RPM", "",
                new Form.DoubleGet() { public double get() { return cfg.highRpmTaperStartRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.highRpmTaperStartRpm = v; } });
        form.addDouble("WOT load threshold, %", "Rows at or above this load are wide open throttle",
                new Form.DoubleGet() { public double get() { return cfg.wotLoadThreshold; } },
                new Form.DoubleSet() { public void set(double v) { cfg.wotLoadThreshold = v; } });
        form.addBool("Scale part-throttle rows", "Otherwise the ECU values below WOT are kept",
                new Form.BoolGet() { public boolean get() { return cfg.scalePartThrottleRows; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.scalePartThrottleRows = v; } });
        form.addDouble("Part-throttle floor, kPa", "",
                new Form.DoubleGet() { public double get() { return cfg.partThrottleFloorKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.partThrottleFloorKpa = v; } });

        form.section("Safety");
        form.addDouble("Hard limit, kPa", "Any sample above this aborts the session and forces a safe duty. Keep it below the ECU overboost cut.",
                new Form.DoubleGet() { public double get() { return cfg.maxBoostKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxBoostKpa = v; } });
        form.addDouble("Prediction margin, kPa", "Open-loop ladder never plans a duty predicted above hard limit minus this",
                new Form.DoubleGet() { public double get() { return cfg.predictionMarginKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.predictionMarginKpa = v; } });
        form.addDouble("Minimum coolant, °C", "",
                new Form.DoubleGet() { public double get() { return cfg.minCltC; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minCltC = v; } });
        form.addDouble("Minimum RPM", "",
                new Form.DoubleGet() { public double get() { return cfg.minRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minRpm = v; } });
        form.addDouble("Maximum RPM", "",
                new Form.DoubleGet() { public double get() { return cfg.maxRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxRpm = v; } });

        form.section("Open-loop characterization");
        form.addBool("Characterize first", "Run an open-loop duty ladder before closing the loop (needs an open-loop duty table)",
                new Form.BoolGet() { public boolean get() { return cfg.characterizeFirst; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.characterizeFirst = v; } });
        form.addDouble("Start duty, %", "0 = minimum duty + step",
                new Form.DoubleGet() { public double get() { return cfg.characterizeStartDuty; } },
                new Form.DoubleSet() { public void set(double v) { cfg.characterizeStartDuty = v; } });
        form.addDouble("Step, %", "",
                new Form.DoubleGet() { public double get() { return cfg.characterizeStepPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.characterizeStepPct = v; } });
        form.addDouble("Headroom above highest target, kPa", "Ladder stops once this much boost above the highest stage was seen",
                new Form.DoubleGet() { public double get() { return cfg.characterizeHeadroomKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.characterizeHeadroomKpa = v; } });
        form.addDouble("Ladder duty ceiling, %", "",
                new Form.DoubleGet() { public double get() { return cfg.characterizeMaxDuty; } },
                new Form.DoubleSet() { public void set(double v) { cfg.characterizeMaxDuty = v; } });

        form.section("Closed-loop assessment");
        form.addDouble("Overshoot limit, kPa", "",
                new Form.DoubleGet() { public double get() { return cfg.overshootThresholdKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.overshootThresholdKpa = v; } });
        form.addDouble("Steady-state tolerance, kPa", "",
                new Form.DoubleGet() { public double get() { return cfg.steadyStateTolKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.steadyStateTolKpa = v; } });
        form.addDouble("Oscillation limit, kPa", "",
                new Form.DoubleGet() { public double get() { return cfg.oscillationAmplitudeKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.oscillationAmplitudeKpa = v; } });
        form.addDouble("Max rise time, s", "",
                new Form.DoubleGet() { public double get() { return cfg.maxRiseTimeSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxRiseTimeSec = v; } });
        form.addDouble("PID step per run (fraction)", "0.25 = gains move by 25 % per run",
                new Form.DoubleGet() { public double get() { return cfg.pidStepFraction; } },
                new Form.DoubleSet() { public void set(double v) { cfg.pidStepFraction = v; } });
        form.addBool("Tune P", "", new Form.BoolGet() { public boolean get() { return cfg.tuneP; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.tuneP = v; } });
        form.addBool("Tune I", "", new Form.BoolGet() { public boolean get() { return cfg.tuneI; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.tuneI = v; } });
        form.addBool("Tune D", "", new Form.BoolGet() { public boolean get() { return cfg.tuneD; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.tuneD = v; } });
        form.addBool("Soft start (halve I and D for the first closed-loop run)", "",
                new Form.BoolGet() { public boolean get() { return cfg.softStartClosedLoop; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.softStartClosedLoop = v; } });
        form.addBool("Trim unreachable targets", "Lower target cells where the valve is at max duty and boost stays short",
                new Form.BoolGet() { public boolean get() { return cfg.trimUnreachableTargets; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.trimUnreachableTargets = v; } });
        form.addDouble("Good runs required per stage", "",
                new Form.DoubleGet() { public double get() { return cfg.runsRequiredPerStage; } },
                new Form.DoubleSet() { public void set(double v) { cfg.runsRequiredPerStage = (int) Math.max(1, v); } });

        form.section("Learning");
        form.addDouble("Bias authority per run, %", "Max change of a bias cell per run once the table exists",
                new Form.DoubleGet() { public double get() { return cfg.biasMaxStepPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.biasMaxStepPct = v; } });
        form.addDouble("Valve-to-MAP lag, s", "MAP is paired with the duty commanded this long before",
                new Form.DoubleGet() { public double get() { return cfg.plantLagSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.plantLagSec = v; } });
        form.addDouble("Settle delay after WOT, s", "",
                new Form.DoubleGet() { public double get() { return cfg.settleDelaySec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.settleDelaySec = v; } });
        form.addDouble("Max MAP slope for steady samples, kPa/s", "",
                new Form.DoubleGet() { public double get() { return cfg.maxMapSlopeKpaPerSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxMapSlopeKpaPerSec = v; } });
        form.addDouble("Auto end run after idle, s", "0 = end runs manually",
                new Form.DoubleGet() { public double get() { return cfg.autoEndRunIdleSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.autoEndRunIdleSec = v; } });

        JPanel left = new JPanel(new BorderLayout());
        left.add(new JScrollPane(form.panel()), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        JButton applyBtn = new JButton("Apply");
        JButton previewBtn = new JButton("Preview target table");
        buttons.add(applyBtn);
        buttons.add(previewBtn);
        buttons.add(status);
        left.add(buttons, BorderLayout.SOUTH);

        JTable preview = new JTable(previewModel);
        preview.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        previewModel.setYLabel("load \\ rpm");
        JPanel right = new JPanel(new BorderLayout());
        javax.swing.JTextArea caption = new javax.swing.JTextArea("Target table preview for the first stage (top row = highest load)");
        caption.setEditable(false);
        caption.setLineWrap(true);
        caption.setWrapStyleWord(true);
        caption.setBackground(getBackground());
        right.add(caption, BorderLayout.NORTH);
        right.add(new JScrollPane(preview), BorderLayout.CENTER);
        // a long caption must not dictate the split: the preview can be narrow, the form needs its labels
        right.setMinimumSize(new java.awt.Dimension(160, 100));
        left.setMinimumSize(new java.awt.Dimension(240, 100));
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.6);
        add(split, BorderLayout.CENTER);
        // the form gets the width it asks for (up to 70 % of the tab), the preview takes the rest
        addComponentListener(new java.awt.event.ComponentAdapter() {
            private int lastWidth = -1;
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int w = getWidth();
                if (w > 0 && w != lastWidth) {
                    lastWidth = w;
                    int wanted = form.panel().getPreferredSize().width + 30;
                    split.setDividerLocation(Math.min(wanted, (int) (w * 0.7)));
                }
            }
        });

        applyBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                apply();
            }
        });
        previewBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (apply()) {
                    previewTargets();
                }
            }
        });
        form.refresh();
    }

    private String stagesText() {
        StringBuilder sb = new StringBuilder();
        for (double t : cfg.targetStagesKpa) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(Form.fmt(t));
        }
        return sb.toString();
    }

    private void setStages(String text) {
        List<Double> list = SettingsStore.parseStages(text);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one target stage, e.g. 150, 170");
        }
        cfg.targetStagesKpa = new ArrayList<Double>(list);
    }

    /** Copies the form into the config; returns false and shows the problem on bad input. */
    /** Preferred width of the settings form (layout checks). */
    public int formPreferredWidth() {
        return form.panel().getPreferredSize().width;
    }

    public boolean apply() {
        try {
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
        form.refresh();
    }

    private void previewTargets() {
        if (port == null) {
            status.setText("No ECU connection");
            return;
        }
        try {
            EcuAdapter adapter = new EcuAdapter(port, binding.copy());
            EcuState s = adapter.read(new ArrayList<String>());
            Grid g = TargetTableBuilder.build(s.targetTable, cfg.targetStagesKpa.get(0), cfg, s.targetTableMax);
            previewModel.setGrid(g, s.targetTable);
            status.setText("Preview built from the ECU target table axes");
        } catch (EcuException e) {
            status.setText("Preview failed: " + e.getMessage());
        }
    }
}
