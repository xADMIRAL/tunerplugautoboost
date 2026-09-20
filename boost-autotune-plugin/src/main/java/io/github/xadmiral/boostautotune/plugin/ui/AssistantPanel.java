package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.plugin.assistant.AssistantConfig;
import io.github.xadmiral.boostautotune.plugin.assistant.AssistantSession;
import io.github.xadmiral.boostautotune.plugin.assistant.ClaudeTransport;
import io.github.xadmiral.boostautotune.plugin.assistant.HttpTransport;
import io.github.xadmiral.boostautotune.plugin.assistant.PendingChanges;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Assistant tab: a chat with Claude that can read the connected ECU, watch live channels and
 * propose changes. Proposals land in the table at the bottom; the user applies, rejects or
 * restores them. The API key is stored in the user's preferences, the rest with the plugin
 * settings.
 */
public final class AssistantPanel extends JPanel implements AssistantSession.Listener {
    /** Where the panel reports what it wrote. */
    public interface Log {
        void line(String s);
    }

    private static final String[] COLS = {"#", "Change", "Reason", "Status"};

    private final class ChangesModel extends AbstractTableModel {
        List<PendingChanges.Change> rows = new java.util.ArrayList<PendingChanges.Change>();

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        public Object getValueAt(int r, int c) {
            PendingChanges.Change ch = rows.get(r);
            switch (c) {
                case 0:
                    return Integer.valueOf(ch.id);
                case 1:
                    return ch.describe();
                case 2:
                    return ch.reason;
                default:
                    return ch.status + (ch.error.isEmpty() ? "" : ": " + ch.error);
            }
        }
    }

    private final AssistantConfig cfg;
    private final EcuBinding binding;
    private final EcuPort port;
    private final Runnable onChanged;
    private final Log log;
    private final ClaudeTransport transport;
    private AssistantSession session;
    private final JPasswordField keyField = new JPasswordField(22);
    private final JComboBox<String> modelBox = new JComboBox<String>(AssistantConfig.MODEL_CHOICES);
    private final JTextField baseUrlField = new JTextField(22);
    private final JComboBox<String> effortBox = new JComboBox<String>(AssistantConfig.EFFORT_CHOICES);
    private final JCheckBox fallbacksBox = new JCheckBox("Refusal fallbacks (beta)");
    private final JCheckBox autoApplyBox = new JCheckBox("Auto-apply changes");
    private final JTextArea profileArea = new JTextArea(3, 60);
    private final JTextArea transcript = new JTextArea();
    private final JTextArea input = new JTextArea(3, 60);
    private final JButton sendBtn = new JButton("Send");
    private final JButton stopBtn = new JButton("Stop");
    private final JButton newBtn = new JButton("New conversation");
    private final JLabel status = new JLabel(" ");
    private final ChangesModel model = new ChangesModel();
    private final JTable table = new JTable(model);
    private final JButton applySelBtn = new JButton("Apply selected");
    private final JButton applyAllBtn = new JButton("Apply all pending");
    private final JButton rejectBtn = new JButton("Reject selected");
    private final JButton restoreBtn = new JButton("Restore all applied");
    private final JLabel changesStatus = new JLabel(" ");

    public AssistantPanel(AssistantConfig cfg, EcuBinding binding, EcuPort port, Runnable onChanged, Log log) {
        this(cfg, binding, port, onChanged, log, new HttpTransport());
    }

    public AssistantPanel(final AssistantConfig cfg, EcuBinding binding, EcuPort port, Runnable onChanged, Log log, ClaudeTransport transport) {
        super(new BorderLayout(6, 6));
        this.cfg = cfg;
        this.binding = binding;
        this.port = port;
        this.onChanged = onChanged;
        this.log = log;
        this.transport = transport;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // ---- settings ----
        JPanel settings = new JPanel(new BorderLayout(4, 4));
        settings.setBorder(BorderFactory.createTitledBorder("Claude API (the key stays in your user preferences, not in the settings file)"));
        JPanel row1 = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        row1.add(new JLabel("API key:"));
        row1.add(keyField);
        JButton saveKeyBtn = new JButton("Save key");
        row1.add(saveKeyBtn);
        row1.add(new JLabel("  Model:"));
        modelBox.setEditable(true);
        row1.add(modelBox);
        row1.add(new JLabel("  Base URL:"));
        row1.add(baseUrlField);
        row1.add(new JLabel("  Effort:"));
        row1.add(effortBox);
        row1.add(fallbacksBox);
        row1.add(autoApplyBox);
        JButton saveBtn = new JButton("Save settings");
        row1.add(saveBtn);
        settings.add(row1, BorderLayout.NORTH);
        JPanel profile = new JPanel(new BorderLayout(4, 2));
        profile.add(new JLabel("Car profile and notes (goes into every conversation):"), BorderLayout.NORTH);
        profileArea.setLineWrap(true);
        profileArea.setWrapStyleWord(true);
        profile.add(new JScrollPane(profileArea), BorderLayout.CENTER);
        settings.add(profile, BorderLayout.CENTER);
        add(settings, BorderLayout.NORTH);

        // ---- chat ----
        JPanel chat = new JPanel(new BorderLayout(4, 4));
        chat.setBorder(BorderFactory.createTitledBorder("Conversation"));
        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        chat.add(new JScrollPane(transcript), BorderLayout.CENTER);
        JPanel ask = new JPanel(new BorderLayout(4, 4));
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setToolTipText("Ctrl+Enter sends");
        ask.add(new JScrollPane(input), BorderLayout.CENTER);
        JPanel askButtons = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        askButtons.add(sendBtn);
        askButtons.add(stopBtn);
        askButtons.add(newBtn);
        askButtons.add(status);
        ask.add(askButtons, BorderLayout.SOUTH);
        chat.add(ask, BorderLayout.SOUTH);
        stopBtn.setEnabled(false);

        // ---- proposed changes ----
        JPanel changes = new JPanel(new BorderLayout(4, 4));
        changes.setBorder(BorderFactory.createTitledBorder("Proposed changes (ECU RAM; burn in TunerStudio to keep)"));
        table.setRowHeight(22);
        table.setPreferredScrollableViewportSize(new Dimension(600, 22 * 4));
        table.getColumnModel().getColumn(0).setPreferredWidth(30);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
        table.getColumnModel().getColumn(3).setPreferredWidth(140);
        changes.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel changeButtons = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        changeButtons.add(applySelBtn);
        changeButtons.add(applyAllBtn);
        changeButtons.add(rejectBtn);
        changeButtons.add(restoreBtn);
        JButton clearBtn = new JButton("Clear finished");
        changeButtons.add(clearBtn);
        changeButtons.add(changesStatus);
        changes.add(changeButtons, BorderLayout.SOUTH);

        chat.setMinimumSize(new Dimension(0, 120));
        changes.setMinimumSize(new Dimension(0, 100));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chat, changes);
        split.setResizeWeight(0.7);
        add(split, BorderLayout.CENTER);

        // ---- wiring ----
        saveKeyBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                cfg.apiKey = new String(keyField.getPassword()).trim();
                cfg.saveKey();
                status.setText(cfg.apiKey.isEmpty() ? "API key removed" : "API key saved to your user preferences");
            }
        });
        saveBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                applySettings();
            }
        });
        sendBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                send();
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send");
        input.getActionMap().put("send", new javax.swing.AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                send();
            }
        });
        stopBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (session != null) {
                    session.cancel();
                    status.setText("Stopping after the current step...");
                }
            }
        });
        newBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (session != null && !session.isBusy()) {
                    session.reset();
                    transcript.setText("");
                    status.setText("New conversation (the proposed changes are kept)");
                }
            }
        });
        applySelBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                applySelected();
            }
        });
        applyAllBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                applyAll(true);
            }
        });
        rejectBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                rejectSelected();
            }
        });
        restoreBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                restoreAll();
            }
        });
        clearBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (session != null) {
                    session.changes().clearFinished();
                    refreshChanges();
                }
            }
        });
        refresh();
        transcript.setText("Ask about the tune, the live data or a log: Claude reads the ECU through the plugin, proposes changes "
                + "into the table below and never burns. Set the API key first. Ctrl+Enter sends.\n");
    }

    /** Puts the config into the fields. */
    public void refresh() {
        keyField.setText(cfg.apiKey == null ? "" : cfg.apiKey);
        modelBox.setSelectedItem(cfg.model);
        baseUrlField.setText(cfg.baseUrl);
        effortBox.setSelectedItem(cfg.effort == null ? "" : cfg.effort);
        fallbacksBox.setSelected(cfg.fallbacks);
        autoApplyBox.setSelected(cfg.autoApply);
        profileArea.setText(cfg.profile == null ? "" : cfg.profile);
    }

    /** Reads the fields into the config and saves. Returns false on a problem. */
    public boolean applySettings() {
        Object m = modelBox.getSelectedItem();
        String model = m == null ? "" : m.toString().trim();
        if (model.isEmpty()) {
            status.setText("Pick a model");
            return false;
        }
        String url = baseUrlField.getText().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            status.setText("The base URL must start with https://");
            return false;
        }
        cfg.model = model;
        cfg.baseUrl = url;
        Object eff = effortBox.getSelectedItem();
        cfg.effort = eff == null ? "" : eff.toString().trim();
        cfg.fallbacks = fallbacksBox.isSelected();
        cfg.autoApply = autoApplyBox.isSelected();
        cfg.profile = profileArea.getText();
        cfg.apiKey = new String(keyField.getPassword()).trim();
        onChanged.run();
        status.setText("Settings saved" + (cfg.autoApply ? " (auto-apply ON: proposals go straight to the ECU)" : ""));
        return true;
    }

    private AssistantSession session() {
        if (session == null) {
            session = new AssistantSession(cfg, transport, port, binding, this);
        }
        return session;
    }

    public AssistantSession currentSession() {
        return session;
    }

    /** Sends the text in the input box. */
    public void send() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!applySettings()) {
            return;
        }
        if (cfg.apiKey.isEmpty()) {
            status.setText("Paste an API key first");
            return;
        }
        if (session().isBusy()) {
            status.setText("Still working on the previous message");
            return;
        }
        input.setText("");
        append("You: " + text);
        log.line("[assistant] you: " + text);
        session().send(text);
    }

    private void append(String line) {
        transcript.append(line);
        transcript.append("\n");
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    // ---- AssistantSession.Listener (worker thread) ---------------------------------------------

    @Override
    public void assistantText(final String text) {
        onEdt(new Runnable() {
            public void run() {
                append("Claude: " + text);
                log.line("[assistant] claude: " + text);
            }
        });
    }

    @Override
    public void toolActivity(final String line) {
        onEdt(new Runnable() {
            public void run() {
                append("   [" + line + "]");
                log.line("[assistant] tool " + line);
            }
        });
    }

    @Override
    public void status(final String text) {
        onEdt(new Runnable() {
            public void run() {
                status.setText(text);
            }
        });
    }

    @Override
    public void busy(final boolean busy) {
        onEdt(new Runnable() {
            public void run() {
                sendBtn.setEnabled(!busy);
                newBtn.setEnabled(!busy);
                stopBtn.setEnabled(busy);
                if (!busy) {
                    refreshChanges();
                }
            }
        });
    }

    @Override
    public void error(final String message) {
        onEdt(new Runnable() {
            public void run() {
                append("   ! " + message);
                status.setText(message);
                log.line("[assistant] error: " + message);
            }
        });
    }

    @Override
    public void changesUpdated() {
        onEdt(new Runnable() {
            public void run() {
                refreshChanges();
            }
        });
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    // ---- proposed changes ----------------------------------------------------------------------

    public void refreshChanges() {
        model.rows = session == null ? new java.util.ArrayList<PendingChanges.Change>() : session.changes().list();
        model.fireTableDataChanged();
        int pending = session == null ? 0 : session.changes().pendingCount();
        boolean applied = session != null && session.changes().hasApplied();
        applyAllBtn.setEnabled(pending > 0 && port != null);
        restoreBtn.setEnabled(applied && port != null);
        changesStatus.setText(pending == 0 ? " " : pending + " pending");
    }

    private EcuAdapter adapter() {
        return port == null ? null : new EcuAdapter(port, binding);
    }

    private PendingChanges.Change selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= model.rows.size() ? null : model.rows.get(r);
    }

    private void applySelected() {
        PendingChanges.Change c = selected();
        EcuAdapter a = adapter();
        if (c == null || a == null || session == null) {
            changesStatus.setText(a == null ? "No ECU connection" : "Select a change");
            return;
        }
        PendingChanges.Change done = session.changes().apply(a, c.id);
        log.line("[assistant] " + done.status + ": " + done.describe() + (done.error.isEmpty() ? "" : " (" + done.error + ")"));
        refreshChanges();
        changesStatus.setText(done.status + ": " + done.describe());
    }

    /** Applies every pending change; the number written. */
    public int applyAll(boolean confirm) {
        EcuAdapter a = adapter();
        if (a == null || session == null) {
            changesStatus.setText("No ECU connection");
            return 0;
        }
        int pending = session.changes().pendingCount();
        if (pending == 0) {
            return 0;
        }
        if (confirm) {
            int ok = JOptionPane.showConfirmDialog(this, "Write " + pending + " proposed changes to the ECU (RAM)?\n\n"
                    + "The old values are kept for 'Restore all applied'. Burn in TunerStudio when happy.",
                    "Assistant", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return 0;
            }
        }
        int n = session.changes().applyAll(a);
        for (PendingChanges.Change c : session.changes().list()) {
            if (PendingChanges.APPLIED.equals(c.status) || PendingChanges.FAILED.equals(c.status)) {
                log.line("[assistant] " + c.status + ": " + c.describe() + (c.error.isEmpty() ? "" : " (" + c.error + ")"));
            }
        }
        refreshChanges();
        changesStatus.setText(n + " written to ECU RAM. Burn in TunerStudio to keep them.");
        return n;
    }

    private void rejectSelected() {
        PendingChanges.Change c = selected();
        if (c == null || session == null) {
            return;
        }
        session.changes().reject(c.id);
        refreshChanges();
    }

    /** Writes back everything the assistant's changes touched. */
    public void restoreAll() {
        EcuAdapter a = adapter();
        if (a == null || session == null) {
            return;
        }
        List<String> problems = session.changes().restoreAll(a);
        refreshChanges();
        changesStatus.setText(problems.isEmpty() ? "Original values restored" : "Problems: " + problems);
        log.line("[assistant] restored original values" + (problems.isEmpty() ? "" : "; problems: " + problems));
    }

    public void dispose() {
        if (session != null) {
            session.dispose();
        }
    }

    // ---- for tests ----
    public JTextArea transcriptArea() {
        return transcript;
    }

    public JTextArea inputArea() {
        return input;
    }

    public JTable changesTable() {
        return table;
    }

    public JLabel statusLabel() {
        return status;
    }

    public JCheckBox autoApplyCheckbox() {
        return autoApplyBox;
    }
}
