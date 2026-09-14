package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.List;

/** Extracts overshoot, rise time, steady-state error and oscillation from one closed-loop pull. */
public final class StepResponseAnalyzer {
    private StepResponseAnalyzer() {
    }

    public static ResponseMetrics analyze(Pull pull, AutotuneConfig cfg, double minDuty, double maxDuty) {
        List<Sample> s = pull.samples();
        int n = s.size();
        int activeStart = -1;
        for (int i = 0; i < n; i++) {
            Sample x = s.get(i);
            if (Stats.finite(x.target) && x.target > cfg.wastegateKpa + 1 && x.rpm >= cfg.minRpm) {
                activeStart = i;
                break;
            }
        }
        double peak = pull.maxMap();
        if (activeStart < 0) {
            return new ResponseMetrics(pull, false, false, Double.NaN, peak, 0, Double.NaN, Double.NaN,
                    Double.NaN, 0, 0, Double.NaN, 0, 0, 0);
        }
        List<Double> targets = new ArrayList<Double>();
        int satHi = 0, satLo = 0, active = 0;
        int reachIdx = -1;
        for (int i = activeStart; i < n; i++) {
            Sample x = s.get(i);
            if (!Stats.finite(x.target)) {
                continue;
            }
            active++;
            targets.add(x.target);
            if (x.duty >= maxDuty - cfg.saturationDutyMarginPct) {
                satHi++;
            }
            if (x.duty <= minDuty + cfg.saturationDutyMarginPct) {
                satLo++;
            }
            if (reachIdx < 0 && x.map >= x.target - cfg.steadyStateTolKpa) {
                reachIdx = i;
            }
        }
        double targetMed = Stats.median(targets);
        boolean reached = reachIdx >= 0;
        double rise = reached ? s.get(reachIdx).timeSec - s.get(activeStart).timeSec : Double.NaN;
        double overshoot = 0;
        double ssErr = Double.NaN;
        double meanAbs = Double.NaN;
        double oscAmp = 0, oscCycles = 0, oscPeriod = Double.NaN;
        int settled = 0;
        if (reached) {
            // error series after reach
            List<Double> err = new ArrayList<Double>();
            List<Double> tt = new ArrayList<Double>();
            for (int i = reachIdx; i < n; i++) {
                Sample x = s.get(i);
                if (!Stats.finite(x.target)) {
                    continue;
                }
                err.add(x.map - x.target);
                tt.add(x.timeSec);
                overshoot = Math.max(overshoot, x.map - x.target);
            }
            // settled window: from reach + settleDelay to the end, at least the last third
            double tReach = s.get(reachIdx).timeSec;
            double tEnd = tt.get(tt.size() - 1);
            double from = Math.min(tReach + cfg.settleDelaySec, tEnd - (tEnd - tReach) / 3.0);
            List<Double> settledErr = new ArrayList<Double>();
            for (int i = 0; i < err.size(); i++) {
                if (tt.get(i) >= from) {
                    settledErr.add(err.get(i));
                }
            }
            if (settledErr.isEmpty()) {
                settledErr.addAll(err);
            }
            settled = settledErr.size();
            ssErr = Stats.mean(settledErr);
            List<Double> abs = new ArrayList<Double>();
            for (double e : settledErr) {
                abs.add(Math.abs(e));
            }
            meanAbs = Stats.mean(abs);
            // oscillation: alternating extrema of the smoothed error beyond the deadband
            double[] sm = smooth(err, 3);
            List<Integer> extrema = new ArrayList<Integer>();
            for (int i = 1; i < sm.length - 1; i++) {
                boolean max = sm[i] > sm[i - 1] && sm[i] >= sm[i + 1];
                boolean min = sm[i] < sm[i - 1] && sm[i] <= sm[i + 1];
                if ((max || min) && Math.abs(sm[i]) > cfg.oscillationDeadbandKpa) {
                    if (!extrema.isEmpty()) {
                        int last = extrema.get(extrema.size() - 1);
                        if (Math.signum(sm[last]) == Math.signum(sm[i])) {
                            // same sign: keep the larger one
                            if (Math.abs(sm[i]) > Math.abs(sm[last])) {
                                extrema.set(extrema.size() - 1, i);
                            }
                            continue;
                        }
                    }
                    extrema.add(i);
                }
            }
            if (extrema.size() >= 2) {
                double sum = 0;
                for (int idx : extrema) {
                    sum += Math.abs(sm[idx]);
                }
                oscAmp = sum / extrema.size();
                oscCycles = extrema.size() / 2.0;
                double span = tt.get(extrema.get(extrema.size() - 1)) - tt.get(extrema.get(0));
                oscPeriod = span / Math.max(0.5, (extrema.size() - 1) / 2.0);
            } else if (extrema.size() == 1) {
                oscAmp = Math.abs(sm[extrema.get(0)]);
                oscCycles = 0.5;
            }
        }
        double reachRpm = reached ? s.get(reachIdx).rpm : Double.NaN;
        double spoolSec = reached ? s.get(reachIdx).timeSec - pull.startTime() : Double.NaN;
        return new ResponseMetrics(pull, true, reached, targetMed, peak, overshoot, rise, ssErr, meanAbs,
                oscAmp, oscCycles, oscPeriod, active == 0 ? 0 : (double) satHi / active,
                active == 0 ? 0 : (double) satLo / active, settled, reachRpm, spoolSec);
    }

    /**
     * Where a pull first reached a fixed target: {rpm, seconds after the throttle opened}, or null
     * when it never did. Used on open-loop characterization pulls to find the physical spool floor
     * (valve shut) that the closed loop is later compared against.
     */
    public static double[] reach(Pull pull, double targetKpa, double tolKpa, double minRpm) {
        for (Sample x : pull.samples()) {
            if (x.rpm >= minRpm && Stats.finite(x.map) && x.map >= targetKpa - tolKpa) {
                return new double[]{x.rpm, x.timeSec - pull.startTime()};
            }
        }
        return null;
    }

    static double[] smooth(List<Double> v, int w) {
        double[] out = new double[v.size()];
        int half = w / 2;
        for (int i = 0; i < v.size(); i++) {
            double s = 0;
            int c = 0;
            for (int k = Math.max(0, i - half); k <= Math.min(v.size() - 1, i + half); k++) {
                s += v.get(k);
                c++;
            }
            out[i] = s / c;
        }
        return out;
    }
}
