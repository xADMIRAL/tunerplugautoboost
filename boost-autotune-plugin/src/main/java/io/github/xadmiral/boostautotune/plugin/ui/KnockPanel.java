package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.knock.KnockCalConfig;

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

/** Settings of the knock sensor calibration, with the one thing the driver must promise first. */
public final class KnockPanel extends JPanel {
    private final KnockCalConfig cfg;
    private final Form form = new Form();
    private final JLabel status = new JLabel(" ");
    private final JCheckBox acknowledge = new JCheckBox(
            "I understand: the timing map for these pulls does not knock (a few degrees safe) and the engine is healthy.");
    private final Runnable onChanged;

    public KnockPanel(final KnockCalConfig cfg, Runnable onChanged) {
        super(new BorderLayout(6, 6));
        this.cfg = cfg;
        this.onChanged = onChanged;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JTextArea intro = new JTextArea(
                "Knock sensor calibration: a run is 2-3 full-throttle pulls on a timing map you trust. Per RPM bin of the ECU's knock "
                        + "threshold curve the knock input is collected (per cylinder when the ECU reports it) and treated as the engine's noise. "
                        + "First the gains are set so the noisiest cylinder reads the target level and the cylinders match; then the threshold "
                        + "curve is placed a margin above the noise and checked on more pulls: a bin whose samples cross it, or where the ECU "
                        + "pulled timing, is raised. Isolated spikes are reported as possible knock and kept out of the noise. "
                        + "This sets WHERE the ECU listens; it cannot tell a bad sensor or a knocking engine from a noisy one - listen too.");
        intro.setEditable(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        intro.setForeground(new Color(140, 0, 0));
        intro.setBackground(getBackground());
        JPanel top = new JPanel(new BorderLayout());
        top.add(intro, BorderLayout.CENTER);
        top.add(acknowledge, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        form.section("What you want");
        form.addDouble("Noise level at the noisiest RPM, % of full scale", "The loudest bin's 95th percentile after the gains are set (MS3: about 30-50 % leaves room for knock)",
                new Form.DoubleGet() { public double get() { return cfg.noiseTargetPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.noiseTargetPct = v; } });
        form.addDouble("... accepted from, %", "Gains are left alone while the noise sits inside this band",
                new Form.DoubleGet() { public double get() { return cfg.noiseBandLowPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.noiseBandLowPct = v; } });
        form.addDouble("... up to, %", "", new Form.DoubleGet() { public double get() { return cfg.noiseBandHighPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.noiseBandHighPct = v; } });
        form.addDouble("Threshold margin above the noise, %", "Threshold = noise p95 x (1 + margin); also at least 3 points above the loudest plain sample",
                new Form.DoubleGet() { public double get() { return cfg.marginPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.marginPct = v; } });
        form.addBool("Balance the cylinders with their own gains", "Needs per-cylinder knock channels (knock_cyl01...) and per-cylinder knock in the ECU",
                new Form.BoolGet() { public boolean get() { return cfg.balanceCylinders; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.balanceCylinders = v; } });
        form.addDouble("Cylinder imbalance that gets corrected, %", "A cylinder this much quieter than the loudest one gets more gain",
                new Form.DoubleGet() { public double get() { return cfg.cylinderImbalancePct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.cylinderImbalancePct = v; } });

        form.section("Which samples count");
        form.addBool("Load and RPM window from the ECU's knock settings", "knk_minload / knk_lorpm / knk_hirpm on MS3; the fields below are used when off or unbound",
                new Form.BoolGet() { public boolean get() { return cfg.windowFromEcu; } },
                new Form.BoolSet() { public void set(boolean v) { cfg.windowFromEcu = v; } });
        form.addDouble("Minimum ignition load", "kPa on a speed-density MS3 (the knock control's own minimum load)",
                new Form.DoubleGet() { public double get() { return cfg.minLoad; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minLoad = v; } });
        form.addDouble("WOT threshold, % TPS (no load channel)", "", new Form.DoubleGet() { public double get() { return cfg.wotTps; } },
                new Form.DoubleSet() { public void set(double v) { cfg.wotTps = v; } });
        form.addDouble("Min RPM", "", new Form.DoubleGet() { public double get() { return cfg.minRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minRpm = v; } });
        form.addDouble("Max RPM", "", new Form.DoubleGet() { public double get() { return cfg.maxRpm; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxRpm = v; } });
        form.addDouble("Minimum coolant, °C", "A cold engine is noisier (the ECU has its own cold scaling)",
                new Form.DoubleGet() { public double get() { return cfg.minCltC; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minCltC = v; } });
        form.addDouble("Samples needed per RPM bin", "", new Form.DoubleGet() { public double get() { return cfg.minSamplesPerBin; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minSamplesPerBin = (int) Math.max(1, v); } });

        form.section("Knobs and guards");
        form.addDouble("Max gain change per run (factor)", "A gain never moves more than this factor in one run",
                new Form.DoubleGet() { public double get() { return cfg.maxGainFactorPerRun; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxGainFactorPerRun = v; } });
        form.addDouble("Lowest threshold, %", "", new Form.DoubleGet() { public double get() { return cfg.minThresholdPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.minThresholdPct = v; } });
        form.addDouble("Highest threshold, %", "", new Form.DoubleGet() { public double get() { return cfg.maxThresholdPct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxThresholdPct = v; } });
        form.addDouble("Samples over the threshold tolerated per bin, %", "One stray sample per bin is always tolerated; more, or any ECU knock retard, raises the bin",
                new Form.DoubleGet() { public double get() { return cfg.maxFalsePct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxFalsePct = v; } });
        form.addDouble("Raise on a false trigger, %", "", new Form.DoubleGet() { public double get() { return cfg.raiseOnFalsePct; } },
                new Form.DoubleSet() { public void set(double v) { cfg.raiseOnFalsePct = v; } });
        form.addDouble("Spike = this many times the bin median", "Samples above it (plus 3 points) count as possible knock, not noise",
                new Form.DoubleGet() { public double get() { return cfg.spikeFactor; } },
                new Form.DoubleSet() { public void set(double v) { cfg.spikeFactor = v; } });
        form.addDouble("Good runs required", "", new Form.DoubleGet() { public double get() { return cfg.runsRequired; } },
                new Form.DoubleSet() { public void set(double v) { cfg.runsRequired = (int) Math.max(1, v); } });
        form.addDouble("Overboost abort, kPa", "", new Form.DoubleGet() { public double get() { return cfg.maxBoostKpa; } },
                new Form.DoubleSet() { public void set(double v) { cfg.maxBoostKpa = v; } });
        form.addDouble("Auto end run after idle, s", "0 = manual", new Form.DoubleGet() { public double get() { return cfg.autoEndRunIdleSec; } },
                new Form.DoubleSet() { public void set(double v) { cfg.autoEndRunIdleSec = v; } });

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
}
