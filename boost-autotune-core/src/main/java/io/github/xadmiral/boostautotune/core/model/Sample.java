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
    // ---- channels used by the VVT and ignition modes (NaN when not bound) ----
    /** Fuel load (kPa for speed density), the Y axis of the VVT table on MS3. */
    public final double fuelLoad;
    /** Ignition load, the Y axis of the spark table. */
    public final double ignLoad;
    public final double vvtAngle;
    public final double vvtTarget;
    /** Actual ignition advance, degrees BTDC. */
    public final double advance;
    /** Knock sensor level (firmware units, e.g. % on MS3). */
    public final double knock;
    /** Timing currently pulled by the ECU's knock control, degrees. */
    public final double knockRetard;
    public final double afr;
    public final double afrTarget;
    /** Manifold air temperature, degrees C (NaN when not bound). */
    public final double mat;
    /** True while the ECU reports its anti-lag system as active. */
    public final boolean alsActive;

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
        this.fuelLoad = b.fuelLoad;
        this.ignLoad = b.ignLoad;
        this.vvtAngle = b.vvtAngle;
        this.vvtTarget = b.vvtTarget;
        this.advance = b.advance;
        this.knock = b.knock;
        this.knockRetard = b.knockRetard;
        this.afr = b.afr;
        this.afrTarget = b.afrTarget;
        this.mat = b.mat;
        this.alsActive = b.alsActive;
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
            case FUEL_LOAD: return Double.isNaN(fuelLoad) ? map : fuelLoad;
            case IGN_LOAD: return Double.isNaN(ignLoad) ? map : ignLoad;
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
        private double fuelLoad = Double.NaN;
        private double ignLoad = Double.NaN;
        private double vvtAngle = Double.NaN;
        private double vvtTarget = Double.NaN;
        private double advance = Double.NaN;
        private double knock = Double.NaN;
        private double knockRetard = Double.NaN;
        private double afr = Double.NaN;
        private double afrTarget = Double.NaN;
        private double mat = Double.NaN;
        private boolean alsActive;

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
        public Builder fuelLoad(double v) { this.fuelLoad = v; return this; }
        public Builder ignLoad(double v) { this.ignLoad = v; return this; }
        public Builder vvtAngle(double v) { this.vvtAngle = v; return this; }
        public Builder vvtTarget(double v) { this.vvtTarget = v; return this; }
        public Builder advance(double v) { this.advance = v; return this; }
        public Builder knock(double v) { this.knock = v; return this; }
        public Builder knockRetard(double v) { this.knockRetard = v; return this; }
        public Builder afr(double v) { this.afr = v; return this; }
        public Builder afrTarget(double v) { this.afrTarget = v; return this; }
        public Builder mat(double v) { this.mat = v; return this; }
        public Builder alsActive(boolean v) { this.alsActive = v; return this; }

        public Sample build() {
            return new Sample(this);
        }
    }
}
