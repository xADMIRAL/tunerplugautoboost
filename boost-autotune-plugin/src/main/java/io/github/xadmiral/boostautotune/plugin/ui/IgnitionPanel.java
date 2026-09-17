package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.sweep.SweepConfig;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

/** Settings of the ignition advance sweep, with the warning that belongs in front of it. */
public final class IgnitionPanel extends JPanel {
    private final SweepConfig sweep;
    private final Form form = new Form();
    private final JLabel status = new JLabel(" ");
    private final JCheckBox acknowledge = new JCheckBox(
            "I understand: this changes ignition timing under boost. Knock control and a wideband must be working.");
    private final Runnable onChanged;

    public IgnitionPanel(final SweepConfig sweep, Runnable onChanged) {
        super(new BorderLayout(6, 6));
        this.sweep = sweep;
        this.onChanged = onChanged;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JTextArea intro = new JTextArea(
                "Ignition advance sweep: the WOT rows of the spark table get small offsets (e.g. -2, 0, +2, +4 deg), one per run, "
                        + "and engine acceleration per RPM bin decides. The MBT rule keeps the LEAST advance that gives the best torque.\n"
                        + "Guards: any knock retard reported by the ECU caps that cell below the advance that knocked; heavy knock or a lean "
                        + "mixture at WOT aborts the session and restores the original table; no cell is ever advanced more than the session "
                        + "limit over its original value. This is not a substitute for a dyno and a knock ear. Start with a table you trust.");
        intro.setEditable(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        intro.setForeground(new Color(140, 0, 0));
        intro.setBackground(getBackground());
        JPanel top = new JPanel(new BorderLayout());
        top.add(intro, BorderLayout.CENTER);
        top.add(acknowledge, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        form.section("Sweep");
        form.addText("Candidate offsets, deg (must include 0)", "Negative = retard, positive = advance",
                new Form.TextGet() { public String get() { return SettingsStore.joinDoubles(sweep.candidateOffsets); } },
                new Form.TextSet() { public void set(String v) { VvtPanel.setCandidates(sweep, v); } });
        form.addDouble("Passes", "2 passes (second in reverse order) cancel slow drift of conditions",
                new Form.DoubleGet() { public double get() { return sweep.passes; } },
                new Form.DoubleSet() { public void set(double v) { sweep.passes = (int) Math.max(1, v); } });
        form.addDouble("Min load of rows to change, kPa", "Only rows at or above this load (boost rows) get offsets",
                new Form.DoubleGet() { public double get() { return sweep.minLoad; } },
                new Form.DoubleSet() { public void set(double v) { sweep.minLoad = v; } });
        form.addDouble("Min RPM", "", new Form.DoubleGet() { public double get() { return sweep.minRpm; } },
                new Form.DoubleSet() { public void set(double v) { sweep.minRpm = v; } });
        form.addDouble("Max RPM", "", new Form.DoubleGet() { public double get() { return sweep.maxRpm; } },
                new Form.DoubleSet() { public void set(double v) { sweep.maxRpm = v; } });
        form.addDouble("Gear (0 = any)", "", new Form.DoubleGet() { public double get() { return sweep.gear; } },
                new Form.DoubleSet() { public void set(double v) { sweep.gear = (int) v; } });
        form.addDouble("Min torque gain to change a cell, %", "",
                new Form.DoubleGet() { public double get() { return sweep.minGainPct; } },
                new Form.DoubleSet() { public void set(double v) { sweep.minGainPct = v; } });
        form.addDouble("MBT plateau, %", "Prefer the least advance whose torque is within this of the best",
                new Form.DoubleGet() { public double get() { return sweep.mbtPlateauPct; } },
                new Form.DoubleSet() { public void set(double v) { sweep.mbtPlateauPct = v; } });
        form.addDouble("Max step between RPM columns, deg", "",
                new Form.DoubleGet() { public double get() { return sweep.smoothingMaxStepDeg; } },
                new Form.DoubleSet() { public void set(double v) { sweep.smoothingMaxStepDeg = v; } });

        form.section("Limits and guards");
        form.addDouble("Max advance over original in this session, deg", "Hard cap on how far any cell may be advanced",
                new Form.DoubleGet() { public double get() { return sweep.maxAdvanceOverOriginalDeg; } },
                new Form.DoubleSet() { public void set(double v) { sweep.maxAdvanceOverOriginalDeg = v; } });
        form.addDouble("Absolute maximum advance, deg", "", new Form.DoubleGet() { public double get() { return sweep.absoluteMax; } },
                new Form.DoubleSet() { public void set(double v) { sweep.absoluteMax = v; } });
        form.addDouble("Absolute minimum advance, deg", "", new Form.DoubleGet() { public double get() { return sweep.absoluteMin; } },
                new Form.DoubleSet() { public void set(double v) { sweep.absoluteMin = v; } });
        form.addDouble("Knock retard that caps a cell, deg", "ECU knock retard at or above this marks the cell knock-limited",
                new Form.DoubleGet() { public double get() { return sweep.knockRetardTriggerDeg; } },
                new Form.DoubleSet() { public void set(double v) { sweep.knockRetardTriggerDeg = v; } });
        form.addDouble("Knock retard that aborts, deg", "", new Form.DoubleGet() { public double get() { return sweep.knockAbortRetardDeg; } },
                new Form.DoubleSet() { public void set(double v) { sweep.knockAbortRetardDeg = v; } });
        form.addDouble("Cap margin below knocking advance, deg", "", new Form.DoubleGet() { public double get() { return sweep.knockCapMarginDeg; } },
                new Form.DoubleSet() { public void set(double v) { sweep.knockCapMarginDeg = v; } });
        form.addOptionalDouble("Knock level that counts as knock (blank = off)", "Threshold on the knock level channel, firmware units",
                new Form.DoubleGet() { public double get() { return sweep.knockLevelTrigger; } },
                new Form.DoubleSet() { public void set(double v) { sweep.knockLevelTrigger = v; } });
        form.addOptionalDouble("Max AFR at WOT (blank = off)", "Leaner than this at WOT aborts the session",
                new Form.DoubleGet() { public double get() { return sweep.maxWotAfr; } },
                new Form.DoubleSet() { public void set(double v) { sweep.maxWotAfr = v; } });
        form.addDouble("WOT load threshold, % TPS", "", new Form.DoubleGet() { public double get() { return sweep.wotLoadThreshold; } },
                new Form.DoubleSet() { public void set(double v) { sweep.wotLoadThreshold = v; } });
        form.addDouble("Minimum coolant, °C", "", new Form.DoubleGet() { public double get() { return sweep.minCltC; } },
                new Form.DoubleSet() { public void set(double v) { sweep.minCltC = v; } });
        form.addDouble("Overboost abort, kPa", "", new Form.DoubleGet() { public double get() { return sweep.maxBoostKpa; } },
                new Form.DoubleSet() { public void set(double v) { sweep.maxBoostKpa = v; } });
        form.addDouble("Auto end run after idle, s", "0 = manual", new Form.DoubleGet() { public double get() { return sweep.autoEndRunIdleSec; } },
                new Form.DoubleSet() { public void set(double v) { sweep.autoEndRunIdleSec = v; } });

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

    public boolean acknowledged() {
        return acknowledge.isSelected();
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
