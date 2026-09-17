package io.github.xadmiral.boostautotune.plugin.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/** Plain text log of everything the plugin did. */
public final class LogPanel extends JPanel {
    private final JTextArea area = new JTextArea();

    public LogPanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(area), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 4));
        JButton save = new JButton("Save log...");
        JButton clear = new JButton("Clear");
        buttons.add(save);
        buttons.add(clear);
        add(buttons, BorderLayout.NORTH);
        clear.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                area.setText("");
            }
        });
        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser();
                if (fc.showSaveDialog(LogPanel.this) == JFileChooser.APPROVE_OPTION) {
                    try {
                        Writer w = new OutputStreamWriter(new FileOutputStream(fc.getSelectedFile()), "UTF-8");
                        try {
                            w.write(area.getText());
                        } finally {
                            w.close();
                        }
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(LogPanel.this, ex.getMessage(), "Boost Autotune", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });
    }

    public void append(String line) {
        area.append(line);
        area.append("\n");
        area.setCaretPosition(area.getDocument().getLength());
    }

    public String text() {
        return area.getText();
    }
}
