package io.github.xadmiral.boostautotune.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AxisGridTest {
    @Test
    void locateClampsAndInterpolates() {
        Axis a = Axis.of(1000, 2000, 3000);
        Axis.Pos p = a.locate(2500);
        assertEquals(1, p.i0);
        assertEquals(2, p.i1);
        assertEquals(0.5, p.w1, 1e-9);
        assertEquals(0, a.locate(-5).i0);
        assertEquals(2, a.locate(9999).i0);
        assertEquals(2, a.locate(9999).i1);
        assertEquals(1, a.nearest(2400));
        assertEquals(2, a.nearest(2600));
        assertTrue(a.isStrictlyIncreasing());
        assertFalse(Axis.of(1, 1, 2).isStrictlyIncreasing());
    }

    @Test
    void gridLookupIsBilinear() {
        Grid g = new Grid(Axis.of(0, 10), Axis.of(0, 10));
        g.set(0, 0, 0);
        g.set(1, 0, 10);
        g.set(0, 1, 20);
        g.set(1, 1, 30);
        assertEquals(15, g.lookup(5, 5), 1e-9);
        assertEquals(10, g.lookup(50, -1), 1e-9); // clamped to x max, y min
        assertEquals(30, g.max(), 1e-9);
        Grid c = g.copy();
        c.set(1, 1, 100);
        assertEquals(70, g.maxAbsDiff(c), 1e-9);
        assertEquals(30, g.get(1, 1), 1e-9);
    }
}
