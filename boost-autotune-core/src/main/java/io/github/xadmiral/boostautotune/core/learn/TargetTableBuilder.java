package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.util.Stats;

/** Builds the closed-loop target table (X = RPM, Y = load) for one target stage. */
public final class TargetTableBuilder {
    private TargetTableBuilder() {
    }

    /** Target at an RPM for WOT rows: spool ramp, flat stage target, optional high-RPM taper. */
    public static double targetAtRpm(double rpm, double stageTarget, AutotuneConfig cfg, double rpmAxisMax) {
        double t = stageTarget;
        if (cfg.fullTargetRpm > 0 && rpm < cfg.fullTargetRpm) {
            if (rpm <= cfg.spoolStartRpm) {
                t = cfg.wastegateKpa;
            } else {
                double span = cfg.fullTargetRpm - cfg.spoolStartRpm;
                double f = span <= 0 ? 1 : (rpm - cfg.spoolStartRpm) / span;
                t = Stats.lerp(cfg.wastegateKpa, stageTarget, Stats.clamp(f, 0, 1));
            }
        }
        if (cfg.highRpmTaperKpa > 0 && rpm > cfg.highRpmTaperStartRpm && rpmAxisMax > cfg.highRpmTaperStartRpm) {
            double f = (rpm - cfg.highRpmTaperStartRpm) / (rpmAxisMax - cfg.highRpmTaperStartRpm);
            t -= cfg.highRpmTaperKpa * Stats.clamp(f, 0, 1);
        }
        return Math.min(t, cfg.maxBoostKpa);
    }

    /**
     * @param existing current ECU target table (axes are reused, part-throttle rows may be kept)
     * @param stageTarget WOT target of the stage
     * @param tableMax maximum value the ECU accepts in this table
     */
    public static Grid build(Grid existing, double stageTarget, AutotuneConfig cfg, double tableMax) {
        Axis rpm = existing.xAxis();
        Axis load = existing.yAxis();
        Grid out = existing.copy();
        double loadMin = load.min();
        for (int yi = 0; yi < load.size(); yi++) {
            double l = load.bin(yi);
            boolean wot = l >= cfg.wotLoadThreshold;
            for (int xi = 0; xi < rpm.size(); xi++) {
                double wotTarget = targetAtRpm(rpm.bin(xi), stageTarget, cfg, rpm.max());
                double v;
                if (wot) {
                    v = wotTarget;
                } else if (cfg.scalePartThrottleRows) {
                    double span = cfg.wotLoadThreshold - loadMin;
                    double f = span <= 0 ? 0 : (l - loadMin) / span;
                    v = Stats.lerp(cfg.partThrottleFloorKpa, wotTarget, Stats.clamp(f, 0, 1));
                    v = Math.max(v, cfg.partThrottleFloorKpa);
                } else {
                    continue; // keep ECU value
                }
                out.set(xi, yi, Stats.clamp(v, 0, tableMax));
            }
        }
        return out;
    }

    /** Fills WOT rows of an open-loop duty table with a constant duty; other rows untouched. */
    public static Grid buildOpenLoopDuty(Grid existing, double duty, AutotuneConfig cfg, double minDuty, double maxDuty) {
        Grid out = existing.copy();
        Axis load = existing.yAxis();
        for (int yi = 0; yi < load.size(); yi++) {
            if (load.bin(yi) < cfg.wotLoadThreshold) {
                continue;
            }
            for (int xi = 0; xi < existing.width(); xi++) {
                out.set(xi, yi, Stats.clamp(duty, minDuty, maxDuty));
            }
        }
        return out;
    }
}
