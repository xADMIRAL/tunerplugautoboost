package io.github.xadmiral.boostautotune.core.learn;

/** Result of asking the plant model for the duty that produces a given boost. */
public final class DutyEstimate {
    public enum Quality {
        /** Data lies almost exactly on the target. */
        MEASURED,
        /** Between two observed duty levels. */
        INTERPOLATED,
        /** Outside the observed duty range. */
        EXTRAPOLATED,
        /** No data in this RPM column, taken from neighbouring columns. */
        BORROWED,
        /** No data at all; value unchanged. */
        NONE
    }

    public final double duty;
    public final Quality quality;
    /** True when more duty is unlikely to give more boost (top of the observed curve is flat). */
    public final boolean saturated;
    /** Duty before the extrapolation and range caps; above the maximum duty the target is out of reach. */
    public final double rawDuty;

    public DutyEstimate(double duty, Quality quality, boolean saturated) {
        this(duty, quality, saturated, duty);
    }

    public DutyEstimate(double duty, Quality quality, boolean saturated, double rawDuty) {
        this.duty = duty;
        this.quality = quality;
        this.saturated = saturated;
        this.rawDuty = rawDuty;
    }

    /** True when the column cannot deliver the target short of shutting the valve: flat top, or at least {@code shutDuty} needed. */
    public boolean outOfReach(double shutDuty) {
        return hasValue() && (saturated || rawDuty >= shutDuty - 1e-9);
    }

    public static DutyEstimate none() {
        return new DutyEstimate(Double.NaN, Quality.NONE, false);
    }

    public boolean hasValue() {
        return quality != Quality.NONE && !Double.isNaN(duty);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "%.1f%% (%s%s)", duty, quality, saturated ? ", saturated" : "");
    }
}
