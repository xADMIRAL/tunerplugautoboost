package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.sweep.SweepConfig;
import io.github.xadmiral.boostautotune.core.vvt.VvtPidConfig;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

/** Settings of the two VVT modes: cam target sweep at WOT and closed-loop PID tuning. */
public final class VvtPanel extends JPanel {
    private final SweepConfig sweep;
    private final VvtPidConfig pid;
    private final Form form = new Form();
    private final JLabel status = new JLabel(" ");
    private final Runnable onChanged;

    public VvtPanel(final SweepConfig sweep, final VvtPidConfig pid, Runnable onChanged) {
        super(new BorderLayout(6, 6));
        this.sweep = sweep;
        this.pid = pid;
        this.onChanged = onChanged;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JTextArea intro = new JTextArea(
                "VVT target sweep: the WOT rows of the intake cam table get a series of offsets, one per run "
                        + "(each run = 1-2 pulls in the same gear on the same road). Engine acceleration per RPM bin is compared "
                        + "and the best cam timing is written back, smoothed across RPM. Part-throttle rows are untouched.\n"
                        + "VVT PID: drive normally for a minute or two per run; the cam must follow its target without ringing "
                        + "or lag. Gains are adjusted in relative steps.");
        intro.setEditable(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        intro.setBackground(getBackground());
        add(intro, BorderLayout.NORTH);

        form.section("VVT target sweep (pulls)");
        form.addText("Candidate offsets, deg (must include 0)", "Added to the current WOT-row values, one candidate per run",
                new Form.TextGet() { public String get() { return SettingsStore.joinDoubles(sweep.candidateOffsets); } },
                new Form.TextSet() { public void set(String v) { setCandidates(sweep, v); } });
        form.addDouble("Passes", "Repeat the sweep this many times (2nd pass in reverse order cancels drift)",
                new Form.DoubleGet() { public double get() { return sweep.passes; } },
                new Form.DoubleSet() { public void set(double v) { sweep.passes = (int) Math.max(1, v); } });
        form.addDouble("Min load of rows to change", "Rows with a load bin at or above this (WOT / boost rows)",
                new Form.DoubleGet() { public double get() { return sweep.minLoad; } },
                new Form.DoubleSet() { public void set(double v) { sweep.minLoad = v; } });
        form.addDouble("Min RPM", "", new Form.DoubleGet() { public double get() { return sweep.minRpm; } },
                new Form.DoubleSet() { public void set(double v) { sweep.minRpm = v; } });
        form.addDouble("Max RPM", "", new Form.DoubleGet() { public double get() { return sweep.maxRpm; } },
                new Form.DoubleSet() { public void set(double v) { sweep.maxRpm = v; } });
        form.addDouble("Gear (0 = any)", "Pulls in another gear are ignored when a gear channel is bound",
                new Form.DoubleGet() { public double get() { return sweep.gear; } },
                new Form.DoubleSet() { public void set(double v) { sweep.gear = (int) v; } });
        form.addDouble("Min torque gain to change a cell, %", "",
                new Form.DoubleGet() { public double get() { return sweep.minGainPct; } },
                new Form.DoubleSet() { public void set(double v) { sweep.minGainPct = v; } });
        form.addBool("Require gain above noise", "The gain must also exceed the measurement noise of both candidates",
                new Form.BoolGet() { public boolean get() { return sweep.requireAboveNoise; } },
                new Form.BoolSet() { public void set(boolean v) { sweep.requireAboveNoise = v; } });
        form.addDouble("Max step between RPM columns, deg", "",
                new Form.DoubleGet() { public double get() { return sweep.smoothingMaxStepDeg; } },
                new Form.DoubleSet() { public void set(double v) { sweep.smoothingMaxStepDeg = v; } });
        form.addDouble("Table minimum, deg", "", new Form.DoubleGet() { public double get() { return sweep.absoluteMin; } },
                new Form.DoubleSet() { public void set(double v) { sweep.absoluteMin = v; } });
        form.addDouble("Table maximum, deg", "", new Form.DoubleGet() { public double get() { return sweep.absoluteMax; } },
                new Form.DoubleSet() { public void set(double v) { sweep.absoluteMax = v; } });
        form.addDouble("WOT load threshold, % TPS", "", new Form.DoubleGet() { public double get() { return sweep.wotLoadThreshold; } },
                new Form.DoubleSet() { public void set(double v) { sweep.wotLoadThreshold = v; } });
        form.addDouble("Minimum coolant, °C", "", new Form.DoubleGet() { public double get() { return sweep.minCltC; } },
                new Form.DoubleSet() { public void set(double v) { sweep.minCltC = v; } });
        form.addDouble("Overboost abort, kPa", "", new Form.DoubleGet() { public double get() { return sweep.maxBoostKpa; } },
                new Form.DoubleSet() { public void set(double v) { sweep.maxBoostKpa = v; } });
        form.addDouble("Auto end run after idle, s", "0 = manual", new Form.DoubleGet() { public double get() { return sweep.autoEndRunIdleSec; } },
                new Form.DoubleSet() { public void set(double v) { sweep.autoEndRunIdleSec = v; } });

        form.section("VVT closed-loop PID (normal driving)");
        form.addDouble("Cam commanded at least, deg", "Samples with a smaller target are ignored (cam at rest)",
                new Form.DoubleGet() { public double get() { return pid.activeMinTargetDeg; } },
                new Form.DoubleSet() { public void set(double v) { pid.activeMinTargetDeg = v; } });
        form.addDouble("Minimum RPM", "", new Form.DoubleGet() { public double get() { return pid.minRpm; } },
                new Form.DoubleSet() { public void set(double v) { pid.minRpm = v; } });
        form.addDouble("Minimum coolant, °C", "", new Form.DoubleGet() { public double get() { return pid.minCltC; } },
                new Form.DoubleSet() { public void set(double v) { pid.minCltC = v; } });
        form.addDouble("Ringing amplitude limit, deg", "", new Form.DoubleGet() { public double get() { return pid.oscillationDeg; } },
                new Form.DoubleSet() { public void set(double v) { pid.oscillationDeg = v; } });
        form.addDouble("Steady error tolerance, deg", "", new Form.DoubleGet() { public double get() { return pid.steadyStateTolDeg; } },
                new Form.DoubleSet() { public void set(double v) { pid.steadyStateTolDeg = v; } });
        form.addDouble("Lag tolerance, s", "How long the cam may trail a moving target", new Form.DoubleGet() { public double get() { return pid.lagTolSec; } },
                new Form.DoubleSet() { public void set(double v) { pid.lagTolSec = v; } });
        form.addDouble("Samples required per run", "", new Form.DoubleGet() { public double get() { return pid.minSamples; } },
                new Form.DoubleSet() { public void set(double v) { pid.minSamples = (int) v; } });
        form.addDouble("PID step per run (fraction)", "", new Form.DoubleGet() { public double get() { return pid.pidStepFraction; } },
                new Form.DoubleSet() { public void set(double v) { pid.pidStepFraction = v; } });
        form.addBool("Tune P", "", new Form.BoolGet() { public boolean get() { return pid.tuneP; } },
                new Form.BoolSet() { public void set(boolean v) { pid.tuneP = v; } });
        form.addBool("Tune I", "", new Form.BoolGet() { public boolean get() { return pid.tuneI; } },
                new Form.BoolSet() { public void set(boolean v) { pid.tuneI = v; } });
        form.addBool("Tune D", "", new Form.BoolGet() { public boolean get() { return pid.tuneD; } },
                new Form.BoolSet() { public void set(boolean v) { pid.tuneD = v; } });
        form.addDouble("Good runs required", "", new Form.DoubleGet() { public double get() { return pid.runsRequired; } },
                new Form.DoubleSet() { public void set(double v) { pid.runsRequired = (int) Math.max(1, v); } });

        add(new JScrollPane(form.panel()), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        JButton applyBtn = new JButton("Apply");
        buttons.add(applyBtn);
        buttons.add(status);
        add(buttons, BorderLayout.SOUTH);
        applyBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                apply();
            }
        });
        form.refresh();
    }

    static void setCandidates(SweepConfig c, String text) {
        List<Double> list = SettingsStore.parseStages(text);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Enter candidate offsets, e.g. 0, -10, -5, 5, 10");
        }
        c.candidateOffsets = list;
    }

    public boolean apply() {
        try {
            form.apply();
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            return false;
        }
        List<String> problems = sweep.validate();
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
}
