package io.github.xadmiral.boostautotune.core.als;

import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One off-throttle anti-lag window and what happened after the driver got back on the throttle. */
public final class AlsEvent {
    final List<Sample> active = new ArrayList<Sample>();
    final List<Sample> after = new ArrayList<Sample>();

    public double startTime() {
        return active.isEmpty() ? Double.NaN : active.get(0).timeSec;
    }

    public double endTime() {
        return active.isEmpty() ? Double.NaN : active.get(active.size() - 1).timeSec;
    }

    public double durationSec() {
        return active.isEmpty() ? 0 : endTime() - startTime();
    }

    public int samples() {
        return active.size();
    }

    /** Mean MAP after the settle time. */
    public double meanMapSettled(double settleSec) {
        double s = 0;
        int n = 0;
        for (Sample x : active) {
            if (x.timeSec - startTime() >= settleSec && Stats.finite(x.map)) {
                s += x.map;
                n++;
            }
        }
        return n == 0 ? Double.NaN : s / n;
    }

    public int settledSamples(double settleSec) {
        int n = 0;
        for (Sample x : active) {
            if (x.timeSec - startTime() >= settleSec) {
                n++;
            }
        }
        return n;
    }

    public double meanRpm() {
        double s = 0;
        for (Sample x : active) {
            s += x.rpm;
        }
        return active.isEmpty() ? Double.NaN : s / active.size();
    }

    public double minRpm() {
        double m = Double.POSITIVE_INFINITY;
        for (Sample x : active) {
            m = Math.min(m, x.rpm);
        }
        return m;
    }

    public double maxMat() {
        double m = Double.NaN;
        for (Sample x : active) {
            if (Stats.finite(x.mat)) {
                m = Double.isNaN(m) ? x.mat : Math.max(m, x.mat);
            }
        }
        return m;
    }

    /** Seconds from throttle-in until MAP reached the re-spool target; NaN if it never did. */
    public double respoolSec(double targetKpa) {
        if (after.isEmpty()) {
            return Double.NaN;
        }
        double t0 = after.get(0).timeSec;
        for (Sample x : after) {
            if (x.map >= targetKpa) {
                return x.timeSec - t0;
            }
        }
        return Double.NaN;
    }

    public String summary(AlsConfig cfg) {
        double respool = respoolSec(cfg.respoolTargetKpa);
        return String.format(Locale.US,
                "ALS event %.1f s at %.0f rpm (min %.0f): MAP %.1f kPa off throttle, MAT max %.0f, %s",
                durationSec(), meanRpm(), minRpm(), meanMapSettled(cfg.settleSec), maxMat(),
                Double.isNaN(respool) ? String.format(Locale.US, "no re-spool to %.0f kPa seen", cfg.respoolTargetKpa)
                        : String.format(Locale.US, "re-spool to %.0f kPa in %.2f s", cfg.respoolTargetKpa, respool));
    }
}
