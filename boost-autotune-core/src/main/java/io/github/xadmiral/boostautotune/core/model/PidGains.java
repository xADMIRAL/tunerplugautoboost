package io.github.xadmiral.boostautotune.core.model;

import java.util.Locale;

/** PID gains in the ECU's own units (whatever scale the firmware uses). */
public final class PidGains {
    public final double p;
    public final double i;
    public final double d;

    public PidGains(double p, double i, double d) {
        this.p = p;
        this.i = i;
        this.d = d;
    }

    public PidGains withP(double np) { return new PidGains(np, i, d); }
    public PidGains withI(double ni) { return new PidGains(p, ni, d); }
    public PidGains withD(double nd) { return new PidGains(p, i, nd); }

    public boolean approxEquals(PidGains o, double eps) {
        return o != null && Math.abs(p - o.p) <= eps && Math.abs(i - o.i) <= eps && Math.abs(d - o.d) <= eps;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "P=%.2f I=%.2f D=%.2f", p, i, d);
    }
}
