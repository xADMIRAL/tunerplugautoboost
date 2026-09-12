package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.model.PidGains;

import java.util.ArrayList;
import java.util.List;

/** Proposed gains with the reasoning behind them. */
public final class PidSuggestion {
    public final PidGains before;
    public final PidGains after;
    public final boolean converged;
    public final boolean unreachable;
    /** Overshoot came from the initial rise (feed-forward), not from PID ringing. */
    public boolean transientOvershoot;
    public final List<String> rationale = new ArrayList<String>();

    PidSuggestion(PidGains before, PidGains after, boolean converged, boolean unreachable) {
        this.before = before;
        this.after = after;
        this.converged = converged;
        this.unreachable = unreachable;
    }

    public boolean changed() {
        return !after.approxEquals(before, 1e-9);
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(before).append(" -> ").append(after);
        if (converged) {
            sb.append(" (converged)");
        }
        for (String r : rationale) {
            sb.append("\n  - ").append(r);
        }
        return sb.toString();
    }
}
