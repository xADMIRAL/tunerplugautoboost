package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TableSmootherTest {
    private static final Axis RPM = Axis.of(1000, 2000, 3000, 4000, 5000, 6000);
    private static final Axis LOAD = Axis.of(40, 80, 120, 160, 200);

    /** A plane with one spike and one dip. */
    private static Grid jagged() {
        Grid g = new Grid(RPM, LOAD);
        for (int yi = 0; yi < g.height(); yi++) {
            for (int xi = 0; xi < g.width(); xi++) {
                g.set(xi, yi, 60 + 5 * xi + 3 * yi);
            }
        }
        g.set(2, 2, g.get(2, 2) + 12);
        g.set(4, 1, g.get(4, 1) - 10);
        return g;
    }

    @Test
    void takesSpikesOutAndLeavesAPlaneAlone() {
        Grid g = jagged();
        TableSmoother.Settings s = new TableSmoother.Settings();
        s.strength = 0.6;
        s.maxChangePct = 0;
        TableSmoother.Result r = TableSmoother.smooth(g, s);
        assertTrue(r.roughnessAfter < r.roughnessBefore * 0.6, r.summary());
        assertTrue(r.smoothed.get(2, 2) < g.get(2, 2) - 5, "spike pulled down: " + r.smoothed.get(2, 2));
        assertTrue(r.smoothed.get(4, 1) > g.get(4, 1) + 4, "dip pulled up: " + r.smoothed.get(4, 1));
        // a corner cell on the plane stays put: missing neighbours are mirrored across the edge
        assertEquals(g.get(0, 4), r.smoothed.get(0, 4), 1e-9);
        assertEquals(g.get(5, 0), r.smoothed.get(5, 0), 1e-9);
        assertEquals(r.regionCells, g.width() * g.height());
        assertTrue(r.summary().contains("smoother"));
        // strength 0 changes nothing
        s.strength = 0;
        assertEquals(0, TableSmoother.smooth(g, s).changedCells);
    }

    @Test
    void regionCapAndRangeAreHonoured() {
        Grid g = jagged();
        TableSmoother.Settings s = new TableSmoother.Settings();
        s.strength = 1;
        s.xMin = 3000;
        s.yMin = 120;
        s.maxChangePct = 2;
        TableSmoother.Result r = TableSmoother.smooth(g, s);
        assertEquals(4 * 3, r.regionCells);
        // outside the region nothing moves, even next to the spike
        assertEquals(g.get(1, 2), r.smoothed.get(1, 2), 1e-9);
        assertEquals(g.get(4, 1), r.smoothed.get(4, 1), 1e-9);
        // the spike is inside: capped at 2 % of its value
        double cap = g.get(2, 2) * 0.02;
        assertEquals(g.get(2, 2) - cap, r.smoothed.get(2, 2), 0.06);
        assertTrue(r.cappedCells >= 1);
        // clamping to the table range applies to the region's cells
        s.maxChangePct = 0;
        s.maxValue = 80;
        r = TableSmoother.smooth(g, s);
        for (int yi = 0; yi < g.height(); yi++) {
            for (int xi = 0; xi < g.width(); xi++) {
                if (r.inRegion[yi][xi]) {
                    assertTrue(r.smoothed.get(xi, yi) <= 80 + 1e-9, "cell " + xi + "," + yi + " = " + r.smoothed.get(xi, yi));
                }
            }
        }
    }

    @Test
    void oneDimensionalSmoothingOnlyMixesAlongThatAxis() {
        Grid g = new Grid(RPM, LOAD);
        for (int yi = 0; yi < g.height(); yi++) {
            for (int xi = 0; xi < g.width(); xi++) {
                g.set(xi, yi, yi % 2 == 0 ? 50 : 70); // stripes along RPM, jagged along load
            }
        }
        TableSmoother.Settings s = new TableSmoother.Settings();
        s.strength = 1;
        s.maxChangePct = 0;
        s.direction = TableSmoother.Direction.ALONG_X;
        TableSmoother.Result r = TableSmoother.smooth(g, s);
        assertEquals(0, r.changedCells, "rows are flat: smoothing along RPM changes nothing");
        s.direction = TableSmoother.Direction.ALONG_Y;
        r = TableSmoother.smooth(g, s);
        assertTrue(r.changedCells > 0);
        assertEquals(50, r.smoothed.get(0, 1), 1e-9); // row 1 (70) sits between two 50 rows: full strength takes their average
    }

    @Test
    void passesKeepSmoothingAndValidationRejectsNonsense() {
        Grid g = jagged();
        TableSmoother.Settings s = new TableSmoother.Settings();
        s.strength = 0.5;
        s.maxChangePct = 0;
        TableSmoother.Result one = TableSmoother.smooth(g, s);
        s.passes = 3;
        TableSmoother.Result three = TableSmoother.smooth(g, s);
        assertTrue(three.roughnessAfter < one.roughnessAfter);
        s.strength = 1.5;
        assertThrows(IllegalArgumentException.class, () -> TableSmoother.smooth(g, s));
        s.strength = 0.5;
        s.xMin = 5000;
        s.xMax = 2000;
        assertNotNull(s.validate());
    }
}
