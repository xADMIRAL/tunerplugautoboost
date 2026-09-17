package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.plugin.TuneController;
import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import io.github.xadmiral.boostautotune.plugin.mode.TuneMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Locale;

/** The driver's cockpit: mode, state, live values, run control. */
public final class AutotunePanel extends JPanel {
    private final TuneController ctl;
    private final JComboBox<TuneMode> modeCombo = new JComboBox<TuneMode>(TuneMode.values());
    private final JLabel stateLabel = new JLabel(" ");
    private final JLabel planLabel = new JLabel(" ");
    private final JTextArea instructions = new JTextArea(2, 60);
    private static final String[] LIVE_NAMES = {"RPM", "TPS %", "MAP kPa", "Boost tgt", "Boost duty", "CLT °C", "Gear",
            "VVT angle", "VVT target", "Advance", "Knock rtd", "AFR", "Sample", "Pulls / peak", "MAT °C", "Anti-lag"};
    private final JLabel[] live = new JLabel[LIVE_NAMES.length];
    private final JPanel[] liveCells = new JPanel[LIVE_NAMES.length];
    private final JTextArea report = new JTextArea(14, 80);
    private final JButton startSession = new JButton("Start session");
    private final JButton writePlan = new JButton("Write plan to ECU");
    private final JButton startRun = new JButton("Start run");
    private final JButton endRun = new JButton("End run");
    private final JButton applyNext = new JButton("Apply & prepare next run");
    private final JButton repeat = new JButton("Repeat run");
    private final JButton abort = new JButton("Abort");
    private final JButton restore = new JButton("Restore original");
    private final JButton burn = new JButton("Burn");
    private final JButton simPull3 = new JButton("Simulate pull (3rd)");
    private final JButton simPull4 = new JButton("Simulate pull (4th)");
    private final JButton simDrive = new JButton("Simulate 60 s drive");
    private final JButton simAls = new JButton("Simulate anti-lag (3 lifts)");
    private final JCheckBox autoEnd = new JCheckBox("Auto end run", true);
    private final JCheckBox autoApply = new JCheckBox("Auto apply & prepare next", false);
    private final Runnable beforeStart;
    private final Timer refresh;
    private JPanel buttonRow;

    public AutotunePanel(final TuneController ctl, Runnable beforeStart) {
        super(new BorderLayout(6, 6));
        this.ctl = ctl;
        this.beforeStart = beforeStart;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // a vertical stack that gives every row its preferred height: no maximum sizes, nothing gets
        // squeezed when the text is large or the window narrow
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, 0, 2, 0);
        JPanel modeRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        modeRow.add(new JLabel("Mode:"));
        modeRow.add(modeCombo);
        modeRow.add(startSession);
        top.add(modeRow, gc);
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD, 15f));
        top.add(stateLabel, gc);
        top.add(planLabel, gc);
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setBackground(getBackground());
        top.add(instructions, gc);

        JPanel liveRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 2));
        for (int i = 0; i < live.length; i++) {
            JPanel cell = new JPanel(new BorderLayout());
            JLabel n = new JLabel(LIVE_NAMES[i]);
            n.setFont(n.getFont().deriveFont(10f));
            live[i] = new JLabel("-");
            live[i].setFont(live[i].getFont().deriveFont(Font.BOLD, 15f));
            cell.add(n, BorderLayout.NORTH);
            cell.add(live[i], BorderLayout.CENTER);
            liveCells[i] = cell;
            liveRow.add(cell);
        }
        liveRow.setBorder(BorderFactory.createTitledBorder("Live"));
        top.add(liveRow, gc);
        add(top, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        for (JButton b : new JButton[]{writePlan, startRun, endRun, applyNext, repeat, abort, restore, burn}) {
            buttons.add(b);
        }
        buttons.add(autoEnd);
        buttons.add(autoApply);
        boolean demo = ctl.port() instanceof SimEcuPort;
        simPull3.setVisible(demo);
        simPull4.setVisible(demo);
        simDrive.setVisible(demo);
        buttons.add(simPull3);
        buttons.add(simPull4);
        buttons.add(simDrive);
        simAls.setVisible(demo);
        buttons.add(simAls);

        report.setEditable(false);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        buttonRow = buttons;
        JPanel center = new JPanel(new BorderLayout());
        center.add(buttons, BorderLayout.NORTH);
        center.add(new JScrollPane(report), BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        startSession.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                AutotunePanel.this.beforeStart.run();
                updateState();
            }
        });
        writePlan.addActionListener(safe(new Action() { public void run() throws Exception { ctl.writePlanToEcu(); } }));
        startRun.addActionListener(safe(new Action() { public void run() throws Exception { ctl.startRun(); } }));
        endRun.addActionListener(safe(new Action() { public void run() throws Exception { ctl.endRun(); } }));
        applyNext.addActionListener(safe(new Action() { public void run() throws Exception { ctl.applyAndPrepareNext(); } }));
        repeat.addActionListener(safe(new Action() { public void run() throws Exception { ctl.repeatRun(); } }));
        abort.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ctl.abort("Stopped by user");
            }
        });
        restore.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int r = JOptionPane.showConfirmDialog(AutotunePanel.this,
                        "Write the settings saved at session start back to the ECU?", "Restore original",
                        JOptionPane.OK_CANCEL_OPTION);
                if (r == JOptionPane.OK_OPTION) {
                    safe(new Action() { public void run() throws Exception { ctl.restoreOriginal(); } }).actionPerformed(e);
                }
            }
        });
        burn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int r = JOptionPane.showConfirmDialog(AutotunePanel.this,
                        "Burn the current ECU RAM values to flash?", "Burn", JOptionPane.OK_CANCEL_OPTION);
                if (r == JOptionPane.OK_OPTION) {
                    safe(new Action() { public void run() throws Exception { ctl.burn(); } }).actionPerformed(e);
                }
            }
        });
        autoEnd.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ctl.setAutoEndRun(autoEnd.isSelected());
            }
        });
        autoApply.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ctl.setAutoApply(autoApply.isSelected());
            }
        });
        simPull3.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ((SimEcuPort) ctl.port()).simulatePull(3, 4);
            }
        });
        simPull4.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ((SimEcuPort) ctl.port()).simulatePull(4, 4);
            }
        });
        simDrive.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ((SimEcuPort) ctl.port()).simulateDrive(60, 8);
            }
        });
        simAls.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ((SimEcuPort) ctl.port()).simulateAntilag(3, 4);
            }
        });
        ctl.setAutoEndRun(autoEnd.isSelected());
        ctl.setAutoApply(autoApply.isSelected());

        refresh = new Timer(120, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refreshLive();
            }
        });
        refresh.start();
        updateState();
    }

    private interface Action {
        void run() throws Exception;
    }

    private ActionListener safe(final Action a) {
        return new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                try {
                    a.run();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(AutotunePanel.this, ex.getMessage(), "Boost Autotune", JOptionPane.ERROR_MESSAGE);
                }
                updateState();
            }
        };
    }

    /** Live value labels and their cells (layout checks). */
    public JLabel[] liveLabels() {
        return live;
    }

    public JPanel[] liveCells() {
        return liveCells;
    }

    public JPanel buttonRow() {
        return buttonRow;
    }

    public TuneMode selectedMode() {
        return (TuneMode) modeCombo.getSelectedItem();
    }

    public void setSelectedMode(TuneMode m) {
        modeCombo.setSelectedItem(m);
    }

    public void showReport() {
        report.setText(ctl.lastReportText());
        report.setCaretPosition(0);
    }

    public void updateState() {
        SessionState st = ctl.state();
        String phase = ctl.driver() == null ? "" : " / " + ctl.phase();
        stateLabel.setText("State: " + st + phase + (st == SessionState.ABORTED ? " - " + ctl.abortReason() : ""));
        stateLabel.setForeground(st == SessionState.ABORTED ? Color.RED : st == SessionState.DONE ? new Color(0, 130, 0) : Color.BLACK);
        if (ctl.driver() == null) {
            planLabel.setText("Pick a mode, check its settings tab and Setup, then press Start session.");
            instructions.setText("");
        } else {
            planLabel.setText(ctl.mode().label() + "  |  " + ctl.planTitle()
                    + (ctl.isEcuPrepared() ? "  [written to ECU]" : "  [NOT yet written to ECU]"));
            StringBuilder sb = new StringBuilder(ctl.instructions());
            for (String n : ctl.planNotes()) {
                sb.append('\n').append(n);
            }
            instructions.setText(sb.toString());
        }
        boolean hasSession = ctl.driver() != null;
        boolean active = st == SessionState.RECORDING || st == SessionState.READY || st == SessionState.REVIEW;
        modeCombo.setEnabled(!active);
        startSession.setEnabled(st != SessionState.RECORDING);
        writePlan.setEnabled(hasSession && (st == SessionState.READY || st == SessionState.DONE));
        startRun.setEnabled(hasSession && st == SessionState.READY && ctl.isEcuPrepared());
        endRun.setEnabled(hasSession && st == SessionState.RECORDING);
        applyNext.setEnabled(hasSession && st == SessionState.REVIEW);
        repeat.setEnabled(hasSession && st == SessionState.REVIEW);
        abort.setEnabled(hasSession && active);
        restore.setEnabled(ctl.hasOriginal());
        burn.setEnabled(ctl.adapter() != null);
        boolean vvtPid = ctl.mode() == TuneMode.VVT_PID;
        boolean als = ctl.mode() == TuneMode.ANTILAG;
        simPull3.setEnabled(st == SessionState.RECORDING && !vvtPid && !als);
        simPull4.setEnabled(st == SessionState.RECORDING && !vvtPid && !als);
        simDrive.setEnabled(st == SessionState.RECORDING && vvtPid);
        simAls.setEnabled(st == SessionState.RECORDING && als);
    }

    private void refreshLive() {
        Sample x = ctl.lastSample();
        SampleState st = ctl.lastSampleState();
        if (x == null) {
            return;
        }
        live[0].setText(String.format(Locale.US, "%.0f", x.rpm));
        live[1].setText(String.format(Locale.US, "%.1f", x.tps));
        live[2].setText(String.format(Locale.US, "%.1f", x.map));
        live[3].setText(fmt(x.target, "%.0f"));
        live[4].setText(fmt(x.duty, "%.1f"));
        live[5].setText(String.format(Locale.US, "%.0f", x.clt));
        live[6].setText(Integer.toString(x.gear));
        live[7].setText(fmt(x.vvtAngle, "%.1f"));
        live[8].setText(fmt(x.vvtTarget, "%.1f"));
        live[9].setText(fmt(x.advance, "%.1f"));
        live[10].setText(fmt(x.knockRetard, "%.1f"));
        live[10].setForeground(!Double.isNaN(x.knockRetard) && x.knockRetard > 0 ? Color.RED : Color.BLACK);
        live[11].setText(fmt(x.afr, "%.1f"));
        live[12].setText(st == null ? "-" : st.label());
        live[12].setForeground(st == SampleState.STEADY ? new Color(0, 130, 0) : st == SampleState.OVERBOOST ? Color.RED : Color.DARK_GRAY);
        double peak = ctl.runPeak();
        live[13].setText(ctl.pullsInRun() + (Double.isNaN(peak) ? "" : String.format(Locale.US, " / %.0f", peak)));
        live[14].setText(fmt(x.mat, "%.0f"));
        live[14].setForeground(!Double.isNaN(x.mat) && x.mat >= 70 ? Color.RED : Color.BLACK);
        live[15].setText(x.alsActive ? "ACTIVE" : "-");
        live[15].setForeground(x.alsActive ? new Color(200, 90, 0) : Color.BLACK);
    }

    private static String fmt(double v, String f) {
        return Double.isNaN(v) ? "-" : String.format(Locale.US, f, v);
    }

    public void dispose() {
        refresh.stop();
    }
}
