package io.github.xadmiral.boostautotune.core.sweep;

import io.github.xadmiral.boostautotune.core.learn.Pull;
import io.github.xadmiral.boostautotune.core.learn.TorqueProxy;
import io.github.xadmiral.boostautotune.core.model.Grid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What one sweep run produced, and the final decision when the sweep is over. */
public final class SweepReport {
    /** Per-RPM-bin decision of a finished sweep. */
    public static final class BinDecision {
        public final double rpm;
        public final double chosenOffset;
        public final double baselineAccel;
        public final double chosenAccel;
        public final double gainPct;
        public final boolean knockLimited;
        public final String reason;

        BinDecision(double rpm, double chosenOffset, double baselineAccel, double chosenAccel, double gainPct,
                    boolean knockLimited, String reason) {
            this.rpm = rpm;
            this.chosenOffset = chosenOffset;
            this.baselineAccel = baselineAccel;
            this.chosenAccel = chosenAccel;
            this.gainPct = gainPct;
            this.knockLimited = knockLimited;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%.0f rpm: %+.1f deg (%s, gain %+.1f%%)%s", rpm, chosenOffset, reason,
                    gainPct, knockLimited ? " [knock-limited]" : "");
        }
    }

    public final SweepPlan plan;
    public final List<Pull> pulls = new ArrayList<Pull>();
    public TorqueProxy.AccelProfile accel;
    public final List<KnockGuard.Event> knockEvents = new ArrayList<KnockGuard.Event>();
    public final List<String> messages = new ArrayList<String>();
    public boolean sweepDone;
    public SweepPlan nextPlan;
    public final List<BinDecision> decisions = new ArrayList<BinDecision>();
    public Grid resultTable;
    public Grid originalTable;

    SweepReport(SweepPlan plan) {
        this.plan = plan;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(plan.title()).append('\n');
        sb.append("Pulls: ").append(pulls.size());
        if (accel != null) {
            sb.append(", accelerating samples: ").append(accel.totalSamples());
        }
        sb.append('\n');
        if (accel != null && accel.totalSamples() > 0) {
            sb.append("  dRPM/dt per bin: ").append(accel.describe()).append('\n');
        }
        for (KnockGuard.Event e : knockEvents) {
            sb.append("  KNOCK: ").append(e).append('\n');
        }
        for (String m : messages) {
            sb.append("  ").append(m).append('\n');
        }
        if (sweepDone) {
            sb.append("Decisions per RPM bin:\n");
            for (BinDecision d : decisions) {
                sb.append("  ").append(d).append('\n');
            }
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
