package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.List;
import java.util.Locale;

/**
 * Plans the open-loop duty for the next characterization run. Starts low, climbs in fixed steps
 * and stops when the observed boost covers the highest stage target, when the next step is
 * predicted to exceed the hard limit, or when the duty ceiling is hit.
 */
public final class DutyLadder {
    private DutyLadder() {
    }

    public static final class Step {
        public final double duty;
        public final boolean done;
        public final String reason;

        Step(double duty, boolean done, String reason) {
            this.duty = duty;
            this.done = done;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return done ? "done: " + reason : String.format(Locale.US, "next duty %.1f%% (%s)", duty, reason);
        }
    }

    public static Step next(PlantModel model, AutotuneConfig cfg, double minDuty, double maxDuty,
                            List<Double> dutiesDone, double observedPeak) {
        double ceiling = Math.min(cfg.characterizeMaxDuty, maxDuty);
        if (dutiesDone.isEmpty()) {
            double d = cfg.characterizeStartDuty > 0 ? cfg.characterizeStartDuty : minDuty + cfg.characterizeStepPct;
            d = Stats.clamp(d, minDuty, ceiling);
            return new Step(d, false, "first ladder step");
        }
        double required = cfg.maxStageTarget() + cfg.characterizeHeadroomKpa;
        double last = dutiesDone.get(dutiesDone.size() - 1);
        boolean enoughRuns = dutiesDone.size() >= cfg.characterizeMinRuns;
        if (Stats.finite(observedPeak) && observedPeak >= required && enoughRuns) {
            return new Step(last, true, String.format(Locale.US,
                    "observed %.0f kPa covers the highest target %.0f kPa", observedPeak, cfg.maxStageTarget()));
        }
        double d = last + cfg.characterizeStepPct;
        double limit = cfg.maxBoostKpa - cfg.predictionMarginKpa;
        double predicted = model.predictPeakBoost(d);
        if (Stats.finite(predicted) && predicted > limit) {
            // shrink the step until the prediction fits under the limit
            double lo = last, hi = d;
            for (int k = 0; k < 20; k++) {
                double mid = 0.5 * (lo + hi);
                double pm = model.predictPeakBoost(mid);
                if (Stats.finite(pm) && pm > limit) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            d = lo;
            double minUseful = Math.max(2, cfg.characterizeStepPct / 3);
            if (d <= last + 2 || (enoughRuns && d <= last + minUseful)) {
                return new Step(last, true, String.format(Locale.US,
                        "next step would be predicted above %.0f kPa (limit %.0f - margin %.0f)",
                        limit, cfg.maxBoostKpa, cfg.predictionMarginKpa));
            }
        }
        if (d > ceiling) {
            if (last >= ceiling - 0.5 || enoughRuns) {
                return new Step(last, true, String.format(Locale.US, "duty ceiling %.0f%% reached", ceiling));
            }
            d = ceiling;
        }
        return new Step(d, false, Stats.finite(predicted)
                ? String.format(Locale.US, "predicted peak %.0f kPa", model.predictPeakBoost(d))
                : "no prediction yet");
    }
}
