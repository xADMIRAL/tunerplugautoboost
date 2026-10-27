package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.session.SessionState;
import javax.swing.border.Border;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;

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
    /**
     * Rows top to bottom, each at the container's width and its own preferred height for that
     * width. Unlike GridBagLayout or BoxLayout it never falls back to minimum sizes when the
     * container is short: a wrapped row of buttons or a text box keeps its height and the stack
     * overflows (the tab scrolls) instead of a row collapsing to a sliver.
     */
    public static final class VStack implements LayoutManager {
        private final int gap;

        public VStack(int gap) {
            this.gap = gap;
        }

        public void addLayoutComponent(String name, Component comp) {
        }

        public void removeLayoutComponent(Component comp) {
        }

        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int w = 0;
            int h = 0;
            int n = 0;
            int width = parent.getWidth() - in.left - in.right;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = prefAtWidth(c, width);
                w = Math.max(w, d.width);
                h += d.height;
                n++;
            }
            return new Dimension(w + in.left + in.right, h + in.top + in.bottom + gap * Math.max(0, n - 1));
        }

        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int width = parent.getWidth() - in.left - in.right;
            int y = in.top;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                int h = prefAtWidth(c, width).height;
                c.setBounds(in.left, y, width, h);
                y += h + gap;
            }
        }

        /** The row's preferred size once it is as wide as the stack: wrapping rows wrap for real. */
        private static Dimension prefAtWidth(Component c, int width) {
            if (width > 0 && c.getWidth() != width) {
                c.setSize(width, c.getHeight());
            }
            return c.getPreferredSize();
        }
    }

    /**
     * Wraps a tab so a window shorter than the tab scrolls it vertically instead of cutting it off.
     * The view always takes the viewport width (wrapping rows wrap, never a horizontal bar) and fills
     * the viewport height when there is room, so a tall window looks exactly as before.
     */
    public static JScrollPane verticalScroller(JComponent view) {
        ScrollShell shell = new ScrollShell();
        shell.add(view, BorderLayout.CENTER);
        JScrollPane sp = new JScrollPane(shell, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private static final class ScrollShell extends JPanel implements Scrollable {
        ScrollShell() {
            super(new BorderLayout());
        }

        private boolean pending;

        @Override
        protected void validateTree() {
            super.validateTree();
            // Swing caches a container's preferred size the first time it is asked during a pass,
            // i.e. before the wrapping rows got their new width (a resize, another text size): drop
            // the caches and lay out once more with the real heights; if the view itself is still a
            // pass behind its content, let the viewport size it again after this validation
            invalidateDeep(this);
            super.validateTree();
            if (!pending && !fits()) {
                pending = true;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        pending = false;
                        revalidate();
                    }
                });
            }
        }

        private static void invalidateDeep(Component c) {
            c.invalidate();
            if (c instanceof Container) {
                for (Component k : ((Container) c).getComponents()) {
                    invalidateDeep(k);
                }
            }
        }

        private boolean fits() {
            int want = getPreferredSize().height;
            Container p = getParent();
            if (p instanceof JViewport && p.getHeight() >= want) {
                return getHeight() >= want;
            }
            return getHeight() == want;
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            Container p = getParent();
            return p instanceof JViewport && p.getHeight() > getPreferredSize().height;
        }
    }

    public static Border tabPadding() {
        return BorderFactory.createEmptyBorder(8, 10, 8, 10);
    }

    public static JComponent padded(JComponent c) {
        c.setBorder(tabPadding());
        return c;
    }
}
