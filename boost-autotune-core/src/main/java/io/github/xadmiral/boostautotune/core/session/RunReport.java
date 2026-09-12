package io.github.xadmiral.boostautotune.core.session;

import io.github.xadmiral.boostautotune.core.learn.BiasBuildResult;
import io.github.xadmiral.boostautotune.core.learn.PidSuggestion;
import io.github.xadmiral.boostautotune.core.learn.Pull;
import io.github.xadmiral.boostautotune.core.learn.ResponseMetrics;
import io.github.xadmiral.boostautotune.core.learn.SampleState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Analysis of a finished run and the plan derived from it. */
public final class RunReport {
    public final RunPlan plan;
    public final List<Pull> pulls = new ArrayList<Pull>();
    public final List<ResponseMetrics> metrics = new ArrayList<ResponseMetrics>();
    public final Map<SampleState, Integer> sampleCounts = new EnumMap<SampleState, Integer>(SampleState.class);
    public final List<String> messages = new ArrayList<String>();
    public BiasBuildResult biasResult;
    public PidSuggestion pidSuggestion;
    public final List<String> targetTrims = new ArrayList<String>();
    public double observedPeakKpa = Double.NaN;
    public double plantGainKpaPerPct = Double.NaN;
    public boolean stageConverged;
    public boolean sessionDone;
    public RunPlan nextPlan;

    RunReport(RunPlan plan) {
        this.plan = plan;
    }

    public int totalSamples() {
        int n = 0;
        for (int v : sampleCounts.values()) {
            n += v;
        }
        return n;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(plan.title()).append('\n');
        sb.append(String.format(Locale.US, "Samples: %d, pulls: %d, steady: %d, peak %.1f kPa\n",
                totalSamples(), pulls.size(), sampleCounts.containsKey(SampleState.STEADY) ? sampleCounts.get(SampleState.STEADY) : 0,
                observedPeakKpa));
        for (ResponseMetrics m : metrics) {
            sb.append("  pull: ").append(m.summary()).append('\n');
        }
        if (biasResult != null) {
            sb.append("  ").append(biasResult.summary()).append('\n');
            for (String n : biasResult.notes) {
                sb.append("    ").append(n).append('\n');
            }
            int shown = 0;
            for (BiasBuildResult.CellChange c : biasResult.changes) {
                if (shown++ >= 6) {
                    break;
                }
                sb.append("    ").append(c).append('\n');
            }
        }
        if (pidSuggestion != null) {
            sb.append("  PID: ").append(pidSuggestion.summary().replace("\n", "\n  ")).append('\n');
        }
        for (String t : targetTrims) {
            sb.append("  trim: ").append(t).append('\n');
        }
        for (String m : messages) {
            sb.append("  ").append(m).append('\n');
        }
        if (nextPlan != null) {
            sb.append("Next: ").append(nextPlan.title()).append('\n');
            for (String n : nextPlan.notes) {
                sb.append("  ").append(n).append('\n');
            }
        }
        return sb.toString();
    }
}
