package io.github.xadmiral.boostautotune.plugin.ui;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.JTableHeader;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;

/**
 * Text size for the whole plugin. Every component keeps its original ("base") font in a client
 * property, so the scale can be applied again and again (after a refresh, or when the user picks
 * another size) without compounding. Only the plugin's own component tree is touched: the host
 * application's look and feel defaults stay as they are.
 */
public final class Fonts {
    public static final String[] CHOICES = {"100 %", "125 %", "150 %", "175 %"};
    public static final double DEFAULT_SCALE = 1.25;
    private static final String BASE_FONT = "boostAutotune.baseFont";
    private static final String BASE_ROW_HEIGHT = "boostAutotune.baseRowHeight";
    private static volatile double current = DEFAULT_SCALE;

    private Fonts() {
    }

    /** Parses a choice like "125 %" (or a plain number) into a scale; the default on garbage. */
    public static double parse(String text) {
        if (text == null) {
            return DEFAULT_SCALE;
        }
        try {
            double v = Double.parseDouble(text.replace("%", "").trim());
            if (v > 10) {
                v /= 100.0;
            }
            return v >= 0.5 && v <= 3 ? v : DEFAULT_SCALE;
        } catch (NumberFormatException e) {
            return DEFAULT_SCALE;
        }
    }

    public static String choiceFor(double scale) {
        for (String c : CHOICES) {
            if (Math.abs(parse(c) - scale) < 1e-9) {
                return c;
            }
        }
        return Long.toString(Math.round(scale * 100)) + " %";
    }

    /** The scale the plugin is currently using (panels that rebuild parts of themselves re-apply it). */
    public static double current() {
        return current;
    }

    public static void setCurrent(double scale) {
        current = scale;
    }

    /** Applies the scale to a whole component tree. Safe to call repeatedly. */
    public static void apply(Component root, double scale) {
        if (root == null) {
            return;
        }
        if (root instanceof JComponent) {
            scaleOne((JComponent) root, scale);
        }
        if (root instanceof JTabbedPane) {
            JTabbedPane tabs = (JTabbedPane) root;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                apply(tabs.getComponentAt(i), scale);
            }
        }
        if (root instanceof Container) {
            for (Component c : ((Container) root).getComponents()) {
                apply(c, scale);
            }
        }
        if (root instanceof JTable) {
            JTable t = (JTable) root;
            JTableHeader h = t.getTableHeader();
            if (h != null) {
                scaleOne(h, scale);
            }
        }
    }

    private static void scaleOne(JComponent c, double scale) {
        Font base = (Font) c.getClientProperty(BASE_FONT);
        if (base == null) {
            base = c.getFont();
            if (base == null) {
                return;
            }
            c.putClientProperty(BASE_FONT, base);
        }
        c.setFont(base.deriveFont((float) (base.getSize2D() * scale)));
        if (c instanceof JTable) {
            JTable t = (JTable) c;
            Integer baseRow = (Integer) t.getClientProperty(BASE_ROW_HEIGHT);
            if (baseRow == null) {
                baseRow = t.getRowHeight();
                t.putClientProperty(BASE_ROW_HEIGHT, baseRow);
            }
            t.setRowHeight((int) Math.round(baseRow * scale));
        }
    }
}
