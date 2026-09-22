package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.core.learn.TableSmoother;
import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/** Reads, smooths, writes and restores the demo ECU's VE table through the Smooth tab. */
class SmoothPanelHeadlessTest {
    @Test
    void smoothsTheVeTableAndUndoes() throws Exception {
        final File f = File.createTempFile("boost-autotune-smooth", ".properties");
        f.delete();
        final SimEcuPort port = new SimEcuPort();
        final MainPanel[] holder = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0] = new MainPanel(port, new SettingsStore(f));
            }
        });
        final SmoothPanel sp = holder[0].smoothPanel();
        final double[][] before = port.readArray2D(SimEcuPort.CONFIG, "veTable1");
        final TableSmoother.Result[] res = new TableSmoother.Result[1];
        final boolean[] ok = new boolean[3];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                assertTrue(sp.selectTable("veTable1"), "the demo lists its VE table");
                assertEquals("veTable1", sp.zField().getText());
                ok[0] = sp.read();
                sp.settings().strength = 0.6;
                sp.settings().passes = 2;
                sp.settings().maxChangePct = 3;
                sp.settings().xMin = 2200; // leave the idle / cruise columns alone
                sp.refresh();
                res[0] = sp.preview();
                ok[1] = sp.write(false);
            }
        });
        assertTrue(ok[0]);
        assertNotNull(res[0]);
        assertTrue(res[0].changedCells > 20, res[0].summary());
        assertTrue(res[0].roughnessAfter < res[0].roughnessBefore, res[0].summary());
        assertTrue(ok[1]);
        double[][] after = port.readArray2D(SimEcuPort.CONFIG, "veTable1");
        boolean changed = false;
        for (int yi = 0; yi < 16; yi++) {
            for (int xi = 0; xi < 16; xi++) {
                double d = Math.abs(after[yi][xi] - before[yi][xi]);
                assertTrue(d <= before[yi][xi] * 0.03 + 0.051, String.format("cell %d,%d moved %.2f", yi, xi, d));
                if (xi < 5) {
                    assertEquals(before[yi][xi], after[yi][xi], 1e-9, "columns below 2200 rpm untouched");
                }
                changed |= d > 0.05;
            }
        }
        assertTrue(changed);
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ok[2] = sp.restore();
            }
        });
        assertTrue(ok[2]);
        double[][] restored = port.readArray2D(SimEcuPort.CONFIG, "veTable1");
        for (int yi = 0; yi < 16; yi++) {
            assertArrayEquals(before[yi], restored[yi], 1e-9);
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0].dispose();
            }
        });
        f.delete();
    }
}
