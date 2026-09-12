package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.learn.SampleState;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.AutotuneSession;
import io.github.xadmiral.boostautotune.core.session.RunPlan;
import io.github.xadmiral.boostautotune.core.session.RunReport;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.plugin.TuneController;
import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Locale;

/** The driver's cockpit: state, live values, run control. */
public final class AutotunePanel extends JPanel {
    private final TuneController ctl;
    private final JLabel stateLabel = new JLabel(" ");
    private final JLabel planLabel = new JLabel(" ");
    private final JTextArea instructions = new JTextArea(2, 60);
    private final JLabel[] live = new JLabel[10];
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
    private final JButton simPull3 = new JButton("Simulate pull (3rd gear)");
    private final JButton simPull4 = new JButton("Simulate pull (4th gear)");
    private final JCheckBox autoEnd = new JCheckBox("Auto end run", true);
    private final JCheckBox autoApply = new JCheckBox("Auto apply & prepare next", false);
    private final Runnable beforeStart;
    private final Timer refresh;

    public AutotunePanel(final TuneController ctl, Runnable beforeStart) {
        super(new BorderLayout(6, 6));
        this.ctl = ctl;
        this.beforeStart = beforeStart;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD, 15f));
        stateLabel.setAlignmentX(LEFT_ALIGNMENT);
        planLabel.setAlignmentX(LEFT_ALIGNMENT);
        top.add(stateLabel);
        top.add(planLabel);
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setBackground(getBackground());
        instructions.setAlignmentX(LEFT_ALIGNMENT);
        instructions.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 60));
        top.add(instructions);

        JPanel liveRow = new JPanel(new GridLayout(2, 5, 8, 2));
        String[] names = {"RPM", "TPS %", "MAP kPa", "Target kPa", "Duty %", "CLT °C", "Gear", "Sample", "Pulls in run", "Peak kPa"};
        for (int i = 0; i < live.length; i++) {
            JPanel cell = new JPanel(new BorderLayout());
            JLabel n = new JLabel(names[i]);
            n.setFont(n.getFont().deriveFont(10f));
            live[i] = new JLabel("-");
            live[i].setFont(live[i].getFont().deriveFont(Font.BOLD, 16f));
            cell.add(n, BorderLayout.NORTH);
            cell.add(live[i], BorderLayout.CENTER);
            liveRow.add(cell);
        }
        liveRow.setBorder(BorderFactory.createTitledBorder("Live"));
        liveRow.setAlignmentX(LEFT_ALIGNMENT);
        liveRow.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 90));
        top.add(liveRow);
        add(top, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : new JButton[]{startSession, writePlan, startRun, endRun, applyNext, repeat, abort, restore, burn}) {
            buttons.add(b);
        }
        buttons.add(autoEnd);
        buttons.add(autoApply);
        boolean demo = ctl.port() instanceof SimEcuPort;
        simPull3.setVisible(demo);
        simPull4.setVisible(demo);
        buttons.add(simPull3);
        buttons.add(simPull4);

        report.setEditable(false);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel center = new JPanel(new BorderLayout());
        center.add(buttons, BorderLayout.NORTH);
        center.add(new JScrollPane(report), BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        startSession.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onStartSession();
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
                        "Write the boost settings saved at session start back to the ECU?", "Restore original",
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

    private void onStartSession() {
        beforeStart.run();
        updateState();
    }

    public void showReport(RunReport r) {
        report.setText(r == null ? "" : r.summary());
        report.setCaretPosition(0);
    }

    public void updateState() {
        AutotuneSession s = ctl.session();
        SessionState st = ctl.state();
        String phase = s == null ? "" : " / " + s.phase();
        stateLabel.setText("State: " + st + phase + (st == SessionState.ABORTED && s != null ? " - " + s.abortReason() : ""));
        stateLabel.setForeground(st == SessionState.ABORTED ? Color.RED : st == SessionState.DONE ? new Color(0, 130, 0) : Color.BLACK);
        RunPlan plan = s == null ? null : s.plan();
        if (plan == null) {
            planLabel.setText("Configure Setup and Targets, then press Start session.");
            instructions.setText("");
        } else {
            planLabel.setText(plan.title() + (ctl.isEcuPrepared() ? "  [written to ECU]" : "  [NOT yet written to ECU]"));
            StringBuilder sb = new StringBuilder(plan.driverInstructions());
            for (String n : plan.notes) {
                sb.append('\n').append(n);
            }
            instructions.setText(sb.toString());
        }
        boolean hasSession = s != null;
        startSession.setEnabled(st != SessionState.RECORDING);
        writePlan.setEnabled(hasSession && (st == SessionState.READY || st == SessionState.DONE));
        startRun.setEnabled(hasSession && st == SessionState.READY && ctl.isEcuPrepared());
        endRun.setEnabled(hasSession && st == SessionState.RECORDING);
        applyNext.setEnabled(hasSession && st == SessionState.REVIEW);
        repeat.setEnabled(hasSession && st == SessionState.REVIEW);
        abort.setEnabled(hasSession && (st == SessionState.RECORDING || st == SessionState.READY || st == SessionState.REVIEW));
        restore.setEnabled(ctl.original() != null);
        burn.setEnabled(ctl.adapter() != null);
        simPull3.setEnabled(st == SessionState.RECORDING);
        simPull4.setEnabled(st == SessionState.RECORDING);
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
        live[3].setText(Double.isNaN(x.target) ? "-" : String.format(Locale.US, "%.0f", x.target));
        live[4].setText(Double.isNaN(x.duty) ? "-" : String.format(Locale.US, "%.1f", x.duty));
        live[5].setText(String.format(Locale.US, "%.0f", x.clt));
        live[6].setText(Integer.toString(x.gear));
        live[7].setText(st == null ? "-" : st.label());
        live[7].setForeground(st == SampleState.STEADY ? new Color(0, 130, 0) : st == SampleState.OVERBOOST ? Color.RED : Color.DARK_GRAY);
        AutotuneSession s = ctl.session();
        if (s != null) {
            live[8].setText(Integer.toString(s.pullsInRun()));
            live[9].setText(Double.isNaN(s.runPeak()) ? "-" : String.format(Locale.US, "%.1f", s.runPeak()));
        }
    }

    public void dispose() {
        refresh.stop();
    }
}
