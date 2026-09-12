package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.util.Stats;

/**
 * Turns the plant model into a bias table: X = RPM, Y = target boost, Z = duty.
 * Columns without observations borrow from the nearest measured columns; cells that cannot be
 * estimated keep their previous value. Changes are limited by the authority setting unless the
 * table is being filled for the first time.
 */
public final class BiasTableBuilder {
    private BiasTableBuilder() {
    }

    public static BiasBuildResult build(Grid existing, PlantModel model, AutotuneConfig cfg,
                                        double minDuty, double maxDuty, boolean unlimitedAuthority) {
        Axis rpm = existing.xAxis();
        Axis targets = existing.yAxis();
        if (rpm.size() != model.rpmAxis().size()) {
            throw new IllegalArgumentException("Plant model RPM axis does not match the bias table");
        }
        Grid out = existing.copy();
        DutyEstimate.Quality[][] quality = new DutyEstimate.Quality[targets.size()][rpm.size()];
        boolean[][] saturated = new boolean[targets.size()][rpm.size()];
        int measured = 0, interpolated = 0, extrapolated = 0, borrowed = 0, unchanged = 0;

        // 1. estimate every column that has data
        DutyEstimate[][] est = new DutyEstimate[targets.size()][rpm.size()];
        for (int xi = 0; xi < rpm.size(); xi++) {
            for (int yi = 0; yi < targets.size(); yi++) {
                est[yi][xi] = model.hasData(xi)
                        ? model.dutyFor(xi, targets.bin(yi), minDuty, maxDuty, cfg.biasMaxExtrapolationPct)
                        : DutyEstimate.none();
            }
        }
        // 2. borrow for empty columns
        for (int xi = 0; xi < rpm.size(); xi++) {
            if (model.hasData(xi)) {
                continue;
            }
            int left = -1, right = -1;
            for (int k = xi - 1; k >= 0; k--) {
                if (model.hasData(k)) { left = k; break; }
            }
            for (int k = xi + 1; k < rpm.size(); k++) {
                if (model.hasData(k)) { right = k; break; }
            }
            if (left < 0 && right < 0) {
                continue;
            }
            for (int yi = 0; yi < targets.size(); yi++) {
                double v;
                boolean sat;
                if (left >= 0 && right >= 0) {
                    double span = rpm.bin(right) - rpm.bin(left);
                    double t = span <= 0 ? 0 : (rpm.bin(xi) - rpm.bin(left)) / span;
                    v = Stats.lerp(est[yi][left].duty, est[yi][right].duty, t);
                    sat = est[yi][left].saturated && est[yi][right].saturated;
                } else {
                    DutyEstimate src = left >= 0 ? est[yi][left] : est[yi][right];
                    v = src.duty;
                    sat = src.saturated;
                }
                est[yi][xi] = new DutyEstimate(v, DutyEstimate.Quality.BORROWED, sat);
            }
        }
        // 3. write with authority
        double maxChange = 0;
        double maxChangeMeasured = 0;
        for (int yi = 0; yi < targets.size(); yi++) {
            for (int xi = 0; xi < rpm.size(); xi++) {
                DutyEstimate e = est[yi][xi];
                quality[yi][xi] = e.quality;
                saturated[yi][xi] = e.saturated;
                if (!e.hasValue()) {
                    unchanged++;
                    continue;
                }
                double old = existing.get(xi, yi);
                double v = e.duty;
                if (!unlimitedAuthority) {
                    v = Stats.clamp(v, old - cfg.biasMaxStepPct, old + cfg.biasMaxStepPct);
                }
                v = Stats.clamp(v, minDuty, maxDuty);
                out.set(xi, yi, v);
                maxChange = Math.max(maxChange, Math.abs(v - old));
                if (e.quality == DutyEstimate.Quality.MEASURED || e.quality == DutyEstimate.Quality.INTERPOLATED) {
                    maxChangeMeasured = Math.max(maxChangeMeasured, Math.abs(v - old));
                }
                switch (e.quality) {
                    case MEASURED: measured++; break;
                    case INTERPOLATED: interpolated++; break;
                    case EXTRAPOLATED: extrapolated++; break;
                    case BORROWED: borrowed++; break;
                    default: unchanged++; break;
                }
            }
        }
        BiasBuildResult r = new BiasBuildResult(out, quality, saturated, maxChange, maxChangeMeasured,
                measured, interpolated, extrapolated, borrowed, unchanged);
        for (int yi = 0; yi < targets.size(); yi++) {
            for (int xi = 0; xi < rpm.size(); xi++) {
                double delta = out.get(xi, yi) - existing.get(xi, yi);
                if (Math.abs(delta) > 0.05) {
                    r.changes.add(new BiasBuildResult.CellChange(xi, yi, rpm.bin(xi), targets.bin(yi),
                            existing.get(xi, yi), out.get(xi, yi), quality[yi][xi]));
                }
            }
        }
        java.util.Collections.sort(r.changes, new java.util.Comparator<BiasBuildResult.CellChange>() {
            @Override
            public int compare(BiasBuildResult.CellChange a, BiasBuildResult.CellChange b) {
                return Double.compare(Math.abs(b.delta()), Math.abs(a.delta()));
            }
        });
        if (unchanged == targets.size() * rpm.size()) {
            r.notes.add("No usable steady-state samples: bias table left unchanged");
        }
        return r;
    }
}
