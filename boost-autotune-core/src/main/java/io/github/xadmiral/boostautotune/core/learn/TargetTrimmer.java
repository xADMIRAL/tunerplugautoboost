package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.LoadSource;
import io.github.xadmiral.boostautotune.core.model.Sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lowers target cells the turbo cannot reach: where the valve sat at maximum duty and boost still
 * stayed under target, the cell target becomes what was actually achieved.
 */
public final class TargetTrimmer {
    private TargetTrimmer() {
    }

    public static final class Result {
        public final Grid grid;
        public final List<String> notes = new ArrayList<String>();
        public int trimmedCells;

        Result(Grid grid) {
            this.grid = grid;
        }
    }

    public static Result trim(Grid targets, LoadSource loadSource, List<Pull> pulls, AutotuneConfig cfg, double maxDuty) {
        Result r = new Result(targets.copy());
        if (!cfg.trimUnreachableTargets) {
            return r;
        }
        Axis rpm = targets.xAxis();
        Axis load = targets.yAxis();
        int[][] n = new int[load.size()][rpm.size()];
        double[][] sumMap = new double[load.size()][rpm.size()];
        for (Pull p : pulls) {
            for (int k = 0; k < p.size(); k++) {
                if (p.stateAt(k) != SampleState.STEADY) {
                    continue;
                }
                Sample s = p.samples().get(k);
                if (Double.isNaN(s.target) || s.duty < maxDuty - cfg.saturationDutyMarginPct) {
                    continue;
                }
                if (s.map >= s.target - cfg.steadyStateTolKpa) {
                    continue;
                }
                int xi = rpm.nearest(s.rpm);
                int yi = load.nearest(s.load(loadSource));
                n[yi][xi]++;
                sumMap[yi][xi] += s.map;
            }
        }
        for (int yi = 0; yi < load.size(); yi++) {
            for (int xi = 0; xi < rpm.size(); xi++) {
                if (n[yi][xi] < cfg.minSaturatedSamples) {
                    continue;
                }
                double achieved = sumMap[yi][xi] / n[yi][xi];
                double old = targets.get(xi, yi);
                double nv = Math.max(cfg.wastegateKpa, Math.floor(achieved - 2));
                if (nv < old - 1) {
                    r.grid.set(xi, yi, nv);
                    r.trimmedCells++;
                    r.notes.add(String.format(Locale.US,
                            "target @ %.0f rpm / load %.0f lowered %.0f -> %.0f kPa (valve at max duty, %d samples)",
                            rpm.bin(xi), load.bin(yi), old, nv, n[yi][xi]));
                }
            }
        }
        return r;
    }
}
