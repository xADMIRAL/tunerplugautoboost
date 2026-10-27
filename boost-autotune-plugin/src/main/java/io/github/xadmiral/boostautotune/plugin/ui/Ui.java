package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.session.SessionState;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * The plugin's visual language on top of whatever look and feel the host uses: one accent
 * colour, flat section headings with a thin rule, a bold accent frame on the one button that is
 * the next thing to press, coloured state pills and a numbered step bar. Fonts are only changed
 * at build time so the text-size scaling keeps working.
 */
public final class Ui {
    private static final String PLAIN_BORDER = "boostAutotune.plainBorder";

    private Ui() {
    }

    /** The accent: a readable blue on a light theme, a lighter one on a dark theme. */
    public static Color accent() {
        return Palette.isDark(Palette.panel()) ? new Color(96, 170, 255) : new Color(0, 102, 204);
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** A flat section heading: bold accent text over a thin rule. */
    public static JLabel sectionHeader(String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setForeground(accent());
        l.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, withAlpha(accent(), 110)),
                BorderFactory.createEmptyBorder(8, 0, 3, 0)));
        return l;
    }

    /** Marks a button as the next thing to press: an accent frame; {@link #secondary} undoes it. */
    public static void primary(JButton b) {
        if (b.getClientProperty(PLAIN_BORDER) == null) {
            b.putClientProperty(PLAIN_BORDER, b.getBorder());
        }
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent(), 2, true),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        b.setForeground(b.isEnabled() ? accent() : b.getForeground());
    }

    public static void secondary(JButton b) {
        Border plain = (Border) b.getClientProperty(PLAIN_BORDER);
        if (plain != null) {
            b.setBorder(plain);
        }
        b.setForeground(null);
    }

    /** Dark, saturated pill colours: white text reads on them in a light and in a dark theme alike. */
    public static Color stateColor(SessionState st) {
        if (st == null) {
            return new Color(110, 110, 110);
        }
        switch (st) {
            case READY: return new Color(0, 92, 184);
            case RECORDING: return new Color(0, 125, 55);
            case REVIEW: return new Color(190, 100, 0);
            case DONE: return new Color(0, 135, 65);
            case ABORTED: return new Color(180, 30, 30);
            default: return new Color(110, 110, 110);
        }
    }

    /** Turns a label into a coloured pill with white text. */
    public static void pill(JLabel l, String text, Color bg) {
        l.setText(text);
        l.setOpaque(true);
        l.setBackground(bg);
        l.setForeground(Palette.textOn(bg));
        l.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
    }

    /** A numbered row of steps; one is the current step, the ones before it are done. */
    public static final class Steps extends JPanel {
        private final JLabel[] labels;
        private final String[] names;

        public Steps(String... names) {
            super(new WrapLayout(FlowLayout.LEFT, 4, 2));
            this.names = names;
            labels = new JLabel[names.length];
            for (int i = 0; i < names.length; i++) {
                labels[i] = new JLabel();
                labels[i].setOpaque(true);
                labels[i].setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
                add(labels[i]);
                if (i < names.length - 1) {
                    JLabel arrow = new JLabel("›");
                    arrow.setForeground(Palette.dimTextOn(Palette.panel()));
                    add(arrow);
                }
            }
            setActive(0);
        }

        /** Highlights step {@code active} (0-based; names.length = all done). */
        public void setActive(int active) {
            Color panel = Palette.panel();
            Color card = Palette.cardOn(panel);
            for (int i = 0; i < labels.length; i++) {
                String text = (i + 1) + "  " + names[i];
                if (i < active) {
                    labels[i].setText("✓  " + names[i]);
                    labels[i].setBackground(card);
                    labels[i].setForeground(Palette.goodOn(card));
                } else if (i == active) {
                    labels[i].setText(text);
                    labels[i].setBackground(accent());
                    labels[i].setForeground(Palette.textOn(accent()));
                } else {
                    labels[i].setText(text);
                    labels[i].setBackground(card);
                    labels[i].setForeground(Palette.dimTextOn(card));
                }
            }
        }

        public JLabel[] labels() {
            return labels;
        }
    }

    /** Standard outer padding of a tab. */
    public static Border tabPadding() {
        return BorderFactory.createEmptyBorder(8, 10, 8, 10);
    }

    public static JComponent padded(JComponent c) {
        c.setBorder(tabPadding());
        return c;
    }
}
