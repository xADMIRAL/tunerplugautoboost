package io.github.xadmiral.boostautotune.core.model;

/**
 * One snapshot of the runtime channels the autotune needs.
 * Units: seconds, RPM, percent (TPS / duty), kPa absolute (MAP / target), degrees C (CLT).
 * Immutable; build with {@link Builder}.
 */
public final class Sample {
    public final double timeSec;
    public final double rpm;
    public final double tps;
    public final double map;
    public final double target;
    public final double duty;
    public final double clt;
    public final int gear;
    public final boolean boostCut;
    /** True when the ECU reports its closed-loop controller as active; true when unknown. */
    public final boolean closedLoopActive;

    private Sample(Builder b) {
        this.timeSec = b.timeSec;
        this.rpm = b.rpm;
        this.tps = b.tps;
        this.map = b.map;
        this.target = b.target;
        this.duty = b.duty;
        this.clt = b.clt;
        this.gear = b.gear;
        this.boostCut = b.boostCut;
        this.closedLoopActive = b.closedLoopActive;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Returns the value of a named load source for table axis lookups. */
    public double load(LoadSource source) {
        switch (source) {
            case TPS: return tps;
            case BOOST_TARGET: return target;
            case MAP: return map;
            case GEAR: return gear;
            default: throw new IllegalArgumentException("Unknown load source " + source);
        }
    }

    @Override
    public String toString() {
        return String.format("t=%.2f rpm=%.0f tps=%.1f map=%.1f tgt=%.1f duty=%.1f clt=%.0f gear=%d%s",
                timeSec, rpm, tps, map, target, duty, clt, gear, boostCut ? " CUT" : "");
    }

    public static final class Builder {
        private double timeSec;
        private double rpm;
        private double tps;
        private double map;
        private double target = Double.NaN;
        private double duty;
        private double clt = 90;
        private int gear;
        private boolean boostCut;
        private boolean closedLoopActive = true;

        public Builder time(double t) { this.timeSec = t; return this; }
        public Builder rpm(double v) { this.rpm = v; return this; }
        public Builder tps(double v) { this.tps = v; return this; }
        public Builder map(double v) { this.map = v; return this; }
        public Builder target(double v) { this.target = v; return this; }
        public Builder duty(double v) { this.duty = v; return this; }
        public Builder clt(double v) { this.clt = v; return this; }
        public Builder gear(int v) { this.gear = v; return this; }
        public Builder boostCut(boolean v) { this.boostCut = v; return this; }
        public Builder closedLoopActive(boolean v) { this.closedLoopActive = v; return this; }

        public Sample build() {
            return new Sample(this);
        }
    }
}
