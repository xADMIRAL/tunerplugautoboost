package io.github.xadmiral.boostautotune.core.session;

/** Which kind of run the session is asking for. */
public enum SessionPhase {
    /** Open-loop duty ladder to learn the duty → boost relationship. */
    CHARACTERIZE,
    /** Closed-loop runs tuning bias table, PID gains and targets. */
    CLOSED_LOOP,
    /** All stages converged. */
    DONE
}
