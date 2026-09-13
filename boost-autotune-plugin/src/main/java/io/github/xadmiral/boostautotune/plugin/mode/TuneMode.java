package io.github.xadmiral.boostautotune.plugin.mode;

/** The four things the plugin can tune. */
public enum TuneMode {
    BOOST("Boost: closed-loop autotune (pulls)"),
    VVT_PID("VVT: closed-loop PID (normal driving)"),
    VVT_SWEEP("VVT: cam target sweep (pulls)"),
    IGNITION_SWEEP("Ignition: advance sweep with knock guard (pulls)");

    private final String label;

    TuneMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
