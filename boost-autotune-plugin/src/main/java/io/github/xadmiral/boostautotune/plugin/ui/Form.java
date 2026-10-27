package io.github.xadmiral.boostautotune.plugin.ui;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tiny two-column form builder with refresh/apply between fields and a model. */
public final class Form {
    public interface DoubleGet { double get(); }
    public interface DoubleSet { void set(double v); }
    public interface BoolGet { boolean get(); }
    public interface BoolSet { void set(boolean v); }
    public interface TextGet { String get(); }
    public interface TextSet { void set(String v); }

    private interface Field {
        void refresh();
        void apply() throws IllegalArgumentException;
    }

    private final JPanel panel = new JPanel(new GridBagLayout());
    private final List<Field> fields = new ArrayList<Field>();
    private int row;

    {
        // a filler far below the last row soaks up spare height: the rows stay at the top of the
        // scroll pane instead of floating in its middle
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 100000;
        c.gridwidth = 2;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), c);
    }
    /** Rows added while this is set belong to the advanced set, hidden until asked for. */
    private boolean advanced;
    private final List<JComponent> advancedComps = new ArrayList<JComponent>();
    private JCheckBox toggle;
    private boolean toggleApplied;

    public JPanel panel() {
        return panel;
    }

    public void section(String title) {
        JLabel l = Ui.sectionHeader(title);
        GridBagConstraints c = gbc(0, row++, 2);
        c.insets = new Insets(4, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(l, c);
        if (advanced) {
            advancedComps.add(l);
        }
    }

    /** Rows added from here on are advanced settings (hidden behind the toggle). */
    public void beginAdvanced() {
        advanced = true;
    }

    public void endAdvanced() {
        advanced = false;
    }

    public boolean hasAdvanced() {
        return !advancedComps.isEmpty();
    }

    public void setAdvancedVisible(boolean v) {
        for (JComponent c : advancedComps) {
            c.setVisible(v);
        }
        if (toggle != null && toggle.isSelected() != v) {
            toggle.setSelected(v);
        }
        panel.revalidate();
        panel.repaint();
    }

    public boolean advancedVisible() {
        return toggle == null || toggle.isSelected();
    }

    /**
     * Adds the "Advanced settings" switch as the first row: everything marked advanced stays
     * hidden until it is ticked. The hiding is applied on the first {@link #refresh()}.
     */
    public JCheckBox addAdvancedToggle() {
        toggle = new JCheckBox("Advanced settings", false);
        toggle.setToolTipText("Show every setting; the defaults are fine to start with");
        GridBagConstraints c = gbc(0, row++, 2);
        c.insets = new Insets(2, 0, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(toggle, c);
        toggle.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                setAdvancedVisible(toggle.isSelected());
            }
        });
        return toggle;
    }

    public JTextField addDouble(final String label, String tooltip, final DoubleGet get, final DoubleSet set) {
        final JTextField tf = new JTextField(7);
        tf.setToolTipText(tooltip);
        add(label, tf, tooltip);
        fields.add(new Field() {
            public void refresh() {
                tf.setText(fmt(get.get()));
            }

            public void apply() {
                String t = tf.getText().trim().replace(',', '.');
                try {
                    set.set(Double.parseDouble(t));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + ": '" + tf.getText() + "' is not a number");
                }
            }
        });
        return tf;
    }

    /** A number field where an empty box means "not set" (NaN). */
    public JTextField addOptionalDouble(final String label, String tooltip, final DoubleGet get, final DoubleSet set) {
        final JTextField tf = new JTextField(7);
        tf.setToolTipText(tooltip);
        add(label, tf, tooltip);
        fields.add(new Field() {
            public void refresh() {
                double v = get.get();
                tf.setText(Double.isNaN(v) ? "" : fmt(v));
            }

            public void apply() {
                String t = tf.getText().trim().replace(',', '.');
                if (t.isEmpty()) {
                    set.set(Double.NaN);
                    return;
                }
                try {
                    set.set(Double.parseDouble(t));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + ": '" + tf.getText() + "' is not a number");
                }
            }
        });
        return tf;
    }

    public JTextField addText(String label, String tooltip, final TextGet get, final TextSet set) {
        final JTextField tf = new JTextField(10);
        tf.setToolTipText(tooltip);
        add(label, tf, tooltip);
        fields.add(new Field() {
            public void refresh() {
                tf.setText(get.get());
            }

            public void apply() {
                set.set(tf.getText().trim());
            }
        });
        return tf;
    }

    public JCheckBox addBool(String label, String tooltip, final BoolGet get, final BoolSet set) {
        final JCheckBox cb = new JCheckBox();
        cb.setToolTipText(tooltip);
        add(label, cb, tooltip);
        fields.add(new Field() {
            public void refresh() {
                cb.setSelected(get.get());
            }

            public void apply() {
                set.set(cb.isSelected());
            }
        });
        return cb;
    }

    public void addComponent(String label, JComponent comp) {
        add(label, comp, null);
    }

    /** Width of the label column in pixels: long labels wrap instead of pushing the fields off screen. */
    static final int LABEL_WIDTH_PX = 280;

    private void add(String label, JComponent comp, String tooltip) {
        JLabel l = new JLabel("<html><body style='width: " + LABEL_WIDTH_PX + "px'>" + escape(label) + "</body></html>");
        if (tooltip != null && !tooltip.isEmpty()) {
            l.setToolTipText(tooltip);
            comp.setToolTipText(tooltip);
        }
        GridBagConstraints c = gbc(0, row, 1);
        c.anchor = GridBagConstraints.NORTHWEST;
        panel.add(l, c);
        c = gbc(1, row, 1);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.weightx = 1;
        panel.add(comp, c);
        row++;
        if (advanced) {
            advancedComps.add(l);
            advancedComps.add(comp);
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static GridBagConstraints gbc(int x, int y, int w) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = w;
        c.insets = new Insets(2, 4, 2, 4);
        return c;
    }

    public void refresh() {
        for (Field f : fields) {
            f.refresh();
        }
        if (toggle != null && !toggleApplied) {
            toggleApplied = true;
            setAdvancedVisible(toggle.isSelected());
        }
    }

    /** Copies all fields into the model; throws with a readable message on the first bad value. */
    public void apply() throws IllegalArgumentException {
        for (Field f : fields) {
            f.apply();
        }
    }

    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return Long.toString((long) v);
        }
        return String.format(Locale.US, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
