package io.github.xadmiral.boostautotune.core.session;

/** Lifecycle of the session. */
public enum SessionState {
    /** Not initialized with ECU data yet. */
    IDLE,
    /** A plan is ready; the ECU should be prepared with it before recording. */
    READY,
    /** Samples are being collected. */
    RECORDING,
    /** A run was analysed; the report awaits commit. */
    REVIEW,
    /** Finished successfully. */
    DONE,
    /** Stopped by the safety monitor or the user. */
    ABORTED
}
