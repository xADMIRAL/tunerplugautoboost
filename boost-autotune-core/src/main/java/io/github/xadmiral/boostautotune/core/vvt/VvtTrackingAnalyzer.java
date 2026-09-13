package io.github.xadmiral.boostautotune.core.vvt;

import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a run of ordinary driving into tracking figures for the VVT closed loop:
 * <ul>
 * <li>accuracy on the parts where the target holds still (steady error, offset, ringing),</li>
 * <li>lag as the time shift that best aligns the cam angle with the target (cross-correlation).</li>
 * </ul>
 */
public final class VvtTrackingAnalyzer {
    private VvtTrackingAnalyzer() {
    }

    public static VvtTrackingMetrics analyze(List<Sample> run, VvtPidConfig cfg) {
        int n = run.size();
        double[] t = new double[n];
        double[] ang = new double[n];
        double[] tgt = new double[n];
        boolean[] active = new boolean[n];
        double[] rate = new double[n];
        int activeCount = 0;
        for (int i = 0; i < n; i++) {
            Sample s = run.get(i);
            t[i] = s.timeSec;
            ang[i] = s.vvtAngle;
            tgt[i] = s.vvtTarget;
            boolean ok = Stats.finite(s.vvtAngle) && Stats.finite(s.vvtTarget) && s.rpm >= cfg.minRpm
                    && s.clt >= cfg.minCltC && Math.abs(s.vvtTarget) >= cfg.activeMinTargetDeg;
            active[i] = ok;
            if (ok) {
                activeCount++;
            }
            rate[i] = i > 0 && Stats.finite(tgt[i]) && Stats.finite(tgt[i - 1]) && t[i] > t[i - 1]
                    ? (tgt[i] - tgt[i - 1]) / (t[i] - t[i - 1]) : 0;
        }
        if (activeCount == 0) {
            return new VvtTrackingMetrics(n, 0, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, 0, Double.NaN);
        }
        // steady-target accuracy
        double sum = 0, sumAbs = 0, sumSq = 0;
        int steadyN = 0, within = 0;
        List<Double> steadyErr = new ArrayList<Double>();
        List<Double> steadyT = new ArrayList<Double>();
        for (int i = 0; i < n; i++) {
            if (!active[i] || Math.abs(rate[i]) > cfg.steadyTargetDegPerSec) {
                continue;
            }
            double e = ang[i] - tgt[i];
            sum += e;
            sumAbs += Math.abs(e);
            sumSq += e * e;
            steadyN++;
            if (Math.abs(e) <= cfg.steadyStateTolDeg) {
                within++;
            }
            steadyErr.add(e);
            steadyT.add(t[i]);
        }
        double meanErr = steadyN == 0 ? Double.NaN : sum / steadyN;
        double meanAbs = steadyN == 0 ? Double.NaN : sumAbs / steadyN;
        double rms = steadyN == 0 ? Double.NaN : Math.sqrt(sumSq / steadyN);
        double withinFrac = steadyN == 0 ? 0 : (double) within / steadyN;

        // lag: shift the target forward in time until it best matches the angle
        double dt = n > 1 ? (t[n - 1] - t[0]) / (n - 1) : 0.04;
        int maxShift = dt > 0 ? (int) Math.round(1.0 / dt) : 0;
        double bestMse = Double.POSITIVE_INFINITY;
        int bestShift = 0;
        for (int k = 0; k <= maxShift; k++) {
            double mse = 0;
            int c = 0;
            for (int i = k; i < n; i++) {
                if (!active[i] || !Stats.finite(tgt[i - k])) {
                    continue;
                }
                double d = ang[i] - tgt[i - k];
                mse += d * d;
                c++;
            }
            if (c < 20) {
                continue;
            }
            mse /= c;
            if (mse < bestMse * 0.995) { // prefer the smaller shift on a plateau
                bestMse = mse;
                bestShift = k;
            }
        }
        double lagSec = bestShift * dt;

        // ringing: alternating extrema of the steady-target error beyond the deadband, close together
        double[] sm = new double[steadyErr.size()];
        for (int i = 0; i < sm.length; i++) {
            double a = 0;
            int c = 0;
            for (int k = Math.max(0, i - 1); k <= Math.min(sm.length - 1, i + 1); k++) {
                a += steadyErr.get(k);
                c++;
            }
            sm[i] = a / c;
        }
        List<Integer> extrema = new ArrayList<Integer>();
        List<Integer> bestRun = new ArrayList<Integer>();
        for (int i = 1; i < sm.length - 1; i++) {
            if (steadyT.get(i) - steadyT.get(i - 1) > 0.5) {
                if (extrema.size() > bestRun.size()) {
                    bestRun = new ArrayList<Integer>(extrema);
                }
                extrema.clear(); // gap in the steady data
            }
            boolean max = sm[i] > sm[i - 1] && sm[i] >= sm[i + 1];
            boolean min = sm[i] < sm[i - 1] && sm[i] <= sm[i + 1];
            if ((max || min) && Math.abs(sm[i]) > cfg.oscillationDeadbandDeg) {
                if (!extrema.isEmpty()) {
                    int last = extrema.get(extrema.size() - 1);
                    if (Math.signum(sm[last]) == Math.signum(sm[i])) {
                        if (Math.abs(sm[i]) > Math.abs(sm[last])) {
                            extrema.set(extrema.size() - 1, i);
                        }
                        continue;
                    }
                    if (steadyT.get(i) - steadyT.get(last) > 1.0) {
                        if (extrema.size() > bestRun.size()) {
                            bestRun = new ArrayList<Integer>(extrema);
                        }
                        extrema.clear();
                    }
                }
                extrema.add(i);
            }
        }
        if (extrema.size() > bestRun.size()) {
            bestRun = extrema;
        }
        double amp = 0, cycles = 0, period = Double.NaN;
        if (bestRun.size() >= 2) {
            double a = 0;
            for (int idx : bestRun) {
                a += Math.abs(sm[idx]);
            }
            amp = a / bestRun.size();
            cycles = bestRun.size() / 2.0;
            double span = steadyT.get(bestRun.get(bestRun.size() - 1)) - steadyT.get(bestRun.get(0));
            period = span / Math.max(0.5, (bestRun.size() - 1) / 2.0);
        }
        return new VvtTrackingMetrics(n, activeCount, meanErr, meanAbs, rms, withinFrac, lagSec, amp, cycles, period);
    }
}
