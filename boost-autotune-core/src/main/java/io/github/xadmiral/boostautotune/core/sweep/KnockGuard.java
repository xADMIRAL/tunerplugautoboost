package io.github.xadmiral.boostautotune.core.sweep;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.Sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Remembers, per spark table cell, the advance that knocked and keeps the table below it. */
public final class KnockGuard {
    public static final class Event {
        public final double timeSec;
        public final double rpm;
        public final double load;
        public final double advance;
        public final double retard;
        public final double level;

        Event(double timeSec, double rpm, double load, double advance, double retard, double level) {
            this.timeSec = timeSec;
            this.rpm = rpm;
            this.load = load;
            this.advance = advance;
            this.retard = retard;
            this.level = level;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "knock at %.0f rpm / load %.0f, advance %.1f deg (retard %.1f, level %.1f)",
                    rpm, load, advance, retard, level);
        }
    }

    private final Grid caps; // NaN = no cap
    private final double margin;
    private final List<Event> events = new ArrayList<Event>();

    public KnockGuard(Axis rpm, Axis load, double marginDeg) {
        this.caps = Grid.filled(rpm, load, Double.NaN);
        this.margin = marginDeg;
    }

    /** Records a knock event; the cell (and its RPM neighbours) get capped below the knocking advance. */
    public void record(Sample s, double load) {
        double adv = Double.isNaN(s.advance) ? Double.NaN : s.advance;
        events.add(new Event(s.timeSec, s.rpm, load, adv, s.knockRetard, s.knock));
        if (Double.isNaN(adv)) {
            return;
        }
        Axis.Pos px = caps.xAxis().locate(s.rpm);
        Axis.Pos py = caps.yAxis().locate(load);
        int[] xs = px.i0 == px.i1 ? new int[]{px.i0} : new int[]{px.i0, px.i1};
        int[] ys = py.i0 == py.i1 ? new int[]{py.i0} : new int[]{py.i0, py.i1};
        for (int xi : xs) {
            for (int yi : ys) {
                double cap = adv - margin;
                double old = caps.get(xi, yi);
                caps.set(xi, yi, Double.isNaN(old) ? cap : Math.min(old, cap));
            }
        }
    }

    public boolean hasCap(int xi, int yi) {
        return !Double.isNaN(caps.get(xi, yi));
    }

    public double cap(int xi, int yi) {
        return caps.get(xi, yi);
    }

    public int cappedCells() {
        int n = 0;
        for (int yi = 0; yi < caps.height(); yi++) {
            for (int xi = 0; xi < caps.width(); xi++) {
                if (hasCap(xi, yi)) {
                    n++;
                }
            }
        }
        return n;
    }

    public List<Event> events() {
        return new ArrayList<Event>(events);
    }

    /** Clamps a table to the caps; returns the number of cells that were lowered. */
    public int apply(Grid table) {
        int n = 0;
        for (int yi = 0; yi < table.height(); yi++) {
            for (int xi = 0; xi < table.width(); xi++) {
                double cap = caps.get(xi, yi);
                if (!Double.isNaN(cap) && table.get(xi, yi) > cap) {
                    table.set(xi, yi, cap);
                    n++;
                }
            }
        }
        return n;
    }

    public Grid capsGrid() {
        return caps.copy();
    }
}
