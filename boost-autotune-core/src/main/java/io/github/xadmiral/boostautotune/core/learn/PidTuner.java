package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rule-based gain adaptation. Works in relative steps so it is independent of the firmware's gain
 * scaling (MS3 0-200 %, Speeduino 0-200, rusEFI floats). Priorities: stop oscillation, then
 * remove overshoot, then remove steady-state error, then speed up.
 */
public final class PidTuner {
    private PidTuner() {
    }

    public static PidSuggestion suggest(PidGains current, List<ResponseMetrics> metrics, AutotuneConfig cfg,
                                        boolean hasFeedForward) {
        return suggest(current, metrics, cfg, hasFeedForward, false);
    }

    /**
     * @param lenient widen the thresholds by 25 % (used right after a converged run so that noise does not
     *                flip the verdict back and forth)
     */
    public static PidSuggestion suggest(PidGains current, List<ResponseMetrics> metrics, AutotuneConfig cfg,
                                        boolean hasFeedForward, boolean lenient) {
        double k = lenient ? 1.25 : 1.0;
        double ovsLimit = cfg.overshootThresholdKpa * k;
        double ssLimit = cfg.steadyStateTolKpa * k;
        double oscLimit = cfg.oscillationAmplitudeKpa * k;
        List<ResponseMetrics> withTarget = new ArrayList<ResponseMetrics>();
        for (ResponseMetrics m : metrics) {
            if (m.hasTarget) {
                withTarget.add(m);
            }
        }
        if (withTarget.isEmpty()) {
            PidSuggestion s = new PidSuggestion(current, current, false, false);
            s.rationale.add("No pulls with a closed-loop target: gains unchanged");
            return s;
        }
        List<Double> ovs = new ArrayList<Double>();
        List<Double> osc = new ArrayList<Double>();
        List<Double> cycles = new ArrayList<Double>();
        List<Double> ss = new ArrayList<Double>();
        List<Double> absSs = new ArrayList<Double>();
        List<Double> rise = new ArrayList<Double>();
        List<Double> satHi = new ArrayList<Double>();
        int reached = 0;
        for (ResponseMetrics m : withTarget) {
            satHi.add(m.saturatedHighFraction);
            if (!m.reached) {
                continue;
            }
            reached++;
            ovs.add(m.overshootKpa);
            osc.add(m.oscillationAmplitudeKpa);
            cycles.add(m.oscillationCycles);
            if (Stats.finite(m.steadyStateErrorKpa)) {
                ss.add(m.steadyStateErrorKpa);
                absSs.add(Math.abs(m.steadyStateErrorKpa));
            }
            if (Stats.finite(m.riseTimeSec)) {
                rise.add(m.riseTimeSec);
            }
        }
        double step = cfg.pidStepFraction;
        double p = current.p, i = current.i, d = current.d;
        List<String> why = new ArrayList<String>();
        boolean converged = false;
        boolean unreachable = false;
        boolean transientOvershoot = false;
        double satMed = Stats.median(satHi);

        if (reached == 0) {
            if (satMed > 0.5) {
                unreachable = true;
                why.add(String.format(Locale.US,
                        "Target never reached while the valve sat at maximum duty %.0f%% of the time: the target is above what the turbo delivers here (targets will be trimmed), gains unchanged",
                        satMed * 100));
            } else if (cfg.tuneP) {
                p *= 1 + step;
                why.add("Target never reached without duty saturation: raising P");
            }
        } else {
            double ovsMed = Stats.median(ovs);
            double oscMax = Stats.max(osc);
            double cycMax = Stats.max(cycles);
            double ssMed = Stats.median(ss);
            double absSsMed = absSs.isEmpty() ? 0 : Stats.median(absSs);
            double riseMed = rise.isEmpty() ? 0 : Stats.median(rise);
            boolean transientOnly = cycMax <= 0.5;
            if (oscMax >= oscLimit && cycMax >= 1.5) {
                if (cfg.tuneP) {
                    p *= 1 - step;
                }
                if (cfg.tuneI) {
                    i *= 1 - step;
                }
                if (cfg.tuneD && d > 0) {
                    d *= 1 - step / 2;
                }
                why.add(String.format(Locale.US, "Oscillation of %.1f kPa over %.1f cycles: lowering P and I", oscMax, cycMax));
            } else if (ovsMed > ovsLimit && transientOnly && hasFeedForward) {
                // a single bump on the way up with no ringing: the feed-forward is too strong just before
                // the peak (valve lag), the PID cannot fix that. Gains stay, the session trims the bias
                // table around the RPM where the peak was produced.
                transientOvershoot = true;
                why.add(String.format(Locale.US,
                        "Overshoot %.1f kPa on the initial rise without ringing: gains kept, bias table trimmed around spool RPM instead",
                        ovsMed));
            } else if (ovsMed > ovsLimit) {
                if (cfg.tuneP) {
                    p *= 1 - step * 0.6;
                }
                if (cfg.tuneI) {
                    i *= 1 - step * 0.5;
                }
                if (cfg.tuneD) {
                    if (d > 0) {
                        d *= 1 + step;
                    } else if (cfg.allowDerivativeInit) {
                        d = Math.max(cfg.dMin, p * 0.1);
                    }
                }
                why.add(String.format(Locale.US, "Overshoot %.1f kPa above the %.1f kPa limit: softening P/I%s",
                        ovsMed, ovsLimit, cfg.tuneD ? " and adding D" : ""));
            } else if (absSsMed > ssLimit) {
                if (cfg.tuneI) {
                    i *= 1 + (hasFeedForward ? step * 0.5 : step);
                }
                why.add(String.format(Locale.US, "Steady-state error %+.1f kPa: %s", ssMed,
                        hasFeedForward ? "bias table absorbs most of it, nudging I up" : "raising I"));
            } else if (!rise.isEmpty() && riseMed > cfg.maxRiseTimeSec && ovsMed < cfg.overshootThresholdKpa / 2
                    && satMed < 0.3) {
                if (cfg.tuneP) {
                    p *= 1 + step * 0.6;
                }
                why.add(String.format(Locale.US, "Rise time %.2fs is slow with no overshoot: raising P", riseMed));
            } else {
                converged = true;
                why.add(String.format(Locale.US,
                        "Response within limits (overshoot %.1f, ss error %+.1f, oscillation %.1f kPa): gains kept",
                        ovsMed, ssMed, oscMax));
            }
        }
        p = Stats.round(Stats.clamp(p, cfg.pMin, cfg.pMax), cfg.pidDecimals);
        i = Stats.round(Stats.clamp(i, cfg.iMin, cfg.iMax), cfg.pidDecimals);
        d = Stats.round(Stats.clamp(d, cfg.dMin, cfg.dMax), cfg.pidDecimals);
        PidSuggestion s = new PidSuggestion(current, new PidGains(p, i, d), converged, unreachable);
        s.transientOvershoot = transientOvershoot;
        s.rationale.addAll(why);
        return s;
    }

    /** Gentler gains for the first closed-loop run on a freshly built bias table. */
    public static PidGains softStart(PidGains g, AutotuneConfig cfg) {
        double i = Stats.round(Stats.clamp(g.i * 0.5, cfg.iMin, cfg.iMax), cfg.pidDecimals);
        double d = Stats.round(Stats.clamp(g.d * 0.5, cfg.dMin, cfg.dMax), cfg.pidDecimals);
        return new PidGains(g.p, i, d);
    }
}
