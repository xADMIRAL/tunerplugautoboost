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

    public JPanel panel() {
        return panel;
    }

    public void section(String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
        GridBagConstraints c = gbc(0, row++, 2);
        c.insets = new Insets(10, 4, 2, 4);
        panel.add(l, c);
    }

    public JTextField addDouble(final String label, String tooltip, final DoubleGet get, final DoubleSet set) {
        final JTextField tf = new JTextField(8);
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
        final JTextField tf = new JTextField(8);
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
        final JTextField tf = new JTextField(14);
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

    private void add(String label, JComponent comp, String tooltip) {
        JLabel l = new JLabel(label);
        if (tooltip != null) {
            l.setToolTipText(tooltip);
        }
        GridBagConstraints c = gbc(0, row, 1);
        c.anchor = GridBagConstraints.EAST;
        panel.add(l, c);
        c = gbc(1, row, 1);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(comp, c);
        row++;
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
