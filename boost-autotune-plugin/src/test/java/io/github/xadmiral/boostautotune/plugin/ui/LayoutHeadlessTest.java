package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lays the whole UI out at large text sizes and narrow widths without a display and checks that
 * the live values keep their height and that no two buttons of the run-control row overlap.
 */
class LayoutHeadlessTest {
    private static void layoutTree(Component c) {
        c.doLayout();
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                layoutTree(k);
            }
        }
    }

    private static void layoutAt(final MainPanel p, final int w, final int h) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                p.setSize(w, h);
                // two passes: wrapped rows report their height from the width of the first pass
                layoutTree(p);
                layoutTree(p);
            }
        });
    }

    @Test
    void liveValuesStayVisibleAndButtonsDoNotOverlapAtLargeTextAndNarrowWidths() throws Exception {
        final File f = File.createTempFile("boost-autotune-layout", ".properties");
        f.delete();
        final MainPanel[] holder = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0] = new MainPanel(new SimEcuPort(), new SettingsStore(f));
            }
        });
        final MainPanel p = holder[0];
        for (double scale : new double[]{1.0, 1.25, 1.75}) {
            final double sc = scale;
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    p.setFontScale(sc);
                }
            });
            for (int width : new int[]{1150, 800, 640}) {
                layoutAt(p, width, 760);
                AutotunePanel a = p.autotunePanel();
                JLabel[] live = a.liveLabels();
                JPanel[] cells = a.liveCells();
                for (int i = 0; i < live.length; i++) {
                    int fontPx = live[i].getFont().getSize();
                    assertTrue(live[i].getHeight() >= fontPx, String.format("scale %.2f width %d: live '%s' is %d px high for a %d px font",
                            sc, width, live[i].getText(), live[i].getHeight(), fontPx));
                    assertTrue(cells[i].getWidth() > 0 && cells[i].getHeight() > 0, "live cell has no size");
                }
                // every live cell inside the row, none on top of another
                for (int i = 0; i < cells.length; i++) {
                    for (int j = i + 1; j < cells.length; j++) {
                        assertFalse(cells[i].getBounds().intersects(cells[j].getBounds()),
                                String.format("scale %.2f width %d: live cells %d and %d overlap", sc, width, i, j));
                    }
                }
                // anti-lag tab: the table keeps rows, Apply never sits on the advanced checkbox
                AntilagPanel al = p.antilagPanel();
                assertTrue(al.presetTable().getHeight() >= al.presetTable().getRowHeight() * 3,
                        String.format("scale %.2f width %d: preset table squeezed to %d px", sc, width, al.presetTable().getHeight()));
                Rectangle apply = SwingUtilities.convertRectangle(al.applyButton().getParent(), al.applyButton().getBounds(), al);
                Rectangle adv = SwingUtilities.convertRectangle(al.advancedCheckbox().getParent(), al.advancedCheckbox().getBounds(), al);
                assertFalse(apply.intersects(adv), String.format("scale %.2f width %d: Apply overlaps the advanced checkbox", sc, width));
                assertTrue(apply.height > 0 && apply.y >= adv.y + adv.height - 1, "Apply must sit below the advanced row");
                // boost tab: the settings form fits half of a modest window even at the largest text
                assertTrue(p.targetsPanel().formPreferredWidth() <= 620,
                        String.format("scale %.2f: boost settings form is %d px wide", sc, p.targetsPanel().formPreferredWidth()));
                for (JPanel row : new JPanel[]{a.buttonRow(), a.secondaryRow()}) {
                    Component[] kids = row.getComponents();
                    for (int i = 0; i < kids.length; i++) {
                        if (!kids[i].isVisible()) {
                            continue;
                        }
                        Rectangle ri = kids[i].getBounds();
                        assertTrue(ri.height > 0 && ri.width > 0, "button without size: " + kids[i]);
                        assertTrue(ri.y + ri.height <= row.getHeight() + 1, String.format("scale %.2f width %d: control '%s' sticks out of its row",
                                sc, width, kids[i] instanceof JButton ? ((JButton) kids[i]).getText() : kids[i].getClass().getSimpleName()));
                        for (int j = i + 1; j < kids.length; j++) {
                            if (kids[j].isVisible()) {
                                assertFalse(ri.intersects(kids[j].getBounds()), String.format("scale %.2f width %d: controls %d and %d overlap", sc, width, i, j));
                            }
                        }
                    }
                }
                // the report is a compact box: well under half of the window at every text size
                int reportHeight = a.reportArea().getParent().getParent().getHeight();
                assertTrue(reportHeight > 0 && reportHeight < 760 * 0.45, String.format("scale %.2f width %d: report box is %d px high", sc, width, reportHeight));
                // the step bar never stacks its labels on each other
                Component[] steps = a.stepsBar().getComponents();
                for (int i = 0; i < steps.length; i++) {
                    for (int j = i + 1; j < steps.length; j++) {
                        assertFalse(steps[i].getBounds().intersects(steps[j].getBounds()), String.format("scale %.2f width %d: steps overlap", sc, width));
                    }
                }
            }
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0].dispose();
            }
        });
        f.delete();
    }

    /** A dark host theme (TunerStudio's) must not hide the live values or the state line. */
    @Test
    void liveValuesReadableOnADarkTheme() throws Exception {
        final java.awt.Color dark = new java.awt.Color(43, 43, 43);
        final java.awt.Color light = new java.awt.Color(220, 220, 220);
        final String[] bgKeys = {"Panel.background", "Viewport.background", "TextArea.background"};
        final String[] fgKeys = {"Label.foreground", "TextArea.foreground", "TitledBorder.titleColor"};
        final Object[] savedBg = new Object[bgKeys.length];
        final Object[] savedFg = new Object[fgKeys.length];
        for (int i = 0; i < bgKeys.length; i++) { savedBg[i] = javax.swing.UIManager.get(bgKeys[i]); javax.swing.UIManager.put(bgKeys[i], dark); }
        for (int i = 0; i < fgKeys.length; i++) { savedFg[i] = javax.swing.UIManager.get(fgKeys[i]); javax.swing.UIManager.put(fgKeys[i], light); }
        try {
            final File f = File.createTempFile("boost-autotune-dark", ".properties");
            f.delete();
            final MainPanel[] holder = new MainPanel[1];
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    holder[0] = new MainPanel(new SimEcuPort(), new SettingsStore(f));
                    holder[0].autotunePanel().updateState();
                }
            });
            AutotunePanel a = holder[0].autotunePanel();
            JLabel[] live = a.liveLabels();
            JPanel[] cells = a.liveCells();
            for (int i = 0; i < live.length; i++) {
                assertTrue(cells[i].isOpaque(), "live cell paints its own background");
                double contrast = Math.abs(Palette.luminance(live[i].getForeground()) - Palette.luminance(cells[i].getBackground()));
                assertTrue(contrast > 0.5, String.format("live '%s': foreground %s on %s has contrast %.2f",
                        live[i].getText(), live[i].getForeground(), cells[i].getBackground(), contrast));
            }
            JLabel state = a.stateLabel();
            java.awt.Color behind = state.isOpaque() ? state.getBackground() : dark;
            double contrast = Math.abs(Palette.luminance(state.getForeground()) - Palette.luminance(behind));
            assertTrue(contrast > 0.5, "state pill " + state.getForeground() + " on " + behind);
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    holder[0].dispose();
                }
            });
            f.delete();
        } finally {
            for (int i = 0; i < bgKeys.length; i++) { javax.swing.UIManager.put(bgKeys[i], savedBg[i]); }
            for (int i = 0; i < fgKeys.length; i++) { javax.swing.UIManager.put(fgKeys[i], savedFg[i]); }
        }
    }

    @Test
    void windowAsksForHalfTheScreenAndTheAutotuneTabScrollsInsteadOfBeingCut() throws Exception {
        final File f = File.createTempFile("boost-autotune-layout", ".properties");
        f.delete();
        final MainPanel[] holder = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0] = new MainPanel(new SimEcuPort(), new SettingsStore(f));
            }
        });
        final MainPanel p = holder[0];
        // headless: a 1920 x 1080 screen is assumed by MainPanel.usableScreen()
        final Dimension pref = p.getPreferredSize();
        assertTrue(pref.height <= 1080 * MainPanel.WINDOW_HEIGHT_FRACTION + 1, "preferred height " + pref.height);
        assertTrue(pref.width <= 1920 * MainPanel.WINDOW_WIDTH_FRACTION + 1, "preferred width " + pref.width);
        assertTrue(pref.height >= 420 && pref.width >= 960, "too small a window: " + pref);
        AutotunePanel at = p.autotunePanel();
        JScrollPane scroller = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, at);
        assertNotNull(scroller, "the Autotune tab sits in a vertical scroller");
        for (double scale : new double[]{1.25, 1.75}) {
            final double sc = scale;
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    p.setFontScale(sc);
                }
            });
            for (int[] size : new int[][]{{pref.width, pref.height}, {1000, 420}, {1500, 900}}) {
                layoutAt(p, size[0], size[1]);
                int content = at.getPreferredSize().height;
                assertTrue(at.getHeight() >= content - 1, String.format("scale %.2f %dx%d: the tab is %d px high for %d px of content",
                        sc, size[0], size[1], at.getHeight(), content));
                int viewport = scroller.getViewport().getHeight();
                assertTrue(at.getWidth() <= scroller.getViewport().getWidth(), "no horizontal overflow");
                if (content > viewport) {
                    assertTrue(scroller.getVerticalScrollBar().isVisible(), String.format("scale %.2f %dx%d: %d px of content in a %d px viewport needs a scrollbar",
                            sc, size[0], size[1], content, viewport));
                } else {
                    assertTrue(at.getHeight() >= viewport - 1, "a tall window is filled, not left to a gap under a short tab");
                }
                Rectangle tab = new Rectangle(0, 0, at.getWidth(), at.getHeight());
                for (JPanel cell : at.liveCells()) {
                    Rectangle r = SwingUtilities.convertRectangle(cell.getParent(), cell.getBounds(), at);
                    assertTrue(tab.contains(r), String.format("scale %.2f %dx%d: live cell %s outside the tab %s", sc, size[0], size[1], r, tab));
                }
                for (JPanel row : new JPanel[]{at.buttonRow(), at.secondaryRow()}) {
                    for (Component b : row.getComponents()) {
                        if (!b.isVisible()) {
                            continue;
                        }
                        Rectangle r = SwingUtilities.convertRectangle(row, b.getBounds(), at);
                        assertTrue(tab.contains(r), String.format("scale %.2f %dx%d: control %s outside the tab %s", sc, size[0], size[1], r, tab));
                    }
                }
                JScrollPane reportBox = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, at.reportArea());
                Rectangle rep = SwingUtilities.convertRectangle(reportBox.getParent(), reportBox.getBounds(), at);
                assertTrue(rep.height >= reportBox.getPreferredSize().height - 1 && rep.y + rep.height <= at.getHeight(),
                        String.format("scale %.2f %dx%d: report box %s (preferred %d high) inside the tab %s", sc, size[0], size[1], rep, reportBox.getPreferredSize().height, tab));
            }
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                p.dispose();
            }
        });
    }
}
