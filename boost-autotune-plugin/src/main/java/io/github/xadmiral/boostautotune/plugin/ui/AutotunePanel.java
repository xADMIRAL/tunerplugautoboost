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
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Locale;

/**
 * The driver's cockpit: mode, a state pill, the five steps of a session with the current one
 * highlighted, the live values, and two rows of buttons: the main ones (the next thing to press
 * gets an accent frame) and the rest.
 */
public final class AutotunePanel extends JPanel {
    private final TuneController ctl;
    private final JComboBox<TuneMode> modeCombo = new JComboBox<TuneMode>(TuneMode.values());
    private final JLabel stateLabel = new JLabel(" ");
    private final JLabel phaseLabel = new JLabel(" ");
    private final Ui.Steps steps = new Ui.Steps("Start session", "Write plan", "Start run", "End run", "Apply & next");
    private final JLabel planLabel = new JLabel(" ");
    private final JTextArea progressLabel = new JTextArea(" ");
    private final JTextArea instructions = new JTextArea(2, 60);
    private static final String[] LIVE_NAMES = {"RPM", "TPS %", "MAP kPa", "Boost tgt", "Boost duty", "CLT °C", "Gear",
            "VVT angle", "VVT target", "Advance", "Knock rtd", "AFR", "Sample", "Pulls / peak", "MAT °C", "Anti-lag", "Knock %"};
    private final JLabel[] live = new JLabel[LIVE_NAMES.length];
    private final JPanel[] liveCells = new JPanel[LIVE_NAMES.length];
    private Color cardBg;
    private final JTextArea report = new JTextArea(7, 80);
    private final JButton reportSize = new JButton("Expand");
    private static final int REPORT_ROWS_SMALL = 7;
    private static final int REPORT_ROWS_LARGE = 22;
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
    private final JButton[] primaries;
    private final Runnable beforeStart;
    private final Timer refresh;
    private final JPanel buttonRow;
    private final JPanel secondaryRow;

    public AutotunePanel(final TuneController ctl, Runnable beforeStart) {
        super(new BorderLayout(6, 6));
        this.ctl = ctl;
        this.beforeStart = beforeStart;
        setBorder(Ui.tabPadding());
        primaries = new JButton[]{startSession, writePlan, startRun, endRun, applyNext, burn, restore};

        // a vertical stack that gives every row its preferred height: nothing gets squeezed when the
        // text is large or the window narrow
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, 0, 3, 0);
        JPanel modeRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        JLabel modeCaption = new JLabel("Mode");
        modeCaption.setFont(modeCaption.getFont().deriveFont(Font.BOLD));
        modeRow.add(modeCaption);
        modeRow.add(modeCombo);
        modeRow.add(startSession);
        top.add(modeRow, gc);
        JPanel stateRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 2));
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD, 14f));
        stateRow.add(stateLabel);
        phaseLabel.setForeground(Palette.dimTextOn(Palette.panel()));
        stateRow.add(phaseLabel);
        top.add(stateRow, gc);
        top.add(steps, gc);
        top.add(planLabel, gc);
        // a wrapping line: the waiting / silent-channel hints are long and must stay readable
        progressLabel.setEditable(false);
        progressLabel.setLineWrap(true);
        progressLabel.setWrapStyleWord(true);
        progressLabel.setOpaque(false);
        progressLabel.setFont(new JLabel().getFont().deriveFont(Font.BOLD));
        top.add(progressLabel, gc);
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setOpaque(false);
        instructions.setForeground(Palette.text());
        top.add(instructions, gc);

        // the live cells paint their own background and text colours: the host's theme (TunerStudio is
        // dark) must not be able to hide them
        JPanel liveRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        Color card = Palette.cardOn(Palette.panel());
        cardBg = card;
        for (int i = 0; i < live.length; i++) {
            JPanel cell = new JPanel(new BorderLayout(0, 1));
            cell.setOpaque(true);
            cell.setBackground(card);
            cell.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Palette.dimTextOn(card), 1),
                    BorderFactory.createEmptyBorder(3, 8, 3, 8)));
            JLabel n = new JLabel(LIVE_NAMES[i]);
            n.setFont(n.getFont().deriveFont(10f));
            n.setForeground(Palette.dimTextOn(card));
            live[i] = new JLabel("-");
            live[i].setFont(live[i].getFont().deriveFont(Font.BOLD, 16f));
            live[i].setForeground(Palette.textOn(card));
            cell.add(n, BorderLayout.NORTH);
            cell.add(live[i], BorderLayout.CENTER);
            liveCells[i] = cell;
            liveRow.add(cell);
        }
        JPanel liveBox = new JPanel(new BorderLayout());
        liveBox.add(Ui.sectionHeader("Live"), BorderLayout.NORTH);
        liveBox.add(liveRow, BorderLayout.CENTER);
        top.add(liveBox, gc);
        add(top, BorderLayout.NORTH);

        buttonRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        for (JButton b : new JButton[]{writePlan, startRun, endRun, applyNext}) {
            buttonRow.add(b);
        }
        secondaryRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        for (JButton b : new JButton[]{repeat, abort, restore, burn}) {
            secondaryRow.add(b);
        }
        secondaryRow.add(autoEnd);
        secondaryRow.add(autoApply);
        boolean demo = ctl.port() instanceof SimEcuPort;
        for (JButton b : new JButton[]{simPull3, simPull4, simDrive, simAls}) {
            b.setVisible(demo);
            secondaryRow.add(b);
        }

        report.setEditable(false);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        // the report is a compact box under the buttons (a few rows, Expand for more), not a white
        // field that swallows the rest of the window
        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints rc = new GridBagConstraints();
        rc.gridx = 0;
        rc.weightx = 1;
        rc.fill = GridBagConstraints.HORIZONTAL;
        rc.anchor = GridBagConstraints.NORTHWEST;
        rows.add(buttonRow, rc);
        rows.add(secondaryRow, rc);
        JPanel reportHeader = new JPanel(new BorderLayout(6, 0));
        reportHeader.add(Ui.sectionHeader("Last run report"), BorderLayout.CENTER);
        reportSize.setMargin(new Insets(1, 8, 1, 8));
        reportHeader.add(reportSize, BorderLayout.EAST);
        rc.insets = new Insets(4, 0, 0, 0);
        rows.add(reportHeader, rc);
        final JScrollPane reportScroll = new JScrollPane(report);
        rc.insets = new Insets(2, 0, 0, 0);
        rows.add(reportScroll, rc);
        // NORTH of a BorderLayout: the stack keeps its preferred height whatever the window size,
        // so a short window never squeezes the wrapped button rows
        JPanel center = new JPanel(new BorderLayout());
        center.add(rows, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        reportSize.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                boolean small = report.getRows() == REPORT_ROWS_SMALL;
                report.setRows(small ? REPORT_ROWS_LARGE : REPORT_ROWS_SMALL);
                reportSize.setText(small ? "Shrink" : "Expand");
                reportScroll.setPreferredSize(null);
                rows.revalidate();
                rows.repaint();
            }
        });

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

    public JLabel stateLabel() {
        return stateLabel;
    }

    /** The live feed / run progress line. */
    public JTextArea progressLabel() {
        return progressLabel;
    }

    /** Live value labels and their cells (layout checks). */
    public JLabel[] liveLabels() {
        return live;
    }

    public JPanel[] liveCells() {
        return liveCells;
    }

    /** The row with the main run buttons. */
    public JPanel buttonRow() {
        return buttonRow;
    }

    /** The row with the rest: repeat, abort, undo, burn, automation and the demo buttons. */
    public JPanel secondaryRow() {
        return secondaryRow;
    }

    public Ui.Steps stepsBar() {
        return steps;
    }

    /** The report area (layout checks). */
    public JTextArea reportArea() {
        return report;
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

    /** Which of the five steps is the one to do now; 5 = all done. */
    private int currentStep(SessionState st) {
        if (ctl.driver() == null || st == SessionState.ABORTED) {
            return 0;
        }
        switch (st) {
            case READY: return ctl.isEcuPrepared() ? 2 : 1;
            case RECORDING: return 3;
            case REVIEW: return 4;
            case DONE: return 5;
            default: return 0;
        }
    }

    public void updateState() {
        SessionState st = ctl.state();
        Ui.pill(stateLabel, st.name(), Ui.stateColor(st));
        String phase = ctl.driver() == null ? "" : ctl.phase();
        phaseLabel.setText(st == SessionState.ABORTED ? "Aborted: " + ctl.abortReason() : phase);
        phaseLabel.setForeground(st == SessionState.ABORTED ? Palette.badOn(Palette.panel()) : Palette.dimTextOn(Palette.panel()));
        steps.setActive(currentStep(st));
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
        // the next thing to press gets the accent frame
        JButton next;
        if (!hasSession) {
            next = startSession;
        } else {
            switch (st) {
                case READY: next = ctl.isEcuPrepared() ? startRun : writePlan; break;
                case RECORDING: next = endRun; break;
                case REVIEW: next = applyNext; break;
                case DONE: next = burn; break;
                case ABORTED: next = ctl.hasOriginal() ? restore : startSession; break;
                default: next = startSession;
            }
        }
        for (JButton b : primaries) {
            if (b == next) {
                Ui.primary(b);
            } else {
                Ui.secondary(b);
            }
        }
    }

    private void refreshLive() {
        String progress = ctl.progress();
        if (!progress.equals(progressLabel.getText())) {
            progressLabel.setText(progress);
            Color bg = Palette.panel();
            boolean warn = progress.startsWith("Waiting") || progress.contains("silent:") || progress.contains("Aborted") || progress.contains("error");
            progressLabel.setForeground(warn ? Palette.warnOn(bg) : Palette.text());
        }
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
        Color card = cardBg;
        live[10].setForeground(!Double.isNaN(x.knockRetard) && x.knockRetard > 0 ? Palette.badOn(card) : Palette.textOn(card));
        live[11].setText(fmt(x.afr, "%.1f"));
        live[12].setText(st == null ? "-" : st.label());
        live[12].setForeground(st == SampleState.STEADY ? Palette.goodOn(card) : st == SampleState.OVERBOOST ? Palette.badOn(card) : Palette.dimTextOn(card));
        double peak = ctl.runPeak();
        live[13].setText(ctl.pullsInRun() + (Double.isNaN(peak) ? "" : String.format(Locale.US, " / %.0f", peak)));
        live[14].setText(fmt(x.mat, "%.0f"));
        live[14].setForeground(!Double.isNaN(x.mat) && x.mat >= 70 ? Palette.badOn(card) : Palette.textOn(card));
        live[15].setText(x.alsActive ? "ACTIVE" : "-");
        live[15].setForeground(x.alsActive ? Palette.warnOn(card) : Palette.textOn(card));
        live[16].setText(fmt(x.knock, "%.0f"));
        live[16].setForeground(!Double.isNaN(x.knock) && x.knock >= 90 ? Palette.badOn(card) : Palette.textOn(card));
    }

    private static String fmt(double v, String f) {
        return Double.isNaN(v) ? "-" : String.format(Locale.US, f, v);
    }

    public void dispose() {
        refresh.stop();
    }
}
