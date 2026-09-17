package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
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
                JPanel row = a.buttonRow();
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
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0].dispose();
            }
        });
        f.delete();
    }
}
